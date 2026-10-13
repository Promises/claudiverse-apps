package dev.claudiverse.core

import kotlinx.serialization.json.JsonObject
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/*
 * The server's JSON, read into what the screens use. Fields the apps do not
 * show are left out; a field the server omits reads as null, never as a made-up
 * default that would look like data.
 */

data class Seat(
    val id: Long,
    val title: String,
    val displayTitle: String?,
    val status: String,
    val activity: String?,
    val type: String?,
    val model: String?,
    val lastActivityAt: String?,
    /** The machine the seat runs on. Null until the server reports it. */
    val host: String?,
    /** The fleet it belongs to (e.g. "lorem-fleet"), or null. */
    val fleet: String?,
    /** How it was started: "manual", "spawn" or "runner"; null when unknown. */
    val origin: String?,
    /** Opted in (CLAUDIVERSE_NOTIFY=1) to ask for the operator; only these seats can notify. */
    val canNotify: Boolean = false,
    /** Where the seat's `claude` runs. Null until the seat reports it. */
    val workingDirectory: String? = null,
    val insertedAt: String? = null,
    val mode: String? = null,
    /** Claude Code's own session id, what `--resume` takes. */
    val claudeSessionId: String? = null,
    val clientVersion: String? = null,
    /**
     * The conversation a resumed seat continues. A resumed `claude` keeps
     * reporting a placeholder session id, so the server stores the real one
     * here; it is what a later resume uses.
     */
    val conversationId: String? = null,
    /**
     * The seat's claudiverse tools (server 30e9dfc): basic (reporting and the
     * shared tools) or full (the orchestrator set). [accessExplicit]: the
     * operator set it; otherwise it is the default for the seat's kind.
     * Paging is separate: [canNotify] is the effective right.
     */
    val access: String? = null,
    val accessExplicit: Boolean = false,
    /**
     * The operator's paging switch while it is in force: true or false; null
     * when unset, and the seat's own default ([canNotify]) applies. A timed
     * switch lapses at [notifyUntil].
     */
    val notifyGrant: Boolean? = null,
    val notifyUntil: String? = null,
    /** The profile it was started from; null for a seat started by hand. */
    val profileId: Long? = null,
    /** Background tasks running or pending (shells, monitors, workflows, subagents), from the seat list. */
    val backgroundRunning: Int = 0,
) {
    /**
     * Its name in lists: the title for a profile's seat (the name the operator
     * gave it, which a rename changes), else Claude Code's own title for the
     * conversation when one was reported. That one goes stale: only some
     * runtimes ever update it.
     */
    val label: String get() =
        if (profileId != null || origin == "profile") title else displayTitle?.takeIf { it.isNotBlank() } ?: title

    /** Started by cv-runner: an ephemeral worker, hidden by default on the seat list. */
    val isRunner: Boolean get() = origin == "runner"

    /** Its machine can stop it: it is running and reports which machine it is on. */
    val canStop: Boolean get() = status == "running" && host != null

    /** The conversation this seat holds: what a resume continues, and what ties a resumed seat to its earlier rows. */
    val conversation: String? get() = conversationId ?: claudeSessionId

    /** Stopped, with what a resume needs: its machine and a conversation. */
    val canResume: Boolean get() = status != "running" && host != null && conversation != null

    companion object {
        fun from(o: JsonObject) = Seat(
            id = o.requireLong("id"),
            title = o.text("title"),
            displayTitle = o.str("display_title"),
            status = o.text("status"),
            activity = o.str("activity"),
            type = o.str("type"),
            model = o.str("model"),
            lastActivityAt = o.str("last_activity_at"),
            host = o.str("host"),
            fleet = o.str("fleet"),
            origin = o.str("origin"),
            profileId = o.long("profile_id"),
            backgroundRunning = o.long("background_running")?.toInt() ?: 0,
            canNotify = o.flag("can_notify"),
            workingDirectory = o.str("working_directory"),
            insertedAt = o.str("inserted_at"),
            mode = o.str("mode"),
            claudeSessionId = o.str("claude_session_id"),
            clientVersion = o.str("client_version"),
            conversationId = o.str("conversation_id"),
            access = o.str("access"),
            accessExplicit = o.flag("access_explicit"),
            notifyGrant = o.bool("notify_grant"),
            notifyUntil = o.str("notify_until"),
        )
    }
}

