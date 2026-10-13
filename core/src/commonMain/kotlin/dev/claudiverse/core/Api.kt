package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the server is and who is calling. [author] is the name forum posts go out under. */
data class ServerAccess(val baseUrl: String, val token: String, val author: String = DEFAULT_AUTHOR) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        const val DEFAULT_AUTHOR = "operator"
    }
}

/**
 * A failed call, with the server's own error text when it sent one.
 * [retryAfterSeconds]: how long the server asks to wait (a 429 on sign-in).
 * [fieldErrors]: a 422's {errors: {field: [message]}}. [code]: a host agent's
 * refusal code (outside_roots, not_allowed, …) relayed by the server.
 */
class ApiException(
    message: String,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long? = null,
    val fieldErrors: Map<String, List<String>> = emptyMap(),
    val code: String? = null,
) : IOException(message) {
    /** The reply's JSON, for a caller that reads more of it than the message (a 409's current profile). */
    internal var body: JsonObject? = null
}

/** A file to upload, opened only when the upload runs: it streams, no copy is held in memory. */
class Upload(val name: String, val size: Long?, val contentType: String?, val open: () -> Source)

/** A file on this machine's disk as an [Upload] (the desktop's file picker gives a path). */
fun fileUpload(path: String, contentType: String?): Upload {
    val file = Path(path)
    val size = SystemFileSystem.metadataOrNull(file)?.size?.takeIf { it >= 0 }
    return Upload(file.name, size, contentType) { SystemFileSystem.source(file).buffered() }
}

/**
 * The claudiverse REST API, as the operator uses it.
 *
 * Reads [access] on every call, so a URL or token changed in settings applies
 * to the next request. Every call is cancellable: cancelling the coroutine
 * abandons the request.
 *
 * The @Throws annotations are for Swift: without them an exception from a
 * suspend function ends the macOS app instead of reaching its caller.
 */
class Api internal constructor(private val access: () -> ServerAccess, private val http: HttpClient) {

    constructor(access: () -> ServerAccess) : this(access, createHttpClient())

    // --- signing in ---

