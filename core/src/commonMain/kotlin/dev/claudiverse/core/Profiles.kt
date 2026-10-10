package dev.claudiverse.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a profile's editor changes: every field but the name, which is fixed
 * for life. [runtime], [permissionMode] and [mcp] are names the profile's
 * machine maps to its own binaries, modes and MCP catalog ([Host.runtimes],
 * [Host.modes], [Host.mcp]); [resume] is "latest", "new" or a conversation id.
 */
data class ProfileFields(
    val machine: String,
    val folder: String,
    val fleet: String? = null,
    val role: String = "member",
    val canNotify: Boolean = false,
    val runtime: String = "stock+mod",
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: String = "default",
    val mcp: List<String> = emptyList(),
    /** Pooled login with the proxy's account failover: on by default (server ddd6eeb). Never on the patched runtime, which fails over itself. */
    val pooled: Boolean = true,
    val autostart: Boolean = false,
    val resume: String = "latest",
    val debug: Boolean = false,
) {
    /** The fields as the server takes them; null clears an optional one. */
    fun toJson(): JsonObject = buildJsonObject {
        put("machine", machine)
        put("folder", folder)
        put("fleet", fleet)
        put("role", role)
        put("can_notify", canNotify)
        put("runtime", runtime)
        put("model", model)
        put("effort", effort)
        put("permission_mode", permissionMode)
        put("mcp", JsonArray(mcp.map(::JsonPrimitive)))
        put("pooled", pooled)
        put("autostart", autostart)
        put("resume", resume)
        put("debug", debug)
    }

    companion object {
        val ROLES = listOf("member", "watcher", "orchestrator")
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        fun from(o: JsonObject) = ProfileFields(
            machine = o.text("machine"),
            folder = o.text("folder"),
            fleet = o.str("fleet"),
            role = o.str("role") ?: "member",
            canNotify = o.flag("can_notify"),
            runtime = o.str("runtime") ?: "stock+mod",
            model = o.str("model"),
            effort = o.str("effort"),
            permissionMode = o.str("permission_mode") ?: "default",
            mcp = o.array("mcp").strings(),
            pooled = o.bool("pooled") ?: true,
            autostart = o.flag("autostart"),
            resume = o.str("resume") ?: "latest",
            debug = o.flag("debug"),
        )

        /**
         * Three-way merge after a 409: the fields the operator changed from
         * [base] (what they read) keep their edit, the rest take [theirs] (the
         * server's current profile). A field both changed, to different values,
         * is a conflict: it keeps the operator's edit and is named, for them to
         * decide.
         */
        fun merge(base: ProfileFields, mine: ProfileFields, theirs: ProfileFields): ProfileMerge {
            val b = base.toJson()
            val m = mine.toJson()
            val t = theirs.toJson()
            val conflicts = mutableListOf<String>()
            val merged = buildJsonObject {
                for (key in m.keys) {
                    val mineChanged = m[key] != b[key]
                    val theirsChanged = t[key] != b[key]
                    if (mineChanged && theirsChanged && m[key] != t[key]) conflicts += key
                    put(key, if (mineChanged) m.getValue(key) else t[key] ?: JsonNull)
                }
            }
            return ProfileMerge(from(merged), conflicts)
        }
    }
}

/** A [ProfileFields.merge]: the merged fields, and the server field names both sides changed. */
data class ProfileMerge(val fields: ProfileFields, val conflicts: List<String>)

/**
 * The newest start of a profile's seat: a fact about that start, so it stays
 * "started" after the seat stops. [outcome] is starting, started, refused,
 * failed or offline; [at] is when the outcome was recorded, [requestedAt]
 * when the start was asked for (servers before 22d83f1 send no requested_at:
 * then it is [at]), [stoppedAt] when a stop ended it (server 6a2f344+).
 */