data class SeatLive(
    val status: String?,
    val idle: Boolean?,
    val sidecarConnected: Boolean?,
    /** The mode the seat reports running, when the server knows it. */
    val permissionMode: String?,
) {
    companion object {
        fun from(o: JsonObject?) = o?.let {
            SeatLive(it.str("status"), it.bool("idle"), it.bool("sidecar_connected"), it.str("permission_mode"))
        }
    }
}

/** One seat as its own screen shows it: the row, and its live state when the server has one. */
data class SeatDetail(val seat: Seat, val live: SeatLive?)

/** A top-level seat asking for the operator. One open request per seat; asking again updates it. */
data class HumanRequest(
    val id: Long,
    val sessionId: Long,
    val seatTitle: String,
    val summary: String,
    val detail: String?,
    val insertedAt: String?,
    val updatedAt: String?,
) {
    companion object {
        fun from(o: JsonObject) = HumanRequest(
            id = o.requireLong("id"),
            sessionId = o.requireLong("session_id"),
            seatTitle = o.text("seat_title"),
            summary = o.text("summary"),
            detail = o.str("detail"),
            insertedAt = o.str("inserted_at"),
            updatedAt = o.str("updated_at"),
        )
    }
}

/** A published build of the Android app, offered as an update. */
data class AppBuild(
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val commit: String,
    val sha256: String,
    val size: Long,
    val url: String,
    val notes: String?,
    val insertedAt: String?,
) {
    companion object {
        fun from(o: JsonObject) = AppBuild(
            id = o.requireStr("id"),
            versionCode = o.requireLong("version_code"),
            versionName = o.text("version_name"),
            commit = o.text("commit"),
            sha256 = o.text("sha256").lowercase(),
            size = o.long("size") ?: 0,
            url = o.requireStr("url"),
            notes = o.str("notes"),
            insertedAt = o.str("inserted_at"),
        )
    }
}

/** A machine seats can be spawned on, through its host agent. */
data class Host(
    val name: String,
    val online: Boolean,
    val seats: Int,
    val roots: List<String>,
    /** The machine lets the operator add allowed folders from a client ("allow_remote_roots" in its agent config). */
    val remoteRoots: Boolean = false,
    /** The host agent's version, when it reports one. */
    val version: String? = null,
    /** Permission modes the machine allows a profile (agent 0.7.0+; empty before). */
    val modes: List<String> = emptyList(),
    /** Runtimes the machine can start a profile's seat with (agent 0.7.0+). */
    val runtimes: List<HostRuntime> = emptyList(),
    /** MCP servers in the machine's own catalog, by name (agent 0.7.0+). */
    val mcp: List<String> = emptyList(),
    /** When its agent last joined or left, as the server recorded it; null when it never did. */
    val lastSeenAt: String? = null,
    /** What a plain spawn may ask this machine for (host agent 0.13+); null from an older agent. */
    val spawn: SpawnChoices? = null,
) {
    companion object {
        fun from(o: JsonObject) = Host(
            name = o.requireStr("name"),
            online = o.flag("online"),
            seats = o.int("seats") ?: 0,
            roots = o.array("roots").strings(),
            remoteRoots = o.flag("remote_roots"),
            version = o.str("version"),
            modes = o.array("modes").strings(),
            runtimes = o.array("runtimes").objects().mapNotNull { r -> r.str("name")?.let { HostRuntime(it, r.str("version")) } },
            mcp = o.array("mcp").strings(),
            lastSeenAt = o.str("last_seen_at"),
            spawn = o.obj("spawn")?.let {
                SpawnChoices(it.array("options").strings(), it.flag("pool_only"), it.flag("pooled"), it.str("runtime"), it.str("version"))
            },
        )
    }
}