    /**
     * Sign in with the operator's account and mint a token for this client.
     * [client] names the device in the token list (1-64 of letters, digits,
     * space . _ -). 401 bad credentials; 429 carries [ApiException.retryAfterSeconds]:
     * show it and wait, never retry in a loop.
     */
    @Throws(Exception::class)
    suspend fun signIn(email: String, password: String, client: String, scope: String = "super"): SignIn {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("scope", scope)
            put("client", client)
        }
        val o = call(HttpMethod.Post, listOf("auth", "login"), json = body, signedIn = false)
        return SignIn(o.requireStr("token"), o.text("scope"), o.obj("user")?.str("email") ?: email)
    }

    /** Who the current token belongs to. A 401 means it is no longer valid: sign in again. */
    @Throws(Exception::class)
    suspend fun me(): Me {
        val o = get("me")
        return Me(o.obj("user")?.str("email") ?: throw ApiException("Server sent no user"), o.str("scope"))
    }

    /** Revoke the current token, so signing out really ends it. A token minted on the server is never revoked here. */
    @Throws(Exception::class)
    suspend fun signOut() {
        call(HttpMethod.Delete, listOf("auth", "logout"))
    }

    @Throws(Exception::class)
    suspend fun health(): Int = request(HttpMethod.Get, url(listOf("health"), signedIn = false), token = null).code

    // --- seats ---

    /**
     * Running seats; with [includeStopped], every status: running first, then
     * the most recently active stopped ones, bounded. Stopped rows make the
     * list ~4x larger, so they are fetched only when asked for.
     */
    @Throws(Exception::class)
    suspend fun seats(includeStopped: Boolean = false): List<Seat> =
        get("sessions", query = listOf("status" to if (includeStopped) "all" else null, "limit" to if (includeStopped) "100" else null))
            .array("sessions").objects().map(Seat::from)

    @Throws(Exception::class)
    suspend fun seat(id: Long): SeatDetail {
        val o = get("sessions", "$id")
        return SeatDetail(Seat.from(o.obj("session") ?: throw ApiException("Server sent no session")), SeatLive.from(o.obj("live")))
    }

    /** Close a seat's terminal session on its machine. App or super token; synchronous, up to ~10 s. */
    @Throws(Exception::class)
    suspend fun stopSeat(id: Long) {
        post(listOf("sessions", "$id", "stop"))
    }

    /**
     * Start a stopped seat again on its machine, continuing its Claude
     * conversation under its own title and last mode. Like [spawn], the reply
     * only means accepted (202): the outcome is a host_spawn event, op "resume".
     */
    @Throws(Exception::class)
    suspend fun resumeSeat(id: Long): String = post(listOf("sessions", "$id", "resume")).requireStr("spawn_id")

    /**
     * Hand a seat a message. Rejected with status `busy` while the seat is
     * mid-turn, unless [force]: then the seat takes it into its own queue, as
     * if typed while Claude works. A server without `force` support ignores the
     * flag and answers `busy`.
     */
    @Throws(Exception::class)
    suspend fun send(seatId: Long, content: String, force: Boolean = false): SendResult {
        val body = buildJsonObject {
            put("session_id", seatId)
            put("content", content)
            if (force) put("force", true)
        }
        val o = post(listOf("notify", "send"), body)
        return SendResult(o.text("status"), o.str("hint") ?: o.str("error"))
    }

    /**
     * Set a seat's permission mode. App or super token. The reply is the seat's
     * own answer: 200 applied, else an [ApiException] carrying the status (409
     * the seat refused, 503 not connected, 504 no answer: a seat whose build
     * lacks the mode hook).
     */
    @Throws(Exception::class)
    suspend fun setPermissionMode(seatId: Long, mode: String): String =
        post(listOf("sessions", "$seatId", "permission_mode"), buildJsonObject { put("mode", mode) }).str("mode") ?: mode

    /**
     * What adopting a seat would do: the process its machine found, and the
     * profile to start it from. App or super token. [host]: the machine to
     * look on when the seat's row has none (the server matches it there by
     * conversation id only).
     */
    @Throws(Exception::class)
    suspend fun adoptPreview(seatId: Long, host: String? = null): AdoptPreview =
        AdoptPreview.from(get("sessions", "$seatId", "adopt_preview", query = listOf("host" to host)))

    /**
     * Adopt a seat started by hand: create its profile from [fields] (or use
     * the stopped profile [profileId]), stop exactly [process] on its machine,
     * and start the same conversation from the profile. App or super token.
     * Accepted (202, the profile): the outcome arrives as for any profile
     * start. 409 busy (mid-turn: [force] stops it anyway), a running profile
     * or a runner's seat; 422 field errors; 504 its machine is offline.
     */
    @Throws(Exception::class)
    suspend fun adopt(seatId: Long, fields: ProfileFields, process: AdoptProcess, profileId: Long?, force: Boolean, host: String? = null): Profile =
        Profile.from(post(listOf("sessions", "$seatId", "adopt"), adoptBody(fields, process, profileId, force, host)).obj("profile") ?: throw ApiException("Server sent no profile"))

    /**
     * Set a seat's claudiverse tools: "basic" or "full"; null puts it back to
     * its default. Paging is separate ([setNotify]). App or super token (403
     * for a seat's). The seat picks it up within a minute, without a restart.
     */
    @Throws(Exception::class)
    suspend fun setAccess(seatId: Long, level: String?): Seat {
        val o = post(listOf("sessions", "$seatId", "access"), buildJsonObject { put("level", level) })
        return Seat.from(o.obj("session") ?: throw ApiException("Server sent no session"))
    }

    /**
     * The operator's paging switch for a seat: [allow] true or false, for
     * [minutes] (1-10080) or until changed; null clears it and the seat's own
     * default applies. App or super token.
     */
    @Throws(Exception::class)
    suspend fun setNotify(seatId: Long, allow: Boolean?, minutes: Int? = null): Seat {
        val body = buildJsonObject {
            put("allow", allow)
            if (allow != null && minutes != null) put("minutes", minutes)
        }
        return Seat.from(post(listOf("sessions", "$seatId", "notify"), body).obj("session") ?: throw ApiException("Server sent no session"))
    }

    /** Upload a file for a seat to fetch. App or super token. */
    @Throws(Exception::class)
    suspend fun uploadFile(seatId: Long, file: Upload): UploadedFile {
        val form = MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    InputProvider(file.size) { file.open() },
                    Headers.build {
                        file.contentType?.let { append(HttpHeaders.ContentType, it) }
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name.replace("\"", "\\\"")}\"")
                    },
                )
            },
        )
        val o = call(HttpMethod.Post, listOf("sessions", "$seatId", "files"), content = form)
        return UploadedFile(o.requireStr("file_id"), o.str("name") ?: file.name, o.long("size") ?: 0, o.requireStr("url"))
    }

    // --- the operator's attention ---

    /** Open requests from seats asking for the operator. */
    @Throws(Exception::class)
    suspend fun openHumanRequests(): List<HumanRequest> =
        get("human_requests", query = listOf("status" to "open")).array("requests").objects().map(HumanRequest::from)

    /** Resolve every open request from this seat: the operator has opened it. App or super token. */
    @Throws(Exception::class)
    suspend fun resolveHumanRequests(seatId: Long): Int =
        post(listOf("sessions", "$seatId", "human_requests", "resolve")).int("resolved") ?: 0

    // --- machines ---

    /** Machines with a host agent: online, running seats, allowed roots. */
    @Throws(Exception::class)
    suspend fun hosts(): List<Host> = getList(listOf("hosts"), "hosts").map(Host::from)

    @Throws(Exception::class)
    suspend fun recentDirs(host: String): List<RecentDir> =
        getList(listOf("hosts", host, "recent_dirs"), "dirs").map(RecentDir::from)

    /** Subfolders of [path] on [host]; "" lists the host's allowed roots. 403 outside them, 504 when offline. */
    @Throws(Exception::class)
    suspend fun listDir(host: String, path: String): DirListing =
        DirListing.from(get("hosts", host, "ls", query = listOf("path" to path)))

    /**
     * Ask a host's agent to spawn a seat. App or super token. The reply only
     * means accepted (202): the outcome arrives later as a host_spawn event,
     * since starting a seat can take up to 90 s.
     */
    @Throws(Exception::class)
    suspend fun spawn(host: String, path: String, title: String, mode: String, prompt: String?, model: String? = null, pooled: Boolean? = null): String {
        val body = buildJsonObject {
            put("path", path)
            put("title", title)
            put("mode", mode)
            prompt?.takeIf { it.isNotBlank() }?.let { put("prompt", it) }
            // Only what the machine's agent offers (see spawnExtras): an older one would get a 409.
            model?.let { put("model", it) }
            pooled?.let { put("pooled", it) }
        }
        return post(listOf("hosts", host, "spawn"), body).requireStr("spawn_id")
    }

    /** What restarting [seatId] would do, without doing it. App or super token. */
    @Throws(Exception::class)
    suspend fun planSeatRestart(seatId: Long, includeSelf: Boolean = false, caller: Long? = null, confirmProtected: Boolean = false): RestartPlan {
        val o = post(listOf("sessions", "$seatId", "restart"), seatRestartBody(dryRun = true, includeSelf, caller, confirmProtected))
        return RestartPlan.from(o.obj("seat") ?: throw ApiException("Server sent no plan"))
    }

    /**
     * Restart [seatId] on its machine's current Claude Code (adopting it into
     * a tmux seat when it runs outside tmux); returns the restart's id, which
     * its "seat_restart" events carry. A seat that is not idle enough is
     * refused, never queued: [RestartRefused] with the server's sentence.
     * A protected seat ([RestartPlan.isProtected]) needs [confirmProtected].
     */
    @Throws(Exception::class)
    suspend fun restartSeat(seatId: Long, includeSelf: Boolean = false, caller: Long? = null, confirmProtected: Boolean = false): String =
        try {
            post(listOf("sessions", "$seatId", "restart"), seatRestartBody(dryRun = false, includeSelf, caller, confirmProtected)).requireStr("restart_id")
        } catch (e: ApiException) {
            if (e.httpStatus == 409) throw RestartRefused(e.body?.str("error") ?: e.message.orEmpty(), e.body?.str("reason_code"))
            throw e
        }

    /** What restarting the idle seats on [host] (null: every machine) would do: each seat's plan, skips included. */
    @Throws(Exception::class)
    suspend fun planIdleRestart(host: String?, options: RestartOptions = RestartOptions()): List<RestartPlan> =
        post(idleRestartPath(host), restartBody(options, dryRun = true)).array("seats").objects().map(RestartPlan::from)

    /** Restart the idle seats on [host] (null: every machine), one at a time; progress comes as events ([Restarts]). */
    @Throws(Exception::class)
    suspend fun restartIdle(host: String?, options: RestartOptions = RestartOptions()): RestartBatchStarted {
        val o = post(idleRestartPath(host), restartBody(options, dryRun = false))
        return RestartBatchStarted(o.requireStr("batch_id"), o.array("seats").objects().map(RestartPlan::from))
    }

    private fun idleRestartPath(host: String?) =
        if (host == null) listOf("hosts", "restart_idle") else listOf("hosts", host, "restart_idle")

    private fun seatRestartBody(dryRun: Boolean, includeSelf: Boolean, caller: Long?, confirmProtected: Boolean) = buildJsonObject {
        if (dryRun) put("dry_run", true)
        if (includeSelf) put("include_self", true)
        if (confirmProtected) put("confirm_protected", true)
        caller?.let { put("caller", it) }
    }

    /**
     * Add an allowed folder on [host]. Super token. The machine decides: 403
     * (code not_allowed) unless its own config allows remote roots ([Host.remoteRoots]),
     * 404 when the folder does not exist there, 504 when it is offline.
     * Returns the machine's roots after the change.
     */
    @Throws(Exception::class)
    suspend fun addRoot(host: String, path: String): List<String> =
        post(listOf("hosts", host, "roots"), buildJsonObject { put("path", path) }).array("roots").strings()

    /**
     * Allow [path] on [host] without duplicates: nothing is added when an
     * allowed folder already holds it, and allowed folders inside it are
     * removed once it is added (removing only after, so nothing is ever
     * uncovered). Super token, and the machine must allow remote roots
     * ([addRoot]). Returns the machine's roots after.
     */
    @Throws(Exception::class)
    suspend fun allowFolder(host: String, path: String, current: List<String>): List<String> {
        val folder = normalFolder(path)
        if (current.any { isInside(folder, normalFolder(it)) }) return current
        var roots = addRoot(host, folder)
        for (child in roots.filter { it != folder && isInside(normalFolder(it), folder) }) roots = removeRoot(host, child)
        return roots
    }

    /** Remove an allowed folder on [host]: always allowed, since it only narrows. Super token. */
    @Throws(Exception::class)
    suspend fun removeRoot(host: String, path: String): List<String> =
        call(HttpMethod.Delete, listOf("hosts", host, "roots"), json = buildJsonObject { put("path", path) }).array("roots").strings()

    /** Mint a host token for a new machine's agent config; [host] is that machine's hostname. Super token. */
    @Throws(Exception::class)
    suspend fun hostToken(host: String): HostToken {
        val o = post(listOf("hosts", host, "token"))
        return HostToken(o.str("name") ?: host, o.requireStr("token"))
    }

    // --- fleets ---

    /** Every fleet with its runner spaces (from cv-runner's heartbeat) and its running seats. */
    @Throws(Exception::class)
    suspend fun fleets(): List<Fleet> = getList(listOf("fleets"), "fleets").map(Fleet::from)

    // --- seat profiles ---

    /** Every profile with its derived status. Any token. */
    @Throws(Exception::class)
    suspend fun profiles(): List<Profile> = getList(listOf("profiles"), "profiles").map(Profile::from)

    /** Create a profile. Super token. [name] (the seat title) is 1-64 of A-Z a-z 0-9 . _ - and fixed for life. */
    @Throws(Exception::class)
    suspend fun createProfile(name: String, fields: ProfileFields): ProfileSave = saving {
        val body = JsonObject(fields.toJson() + ("name" to JsonPrimitive(name)))
        Profile.from(post(listOf("profiles"), body).obj("profile") ?: throw ApiException("Server sent no profile"))
    }

    /**
     * Save an edit. Super token. [revision] is the one the editor read: if the
     * profile has moved on since, the answer is [ProfileSave.Conflict] with the
     * current one. Edits apply at the seat's next start.
     */
    @Throws(Exception::class)
    suspend fun updateProfile(id: Long, revision: Long, fields: ProfileFields): ProfileSave = saving {
        val body = JsonObject(fields.toJson() + ("revision" to JsonPrimitive(revision)))
        Profile.from(call(HttpMethod.Patch, listOf("profiles", "$id"), json = body).obj("profile") ?: throw ApiException("Server sent no profile"))
    }

    /**
     * Rename a profile, and its seat with it. Super token. [revision] is the
     * one the editor read. A running, idle seat is stopped and started again
     * under the new name on the same conversation ([ProfileRename.Renamed.restarting];
     * its outcome arrives as for a restart). 504 (machine offline) and other
     * failures throw.
     */
    @Throws(Exception::class)
    suspend fun renameProfile(id: Long, revision: Long, name: String): ProfileRename {
        val body = JsonObject(mapOf("name" to JsonPrimitive(name), "revision" to JsonPrimitive(revision)))
        val reply = request(HttpMethod.Post, url(listOf("profiles", "$id", "rename")), token = access().token) {
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val json = parse(reply.body) as? JsonObject
        if (reply.code in 200..299) {
            val profile = Profile.from(json?.obj("profile") ?: throw ApiException("Server sent no profile"))
            val moved = json.obj("moved")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { k to it } }?.toMap().orEmpty()
            return ProfileRename.Renamed(profile, restarting = reply.code == 202, moved = moved)
        }
        val e = failure(reply, json)
        val current = json?.obj("profile")?.let { runCatching { Profile.from(it) }.getOrNull() }
        return when {
            reply.code == 409 && e.code == "stale" && current != null -> ProfileRename.Conflict(current)
            reply.code == 409 && e.code != null -> ProfileRename.Refused(e.code, json?.str("error") ?: e.message.orEmpty())
            reply.code == 422 && e.fieldErrors.isNotEmpty() -> ProfileRename.Invalid(e.fieldErrors)
            else -> throw e
        }
    }

    /** Delete a profile. Super token. 409 while a seat runs from it. */
    @Throws(Exception::class)
    suspend fun deleteProfile(id: Long) {
        request(HttpMethod.Delete, url(listOf("profiles", "$id")), token = access().token).let { reply ->
            if (reply.code !in 200..299) throw failure(reply, parse(reply.body) as? JsonObject)
        }
    }

    /**
     * Start a profile's seat on its machine. App or super token. The reply only
     * means accepted (202, the profile as it is now): the outcome arrives as a
     * "profile" event and in [Profile.lastStart]. 409 already running, 504
     * machine offline, 429 one start per 10 s per machine; 403/400/404 are the
     * machine's refusal, with [ApiException.code].
     */
    @Throws(Exception::class)
    suspend fun startProfile(id: Long): Profile = profileControl(id, "start")

    /** Stop and start again, so an edit applies. As [startProfile]. */
    @Throws(Exception::class)
    suspend fun restartProfile(id: Long): Profile = profileControl(id, "restart")

    /** Stop a profile's seat: its machine closes the seat's terminal session. App or super token. */
    @Throws(Exception::class)
    suspend fun stopProfile(id: Long) {
        post(listOf("profiles", "$id", "stop"))
    }

    private suspend fun profileControl(id: Long, op: String): Profile =
        Profile.from(post(listOf("profiles", "$id", op)).obj("profile") ?: throw ApiException("Server sent no profile"))

    /** A save's 422 and 409 as results: the editor shows them, they are not failures of the call. */
    private suspend fun saving(block: suspend () -> Profile): ProfileSave = try {
        ProfileSave.Saved(block())
    } catch (e: ApiException) {
        val current = e.body?.obj("profile")?.let { runCatching { Profile.from(it) }.getOrNull() }
        when {
            e.httpStatus == 409 && current != null -> ProfileSave.Conflict(current)
            e.httpStatus == 422 && e.fieldErrors.isNotEmpty() -> ProfileSave.Invalid(e.fieldErrors)
            else -> throw e
        }
    }

    // --- tokens ---

    /** Every token on the server, without values. Super token. */
    @Throws(Exception::class)
    suspend fun tokens(): List<TokenInfo> = get("tokens").array("tokens").objects().map(TokenInfo::from)

    /**
     * Revoke a token, e.g. a lost phone's. Super token. 409 for an unlabelled
     * "api" token: it may be the fleet's shared seat token, revoked only on the server.
     */
    @Throws(Exception::class)
    suspend fun revokeToken(id: Long) {
        call(HttpMethod.Delete, listOf("tokens", "$id"))
    }

    // --- the Android app's updates ---

    /** The newest published build of the Android app, or null when none is published (404). */
    @Throws(Exception::class)
    suspend fun latestBuild(): AppBuild? = try {
        AppBuild.from(get("app", "builds", "latest"))
    } catch (e: ApiException) {
        if (e.httpStatus == 404) null else throw e
    }

    /**
     * Download [path] (server-relative, e.g. "/api/app/builds/1/apk") into
     * [sink], reporting bytes so far. Streams: an APK is tens of MB.
     */
    @Throws(Exception::class)
    suspend fun download(path: String, sink: Sink, progress: (Long) -> Unit) {
        val a = access()
        val url = parseBase(a.baseUrl.trimEnd('/') + path).build()
        http.prepareGet(url) { header(HttpHeaders.Authorization, "Bearer ${a.token}") }.execute { response ->
            if (!response.status.isSuccess()) throw ApiException("HTTP ${response.status.value}: download failed", response.status.value)
            val channel = response.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = channel.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                sink.write(buf, 0, n)
                total += n
                progress(total)
            }
            sink.flush()
        }
    }

    // --- forum, tasks, pool ---

    @Throws(Exception::class)
    suspend fun channels(): List<ForumChannel> =
        get("forum", "channels").array("channels").objects().map(ForumChannel::from)

    /**
     * Newest posts in a channel, replies included. Not `top_level`: that form is
     * compact and carries no bodies, which is useless to read.
     */
    @Throws(Exception::class)
    suspend fun posts(channel: String, before: Long? = null, limit: Int = 50): List<ForumPost> =
        get("forum", "channels", channel, "posts", query = listOf("limit" to "$limit", "before" to before?.toString()))
            .array("posts").objects().map(ForumPost::from)

    @Throws(Exception::class)
    suspend fun thread(postId: Long): ForumThread {
        val o = get("forum", "posts", "$postId", query = listOf("thread" to "true"))
        return ForumThread(ForumPost.from(o.obj("post") ?: throw ApiException("Server sent no post")), o.array("replies").objects().map(ForumPost::from))
    }

    @Throws(Exception::class)
    suspend fun createPost(channel: String, body: String, parentId: Long? = null, title: String? = null): ForumPost {
        val payload = buildJsonObject {
            put("channel", channel)
            put("self", access().author)
            put("body", body)
            parentId?.let { put("parent_id", it) }
            title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        }
        return ForumPost.from(post(listOf("forum", "posts"), payload).obj("post") ?: throw ApiException("Server sent no post"))
    }

    @Throws(Exception::class)
    suspend fun tasks(state: String?, fleet: String?, limit: Int = 100): List<FleetTask> =
        get("tasks", query = listOf("state" to state, "fleet" to fleet, "limit" to "$limit"))
            .array("tasks").objects().map(FleetTask::from)

    /**
     * Each account's daily use of its weekly limit for the last [days] days,
     * local to [tz] (the server's default zone when null), with the raw
     * readings when [raw]. Any token.
     */
    @Throws(Exception::class)
    suspend fun usageHistory(days: Int = 28, tz: String? = null, raw: Boolean = false): UsageHistory =
        UsageHistory.from(get("anthropic_auths", "usage_history", query = listOf("days" to "$days", "tz" to tz, "raw" to if (raw) "1" else null)))

    @Throws(Exception::class)
    suspend fun pool(): Pool {
        val o = get("anthropic_auths")
        val accounts = o.array("auths").objects().map(AnthropicAccount::from)
        val seats = o.array("seats").objects().map {
            SeatAssignment(it.text("title"), it.str("label"), it.str("via"), it.str("assigned_at"))
        }
        return Pool(accounts, seats, o.long("current_id"))
    }

    /**
     * Make an account the pool's first choice: it ranks first and the others
     * keep their order behind it. App or super token: it moves the fleet's
     * usage. Takes effect on pooled seats' next request; a preferred account
     * that is cooling down or needs re-auth is still skipped until it recovers.
     */
    @Throws(Exception::class)
    suspend fun makePreferred(accountId: Long): PoolOrder {
        val o = post(listOf("anthropic_auths", "$accountId", "preferred"))
        return PoolOrder(o.array("auths").objects().map(AnthropicAccount::from), o.long("current_id"))
    }

    /** Begin a sign-in to an Anthropic account: the page to open, and what goes back with its code. App or super token. */
    @Throws(Exception::class)
    suspend fun startAccountSignIn(): AccountSignIn {
        val o = post(listOf("anthropic_auths", "authorize"))
        return AccountSignIn(o.str("url") ?: throw ApiException("Server sent no sign-in page"), o.text("verifier"), o.text("state"))
    }

    /**
     * Give [account] fresh credentials from [code], what the sign-in page
     * [signIn] opened shows (pasted as is: "code#state"). The account keeps
     * its id, label and priority. A code for a different Anthropic account
     * answers [Reauthorization.OtherAccount] and changes nothing, unless
     * [force]. From a server without the re-authorize call, the account is
     * enrolled again under its label and priority, which replaces its
     * credentials in place but cannot check whose code it is.
     */
    @Throws(Exception::class)
    suspend fun reauthorize(account: AnthropicAccount, signIn: AccountSignIn, code: String, force: Boolean = false): Reauthorization {
        val pasted = code.trim()
        val body = buildJsonObject {
            put("code", pasted)
            put("verifier", signIn.verifier)
            put("state", signIn.state)
            if (force) put("force", true)
        }
        return try {
            Reauthorization.Done(post(listOf("anthropic_auths", "${account.id}", "reauthorize", "complete"), body).let(::accountIn))
        } catch (e: ApiException) {
            val json = e.body
            when {
                e.httpStatus == 409 -> Reauthorization.OtherAccount(
                    stored = json?.str("stored_email") ?: account.email,
                    signedIn = json?.str("signed_in_email"),
                    message = json?.str("detail") ?: e.message.orEmpty(),
                )
                // No such route: an older server answers Phoenix's bare 404, with no error of its own.
                // An unknown account is {error: "unknown auth"}, and must not be enrolled again.
                e.httpStatus == 404 && json?.str("error") == null -> {
                    val enrol = buildJsonObject {
                        put("label", account.label)
                        put("code", pasted)
                        put("verifier", signIn.verifier)
                        put("state", signIn.state)
                        // Without it the account would drop to the default priority, last in the pool.
                        account.priority?.let { put("priority", it) }
                    }
                    Reauthorization.Done(withDetail { post(listOf("anthropic_auths", "complete"), enrol) }.let(::accountIn))
                }
                else -> throw detailed(e)
            }
        }
    }

    /** The account a sign-in answered with: the rendered auth, bare or under "auth". */
    private fun accountIn(o: JsonObject): AnthropicAccount? =
        runCatching { AnthropicAccount.from(o.obj("auth") ?: o) }.getOrNull()

    private suspend fun <T> withDetail(block: suspend () -> T): T =
        try { block() } catch (e: ApiException) { throw detailed(e) }

    /** The server's own sentence ("the pasted code did not match this attempt") rather than only its code. */
    private fun detailed(e: ApiException): ApiException {
        val detail = e.body?.str("detail") ?: return e
        return ApiException("HTTP ${e.httpStatus}: $detail", e.httpStatus, e.retryAfterSeconds, e.fieldErrors, e.code).also { it.body = e.body }
    }

    @Throws(Exception::class)
    suspend fun incidents(): Incidents {
        val o = get("incidents")
        val latest = o.array("latest").objects().map { Incident(it.pretty()) }
        return Incidents(o.int("total") ?: 0, o.str("since"), latest)
    }

    // --- plumbing ---

    /** A finished response: status and the whole body, already read. */
    private class Reply(val code: Int, val message: String, val body: String, val retryAfter: String?)

    private fun parseBase(text: String): URLBuilder {
        val builder = try {
            URLBuilder(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Not a valid server URL: $text")
        }
        if ((builder.protocol != URLProtocol.HTTP && builder.protocol != URLProtocol.HTTPS) || builder.host.isBlank()) {
            throw ApiException("Not a valid server URL: $text")
        }
        return builder
    }

    /** `<base>/api/<segments>?<query>`; each segment is encoded whole, so a name cannot add a path level. */
    private fun url(segments: List<String>, query: List<Pair<String, String?>> = emptyList(), signedIn: Boolean = true): Url {
        val a = access()
        if (a.baseUrl.isBlank() || (signedIn && a.token.isBlank())) throw ApiException("Set the server URL and token in Settings")
        val builder = parseBase(a.baseUrl)
        builder.appendPathSegments(listOf("api") + segments, encodeSlash = true)
        query.forEach { (k, v) -> if (v != null) builder.parameters.append(k, v) }
        return builder.build()
    }

    private suspend fun get(vararg segments: String, query: List<Pair<String, String?>> = emptyList()): JsonObject =
        call(HttpMethod.Get, segments.toList(), query)

    private suspend fun post(segments: List<String>, body: JsonObject = JsonObject(emptyMap())): JsonObject =
        call(HttpMethod.Post, segments, json = body)

    private suspend fun call(
        method: HttpMethod,
        segments: List<String>,
        query: List<Pair<String, String?>> = emptyList(),
        json: JsonObject? = null,
        content: OutgoingContent? = null,
        signedIn: Boolean = true,
    ): JsonObject {
        val reply = request(method, url(segments, query, signedIn), token = if (signedIn) access().token else null) {
            json?.let { setBody(TextContent(it.toString(), ContentType.Application.Json)) }
            content?.let { setBody(it) }
        }
        val parsed = parse(reply.body) as? JsonObject
        if (reply.code !in 200..299) throw failure(reply, parsed)
        return parsed ?: throw ApiException("Server sent something that is not JSON")
    }

    /** A list endpoint: a bare JSON array, or an object holding it under [key]; either shape is accepted. */
    private suspend fun getList(segments: List<String>, key: String): List<JsonObject> {
        val reply = request(HttpMethod.Get, url(segments), token = access().token)
        val parsed = parse(reply.body)
        if (reply.code !in 200..299) throw failure(reply, parsed as? JsonObject)
        return when (parsed) {
            is JsonArray -> parsed.objects()
            is JsonObject -> parsed.array(key).objects()
            else -> throw ApiException("Server sent something that is not JSON")
        }
    }

    private fun parse(body: String) = runCatching { Json.parseToJsonElement(body.trim()) }.getOrNull()

    private fun failure(reply: Reply, json: JsonObject?): ApiException {
        val fields = json?.obj("errors")?.mapValues { (_, v) -> (v as? JsonArray).strings().ifEmpty { listOf(v.toString()) } }.orEmpty()
        val reason = json?.str("error")
            ?: fields.entries.joinToString("; ") { (field, messages) -> "$field ${messages.joinToString(", ")}" }.ifBlank { null }
            ?: reply.body.trim().take(200).ifBlank { reply.message }
        val retryAfter = json?.long("retry_after") ?: reply.retryAfter?.trim()?.toLongOrNull()
        return ApiException("HTTP ${reply.code}: $reason", reply.code, retryAfter, fields, json?.str("code")).also { it.body = json }
    }

    private suspend fun request(
        method: HttpMethod,
        url: Url,
        token: String?,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): Reply {
        val response = http.request(url) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            block()
        }
        return Reply(response.status.value, response.status.description, response.bodyAsText(), response.headers[HttpHeaders.RetryAfter])
    }
}