data class ProfileStart(
    val at: String?,
    val outcome: String,
    val reason: String?,
    val seat: String?,
    val requestedAt: String? = at,
    val stoppedAt: String? = null,
) {
    companion object {
        fun from(o: JsonObject) = ProfileStart(
            o.str("at"), o.str("outcome") ?: "unknown", o.str("reason"), o.str("seat"),
            o.str("requested_at") ?: o.str("at"), o.str("stopped_at"),
        )
    }
}

/**
 * A seat profile, as listed: its settings, and what the server derives from
 * the seats started from it. [status] is running, starting, unregistered (its
 * machine started the seat but it never registered, e.g. it sits at a prompt
 * in tmux), offline (its machine), failed (the last start) or stopped; [seat]
 * is the running seat's row; [restartNeeded] means that seat started from an
 * older [revision].
 */
data class Profile(
    val id: Long,
    val name: String,
    val revision: Long,
    val fields: ProfileFields,
    val lastStart: ProfileStart?,
    val status: String,
    val seat: Long?,
    val restartNeeded: Boolean,
) {
    val isRunning: Boolean get() = status == "running"

    /** A seat is up on its machine, registered or not: Stop applies, Start and Delete do not. */
    val canStop: Boolean get() = status == "running" || status == "unregistered"

    companion object {
        fun from(o: JsonObject) = Profile(
            id = o.requireLong("id"),
            name = o.requireStr("name"),
            revision = o.long("revision") ?: 1,
            fields = ProfileFields.from(o),
            lastStart = o.obj("last_start")?.let(ProfileStart::from),
            status = o.str("status") ?: "stopped",
            seat = o.long("seat"),
            restartNeeded = o.flag("restart_needed"),
        )
    }
}

/** A profile create or edit: saved, refused field by field (422), or overtaken by another edit (409). */
sealed interface ProfileSave {
    data class Saved(val profile: Profile) : ProfileSave
    /** Server field names (e.g. "folder") to their messages. */
    data class Invalid(val errors: Map<String, List<String>>) : ProfileSave
    /** The profile changed since it was read: [current] is what the server has now. */
    data class Conflict(val current: Profile) : ProfileSave
}

/** The answer to [Api.renameProfile]. */
sealed interface ProfileRename {
    /**
     * Renamed. [restarting]: its seat was running, and is being started again
     * under the new name. [moved]: what the server moved to the new name, by
     * kind (past seats, watch lists, handoffs, tasks, …), as counts.
     */
    data class Renamed(val profile: Profile, val restarting: Boolean, val moved: Map<String, Int>) : ProfileRename
    /** Not renamed, for [code]: name_taken, busy (mid-turn) or claimed_task. */
    data class Refused(val code: String, val message: String) : ProfileRename
    /** The profile changed since it was read: [current] is what the server has now. */
    data class Conflict(val current: Profile) : ProfileRename
    /** Server field names (e.g. "name") to their messages. */
    data class Invalid(val errors: Map<String, List<String>>) : ProfileRename
}

/** Why a rename was refused, for the operator, from [ProfileRename.Refused]. */
fun renameRefusal(r: ProfileRename.Refused): String = when (r.code) {
    "name_taken" -> "That name is taken: a profile or a running seat already has it."
    "busy" -> "The seat is mid-turn: rename it once it is idle."
    "claimed_task" -> "The seat holds a task, which its runner tracks by name: rename it once the task is done."
    else -> r.message.ifBlank { r.code }
}

/** The server's `moved` kinds, as one and as several. */
private val MOVED_KINDS = mapOf(
    "past_seats" to ("past seat" to "past seats"),
    "watch_lists" to ("watch list" to "watch lists"),
    "queued_pings" to ("queued ping" to "queued pings"),
    "watchers" to ("seat watching it" to "seats watching it"),
    "forum_subscriptions" to ("forum subscription" to "forum subscriptions"),
    "handoffs" to ("handoff" to "handoffs"),
    "handoff_versions" to ("handoff version" to "handoff versions"),
    "open_tasks" to ("open task" to "open tasks"),
)