/**
 * What a plain spawn may ask a machine's agent for: [options] it takes
 * ("model", "pooled"); [poolOnly] when it has no Claude login of its own, so
 * its seats always use the server's pool; [pooled], its own default.
 */
data class SpawnChoices(
    val options: List<String>,
    val poolOnly: Boolean,
    val pooled: Boolean,
    /** The runtime a plain spawn runs, and its Claude Code version ("2.1.296"), when the agent says. */
    val runtime: String? = null,
    val version: String? = null,
) {
    val offersModel: Boolean get() = "model" in options
    val offersPool: Boolean get() = "pooled" in options
}

/** A runtime name in a machine's agent config, with the Claude Code version it runs. */
data class HostRuntime(val name: String, val version: String?)

/** A folder past seats on a host worked in, newest first. */
data class RecentDir(val path: String, val lastUsedAt: String?, val seatsRunning: Int) {
    companion object {
        fun from(o: JsonObject) = RecentDir(o.requireStr("path"), o.str("last_used_at"), o.int("seats_running") ?: 0)
    }
}

/** One folder's subfolders on a host. [parent] is null at a root (or at the list of roots, path ""). */
data class DirListing(val path: String, val parent: String?, val dirs: List<String>, val truncated: Boolean) {
    companion object {
        fun from(o: JsonObject) = DirListing(
            path = o.text("path"),
            parent = o.str("parent"),
            dirs = o.array("dirs").strings(),
            truncated = o.flag("truncated"),
        )
    }
}

/** A file uploaded for a seat to fetch. */
data class UploadedFile(val id: String, val name: String, val size: Long, val url: String)

/** [description] is `about` in Swift, where `description` is every object's own. */
@OptIn(ExperimentalObjCName::class)
data class ForumChannel(val slug: String, val title: String?, @property:ObjCName("about") val description: String?) {
    companion object {
        fun from(o: JsonObject) = ForumChannel(o.requireStr("slug"), o.str("title"), o.str("description"))
    }
}

data class ForumPost(
    val id: Long,
    val channel: String?,
    val author: String?,
    val title: String?,
    val body: String,
    val status: String?,
    val owner: String?,
    val parentId: Long?,
    val threadRootId: Long?,
    val pinned: Boolean,
    val insertedAt: String?,
) {
    companion object {
        fun from(o: JsonObject) = ForumPost(
            id = o.requireLong("id"),
            channel = o.str("channel"),
            author = o.str("author"),
            title = o.str("title"),
            body = o.text("body"),
            status = o.str("status"),
            owner = o.str("owner"),
            parentId = o.long("parent_id"),
            threadRootId = o.long("thread_root_id"),
            pinned = o.flag("pinned"),
            insertedAt = o.str("inserted_at"),
        )
    }
}

/** A post and every reply in its thread. */
data class ForumThread(val root: ForumPost, val replies: List<ForumPost>)

data class FleetTask(
    val id: Long,
    val title: String,
    val state: String,
    val priority: Int?,
    val fleet: String?,
    val lane: String?,
    val claimedBy: String?,
    val createdBy: String?,
    val body: String?,
    val insertedAt: String?,
    val completedAt: String?,
) {
    companion object {
        fun from(o: JsonObject) = FleetTask(
            id = o.requireLong("id"),
            title = o.text("title"),
            state = o.text("state"),
            priority = o.int("priority"),
            fleet = o.str("fleet"),
            lane = o.str("lane"),
            claimedBy = o.str("claimed_by"),
            createdBy = o.str("created_by"),
            body = o.str("body_md"),
            insertedAt = o.str("inserted_at"),
            completedAt = o.str("completed_at"),
        )
    }
}

data class UsageLimit(val kind: String, val percent: Int?, val resetsAt: String?, val severity: String?, val active: Boolean)

