package dev.claudiverse.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adopting a seat someone started by hand: its machine's agent finds the
 * Claude Code process running it (by Claude Code's own record of the
 * process, ~/.claude/sessions/<pid>.json), stops exactly that process, and
 * starts the same conversation again from a profile, under the agent, so
 * the app can stop, start and restart it from then on.
 *
 * The process the agent found: [matchedBy] is "session_id" (the record's
 * conversation is the seat's) or "tmux_title" (only its tmux session name
 * and folder match: the operator confirms it). [tmux] is null for a process
 * in a plain terminal, which gets its shell back when the process stops.
 * [status] is idle or busy (mid-turn).
 */
data class AdoptProcess(
    val pid: Long,
    val procStart: String,
    val tmux: String?,
    val status: String,
    val version: String?,
    val matchedBy: String,
    val sessionId: String?,
) {
    val isBusy: Boolean get() = status == "busy"
    val byTmuxName: Boolean get() = matchedBy == "tmux_title"

    companion object {
        fun from(o: JsonObject) = AdoptProcess(
            pid = o.requireLong("pid"),
            procStart = o.requireStr("proc_start"),
            tmux = o.str("tmux"),
            status = o.str("status") ?: "unknown",
            version = o.str("version"),
            matchedBy = o.str("matched_by") ?: "session_id",
            sessionId = o.str("session_id"),
        )
    }
}

/**
 * What adopting a seat would do, from GET /api/sessions/:id/adopt_preview:
 * the process to stop, the profile to start it from ([existing], a stopped
 * profile with the seat's title on its machine, or [suggested] fields for a
 * new one named after the seat), or why it cannot be adopted ([blocked]: no
 * agent or too old, a runner's seat, no process or several, a transcript the
 * profile's runtime cannot read, …). [folder] is the folder of the seat's
 * process when blocked because it is outside the machine's allowed folders:
 * a seat started by hand has none in its row, so the app learns it here.
 */
data class AdoptPreview(
    val process: AdoptProcess?,
    val suggested: ProfileFields?,
    val existing: Profile?,
    val blocked: String?,
    val folder: String? = null,
) {
    val canAdopt: Boolean get() = blocked == null && process != null && (existing != null || suggested != null) && existing?.canStop != true

    companion object {
        fun from(o: JsonObject) = AdoptPreview(
            process = o.obj("process")?.let(AdoptProcess::from),
            suggested = o.obj("suggested")?.let(ProfileFields::from),
            existing = o.obj("existing_profile")?.let { runCatching { Profile.from(it) }.getOrNull() },
            blocked = o.str("blocked"),
            folder = o.str("folder"),
        )
    }
}

/**
 * Why a seat cannot be adopted, as far as its row tells; null when the
 * server should be asked (it knows the agent, the process and any profile).
 * A seat whose machine is not recorded is asked about too: the app looks
 * for it on the machine it runs on ([adoptHost]).
 */
fun adoptBlockedReason(seat: Seat): String? = when {
    seat.status != "running" -> "It is stopped: make a profile for it and press Start instead."
    seat.isRunner -> "A runner's worker: cv-runner starts and stops it."
    seat.origin == "profile" -> "It already runs from a profile."
    else -> null
}

/**
 * The machine to look for a seat's process on: its recorded one, else
 * [thisMachine] (the one the app runs on, where a seat started by hand with
 * no machine reported most likely runs). Null when neither is known.
 */
fun adoptHost(seat: Seat, thisMachine: String?): String? = seat.host ?: thisMachine?.takeIf { it.isNotBlank() }

/** The body of POST /api/sessions/:id/adopt: the profile's fields, the process to stop, an existing profile to use, and the machine when the row has none. */
internal fun adoptBody(fields: ProfileFields, process: AdoptProcess, profileId: Long?, force: Boolean, host: String? = null): JsonObject =
    JsonObject(
        fields.toJson() + buildMap {
            host?.let { put("host", JsonPrimitive(it)) }
            put("pid", JsonPrimitive(process.pid))
            put("proc_start", JsonPrimitive(process.procStart))
            profileId?.let { put("profile_id", JsonPrimitive(it)) }
            if (force) put("force", JsonPrimitive(true))
        },
    )