/**
 * "3 past seats, 1 handoff" from [ProfileRename.Renamed.moved]: the non-zero
 * counts, in the server's order. Handoff versions are history detail, left
 * out when the handoff itself is counted.
 */
fun renameMoved(moved: Map<String, Int>): String =
    moved.filter { (kind, n) -> n > 0 && !(kind == "handoff_versions" && (moved["handoffs"] ?: 0) > 0) }
        .entries.joinToString(", ") { (kind, n) ->
            val (one, many) = MOVED_KINDS[kind] ?: kind.replace('_', ' ').let { it to it }
            "$n ${if (n == 1) one else many}"
        }

/**
 * The profiles, kept current while something shows them: read from
 * GET /api/profiles on every (re)join of operator:events and every
 * [RECHECK_MS], and changed in between by each live "profile" event.
 */
class Profiles(private val api: Api, private val socket: PhoenixSocket, private val scope: CoroutineScope) {

    private val state = MutableStateFlow<List<Profile>>(emptyList())
    val all: StateFlow<List<Profile>> = state.asStateFlow()

    private val lock = SynchronizedObject()
    private var jobs: List<Job> = emptyList()

    fun start() = synchronized(lock) {
        if (jobs.isNotEmpty()) return@synchronized
        val ch = socket.channel(Fleets.TOPIC)
        jobs = listOf(
            scope.launch {
                ch.events.collect { e -> if (e.event == "profile") state.update { apply(it, e.payload) } }
            },
            scope.launch { ch.joined.collect { if (it) refresh() } },
            scope.launch {
                while (isActive) {
                    refresh()
                    delay(RECHECK_MS)
                }
            },
        )
        ch.join()
    }

    fun stop() = synchronized(lock) {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    @Throws(CancellationException::class)
    suspend fun refresh() {
        try {
            state.value = api.profiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CoreLog.info("profiles not read: ${e.message}")
        }
    }

    /** A profile the caller just saved or started: shown at once, not on the event that follows. */
    fun put(profile: Profile) = state.update { upsert(it, profile) }

    internal companion object {
        const val RECHECK_MS = 60_000L

        /** One live "profile" event: {op, profile}; "deleted" carries only the profile's id and name. */
        fun apply(profiles: List<Profile>, event: JsonObject): List<Profile> {
            val p = event.obj("profile") ?: return profiles
            if (event.str("op") == "deleted") {
                val id = p.long("id") ?: return profiles
                return profiles.filter { it.id != id }
            }
            val profile = runCatching { Profile.from(p) }.getOrNull() ?: return profiles
            return upsert(profiles, profile)
        }

        /**
         * [profile] in place of the one with its id, or added. Never replaced by
         * an older revision: an event can arrive after the reply to the edit
         * that came later.
         */
        fun upsert(profiles: List<Profile>, profile: Profile): List<Profile> {
            val existing = profiles.firstOrNull { it.id == profile.id }
            if (existing != null && existing.revision > profile.revision) return profiles
            return (profiles.filter { it.id != profile.id } + profile).sortedBy { it.name.lowercase() }
        }
    }
}

/** Profiles of one fleet ([fleet] null: those in none), for a list grouped as the seat list is. */
data class ProfileGroup(val fleet: String?, val profiles: List<Profile>) {
    val running: Int get() = profiles.count { it.status == "running" }
}

/** [profiles] by fleet: fleets in name order, profiles with no fleet last, each group keeping the given order. */
fun profileGroups(profiles: List<Profile>): List<ProfileGroup> =
    profiles.groupBy { it.fields.fleet?.takeIf { f -> f.isNotBlank() } }.entries
        .sortedWith(compareBy({ it.key == null }, { it.key?.lowercase() }))
        .map { ProfileGroup(it.key, it.value) }