data class AnthropicAccount(
    val id: Long,
    val label: String,
    val priority: Int?,
    val status: String,
    val cooldownUntil: String?,
    val cooldownReason: String?,
    val tokenValid: Boolean?,
    val usageCheckedAt: String?,
    val limits: List<UsageLimit>,
    /** The account the pool would hand out now, when the server says (it may differ from the preferred one: that one can be cooling). */
    val inUse: Boolean? = null,
    /** The Anthropic account's own email, as its sign-in reported it. */
    val email: String? = null,
    /** Why it needs re-auth ("refresh rejected with HTTP 400"), while it does. */
    val reauthReason: String? = null,
) {
    /** The server can no longer refresh its token: it is out of the pool until someone signs in to it again. */
    val needsReauth: Boolean get() = status == "needs_reauth"

    companion object {
        fun from(o: JsonObject): AnthropicAccount {
            val limits = o.obj("usage")?.array("limits").objects().map {
                UsageLimit(
                    kind = it.text("kind"),
                    percent = it.int("percent"),
                    resetsAt = it.str("resets_at"),
                    severity = it.str("severity"),
                    active = it.flag("is_active"),
                )
            }
            return AnthropicAccount(
                id = o.requireLong("id"),
                label = o.text("label"),
                priority = o.int("priority"),
                status = o.text("status"),
                cooldownUntil = o.str("cooldown_until"),
                cooldownReason = o.str("cooldown_reason"),
                tokenValid = o.bool("token_valid"),
                usageCheckedAt = o.str("usage_checked_at"),
                limits = limits,
                inUse = o.bool("in_use"),
                email = o.str("account_email"),
                reauthReason = o.str("reauth_reason"),
            )
        }
    }
}

data class SeatAssignment(val title: String, val label: String?, val via: String?, val assignedAt: String?)

/**
 * The Anthropic account pool. The lowest [AnthropicAccount.priority] is the
 * preferred account: the pool's first choice, skipped while it is cooling
 * down or needs re-auth. [currentId] is the account it would hand out now,
 * when the server says so.
 */
data class Pool(val accounts: List<AnthropicAccount>, val seats: List<SeatAssignment>, val currentId: Long? = null) {
    /** Preferred first, then by priority; an account with none comes last. */
    val ordered: List<AnthropicAccount> get() = accounts.sortedWith(compareBy(nullsLast()) { it.priority })

    val preferredId: Long? get() = accounts.filter { it.priority != null }.minByOrNull { it.priority!! }?.id

    /** The account in use now, from current_id or an account's in_use; null when the server does not say. */
    val inUseId: Long? get() = currentId ?: accounts.firstOrNull { it.inUse == true }?.id

    /** This pool with the order a preference change answered with; who used what stays. */
    fun withOrder(order: PoolOrder) = copy(accounts = order.accounts, currentId = order.currentId ?: currentId)
}

/**
 * A sign-in to an Anthropic account, begun: [url] is the page to open, and
 * [verifier] and [state] go back with the code that page shows (PKCE; the
 * server holds nothing between the two calls).
 */
data class AccountSignIn(val url: String, val verifier: String, val state: String)

/** How re-authorizing an account ended. */
sealed interface Reauthorization {
    /** Its credentials are replaced in place: same account, label and priority. [account] when the server returned it. */
    data class Done(val account: AnthropicAccount?) : Reauthorization

    /**
     * The code signed in to a different Anthropic account than the one stored
     * ([stored] and [signedIn], when the server names them), or one whose
     * email it could not read. Nothing changed, and the code is spent:
     * replacing it anyway takes a fresh sign-in, completed with force.
     */
    data class OtherAccount(val stored: String?, val signedIn: String?, val message: String) : Reauthorization
}

/** What a preference change answers: every account in its new order, and the one in use when the server says. */
data class PoolOrder(val accounts: List<AnthropicAccount>, val currentId: Long?)

data class Incident(val summary: String)

data class Incidents(val total: Int, val since: String?, val latest: List<Incident>)

/** The result of handing a seat a message: the server says delivered, busy or error. */
data class SendResult(val status: String, val hint: String?) {
    val delivered: Boolean get() = status == "delivered"
}

/** Who a token belongs to and what it may do: scope is "api", "app" or "super". */
data class Me(val email: String, val scope: String?)

/** A signed-in session: the token to keep (in the Keychain or the Android keystore) and who it is. */
data class SignIn(val token: String, val scope: String, val email: String)

/**
 * A token on the server, as the token list shows it; its value is never sent.
 * [context] is "api", "app", "super" or "host:<machine>"; [label] the client
 * name it was minted for, null for tokens minted out of band; [current] marks
 * the token making the request.
 */
data class TokenInfo(val id: Long, val context: String, val label: String?, val insertedAt: String?, val current: Boolean) {
    /** The machine a host token belongs to, else null. */
    val host: String? get() = context.removePrefix("host:").takeIf { context.startsWith("host:") }

    companion object {
        fun from(o: JsonObject) = TokenInfo(
            id = o.requireLong("id"),
            context = o.text("context"),
            label = o.str("label"),
            insertedAt = o.str("inserted_at"),
            current = o.flag("current"),
        )
    }
}

/** A host token minted for a new machine's agent config. Shown once: the server never returns it again. */
data class HostToken(val name: String, val token: String)

/**
 * A runner space of a fleet, from cv-runner's heartbeat. [status] is idle,
 * busy, halted or missing (no heartbeat for 75 s), or stopped in a live event
 * when the runner closed cleanly. [seat] and [task] only while busy; every
 * field but slot, fleet and status may be absent.
 */
data class FleetRunner(
    val slot: String,
    val fleet: String,
    val status: String,
    val host: String? = null,
    val channel: String? = null,
    val lane: String? = null,
    val taskSource: String? = null,
    val model: String? = null,
    val seat: String? = null,
    val task: Long? = null,
    /** When the slot entered its current status. */
    val since: String? = null,
    val lastSeen: String? = null,
) {
    companion object {
        fun from(o: JsonObject) = FleetRunner(
            slot = o.requireStr("slot"),
            fleet = o.requireStr("fleet"),
            status = o.str("status") ?: o.str("state") ?: "missing",
            host = o.str("host"),
            channel = o.str("channel"),
            lane = o.str("lane"),
            taskSource = o.str("task_source"),
            model = o.str("model"),
            seat = o.str("seat"),
            task = o.long("task"),
            since = o.str("since"),
            lastSeen = o.str("last_seen"),
        )
    }
}

/** A running seat of a fleet: origin "manual" for members (ipsum-admin, lorem-admin), "runner" for workers. */
data class FleetSeat(val id: Long, val title: String, val origin: String?, val host: String?) {
    companion object {
        fun from(o: JsonObject) = FleetSeat(o.requireLong("id"), o.text("title"), o.str("origin"), o.str("host"))
    }
}

/**
 * A fleet's declared member: a profile with this fleet. [id] is the profile's;
 * [status] as [Profile.status]; [seat] the running seat's row, or null.
 */
data class FleetMember(val id: Long, val name: String, val role: String, val machine: String?, val status: String, val seat: Long?) {
    companion object {
        fun from(o: JsonObject) = FleetMember(o.requireLong("id"), o.requireStr("name"), o.str("role") ?: "member", o.str("machine"), o.str("status") ?: "stopped", o.long("seat"))

        fun of(p: Profile) = FleetMember(p.id, p.name, p.fields.role, p.fields.machine, p.status, p.seat)
    }
}

data class Fleet(val fleet: String, val runners: List<FleetRunner>, val seats: List<FleetSeat>, val members: List<FleetMember> = emptyList()) {
    companion object {
        fun from(o: JsonObject) = Fleet(
            fleet = o.requireStr("fleet"),
            runners = o.array("runners").objects().mapNotNull { runCatching { FleetRunner.from(it) }.getOrNull() },
            seats = o.array("seats").objects().mapNotNull { runCatching { FleetSeat.from(it) }.getOrNull() },
            members = o.array("members").objects().mapNotNull { runCatching { FleetMember.from(it) }.getOrNull() },
        )
    }
}
