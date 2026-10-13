package dev.claudiverse.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What restarting a seat would do, as the server plans it: restart it in its
 * tmux window on the machine's current Claude Code, adopt it into a tmux seat
 * when it runs outside tmux, or skip it, saying why ([reason]: busy, the
 * caller itself, already current…). [argvNames] and [envNames] are the names
 * of the arguments and environment a restart carries over, not their values.
 */
data class RestartPlan(
    val sessionId: Long,
    val title: String,
    val host: String?,
    val fleet: String? = null,
    /** "restart", "adopt" or "skip"; anything else a newer server sends reads as "skip". */
    val action: String,
    /** Why it is skipped, a sentence for people; null when it is not. */
    val reason: String?,
    /** The skip's code: busy, background, question, permission, recent_activity, runner, fleet, self, not_running, no_machine, offline, up_to_date, not_found, refused. */
    val reasonCode: String? = null,
    val clientVersion: String?,
    val targetVersion: String?,
    /** From a dry run the machine looked at: the argument and environment names a restart carries (never values). */
    val argvNames: List<String> = emptyList(),
    val envNames: List<String> = emptyList(),
    /** Marked protected on the server: a batch leaves it out unless named, and one restart needs confirmProtected. */
    val isProtected: Boolean = false,
) {
    val skipped: Boolean get() = action == SKIP

    companion object {
        const val RESTART = "restart"
        const val ADOPT = "adopt"
        const val SKIP = "skip"

        fun from(o: JsonObject): RestartPlan {
            val action = o.str("action")
            val known = action in setOf(RESTART, ADOPT, SKIP)
            val launch = o.obj("launch")
            return RestartPlan(
                sessionId = o.requireLong("session_id"),
                title = o.text("title"),
                host = o.str("host"),
                fleet = o.str("fleet"),
                action = if (known) action!! else SKIP,
                reason = o.str("reason") ?: if (!known) "this app does not know the action \"$action\"" else null,
                reasonCode = o.str("reason_code"),
                clientVersion = o.str("client_version"),
                targetVersion = o.str("target_version"),
                argvNames = launch?.array("argv").strings(),
                envNames = launch?.array("env").strings(),
                isProtected = o.flag("protected"),
            )
        }
    }
}

/**
 * Which idle seats a batch restart takes. Defaults as both apps offer them:
 * only outdated seats, paused fleets left out, never the caller's own.
 * [sessionIds] is exactly the set to restart (a protected seat only when
 * named here); [excludeSessionIds] leaves seats out.
 */
data class RestartOptions(
    val fleets: List<String>? = null,
    val excludeFleets: List<String>? = null,
    val onlyOutdated: Boolean = true,
    val includeSelf: Boolean = false,
    val caller: String? = null,
    val sessionIds: List<Long>? = null,
    val excludeSessionIds: List<Long>? = null,
    val excludePaused: Boolean = true,
)

/** A single seat's restart the server refused (409): [message] is its sentence, for people; [reasonCode] as in [RestartPlan]. */
class RestartRefused(message: String, val reasonCode: String?) : Exception(message)

/** A batch restart the server accepted: it restarts [seats] one at a time. */
data class RestartBatchStarted(val batchId: String, val seats: List<RestartPlan>)

/** One seat's progress in a restart: [outcome] is started, restarted, adopted, skipped or failed. */
data class SeatRestartEvent(
    val sessionId: Long,
    val title: String?,
    val host: String?,
    val outcome: String,
    val reason: String?,
    val reasonCode: String? = null,
    val oldVersion: String?,
    val newVersion: String?,
    /** The row the restarted seat registered (a mod seat usually keeps its old one); null if none within the wait. */
    val newSessionId: Long? = null,
    /** The old process had to be killed rather than ending on its own. */
    val killed: Boolean,
    val note: String?,
    val restartId: String?,
    val batchId: String?,
) {
    /** Done, one way or the other: no later event for this seat in this restart. */
    val finished: Boolean get() = outcome in FINISHED

    companion object {
        val FINISHED = setOf("restarted", "adopted", "skipped", "failed")

        fun from(o: JsonObject) = SeatRestartEvent(
            sessionId = o.requireLong("session_id"),
            title = o.str("title"),
            host = o.str("host"),
            outcome = o.str("outcome") ?: "unknown",
            reason = o.str("reason"),
            reasonCode = o.str("reason_code"),
            oldVersion = o.str("old_version"),
            newVersion = o.str("new_version"),
            newSessionId = o.long("new_session_id"),
            killed = o.flag("killed"),
            note = o.str("note"),
            restartId = o.str("restart_id"),
            batchId = o.str("batch_id"),
        )
    }
}

/** A batch's own state ([state]: started or done) with [total] seats and its counts by outcome (restarted, adopted, skipped, failed). */
data class RestartBatchEvent(val batchId: String, val host: String?, val state: String, val total: Int?, val counts: Map<String, Int>) {
    val done: Boolean get() = state == "done"

    companion object {
        fun from(o: JsonObject): RestartBatchEvent {
            val counts = o.obj("counts")
            return RestartBatchEvent(
                batchId = o.requireStr("batch_id"),
                host = o.str("host"),
                state = o.str("state") ?: "unknown",
                total = o.int("total"),
                counts = counts?.keys?.mapNotNull { k -> counts.int(k)?.let { k to it } }?.toMap().orEmpty(),
            )
        }
    }
}

/** One planned seat with what has happened to it so far: [event] is null until the first arrives. */
data class RestartRow(val plan: RestartPlan, val event: SeatRestartEvent?) {
    /** Where it stands: skipped from the plan, its latest outcome, or waiting its turn. */
    val status: String get() = event?.outcome ?: if (plan.skipped) "skipped" else "waiting"
}

/**
 * A batch's seats in plan order, each with its latest event in that batch
 * ([batchId]): the progress list a sheet shows. Events for other batches or
 * seats are ignored, and a seat's later event replaces an earlier one.
 */
fun foldRestart(plan: List<RestartPlan>, events: List<SeatRestartEvent>, batchId: String?): List<RestartRow> {
    val latest = events.filter { batchId == null || it.batchId == batchId }.associateBy { it.sessionId }
    return plan.map { RestartRow(it, latest[it.sessionId]) }
}

/** The body of a batch restart; dry run asks for the plan only. */
internal fun restartBody(o: RestartOptions, dryRun: Boolean): JsonObject = buildJsonObject {
    if (dryRun) put("dry_run", true)
    o.fleets?.let { f -> put("fleets", buildJsonArray { f.forEach { add(JsonPrimitive(it)) } }) }
    o.excludeFleets?.let { f -> put("exclude_fleets", buildJsonArray { f.forEach { add(JsonPrimitive(it)) } }) }
    o.sessionIds?.let { ids -> put("session_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) }
    o.excludeSessionIds?.let { ids -> put("exclude_session_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) }
    if (o.excludePaused) put("exclude_paused", true)
    put("only_outdated", o.onlyOutdated)
    if (o.includeSelf) put("include_self", true)
    o.caller?.let { put("caller", it) }
}

/**
 * Restarts as they happen, from operator:events: every "seat_restart" event
 * in arrival order, and each batch's latest "restart_batch", while started.
 * Holds its channel claim and gives it back on stop.
 */
class Restarts(private val socket: PhoenixSocket, private val scope: CoroutineScope) {

    private val eventsState = MutableStateFlow<List<SeatRestartEvent>>(emptyList())
    /** Seat events since start, oldest first: fold them with [foldRestart]. */
    val events: StateFlow<List<SeatRestartEvent>> = eventsState.asStateFlow()

    private val batchesState = MutableStateFlow<Map<String, RestartBatchEvent>>(emptyMap())
    /** Each batch's latest state, by batch id. */
    val batches: StateFlow<Map<String, RestartBatchEvent>> = batchesState.asStateFlow()

    private val lock = SynchronizedObject()
    private var job: Job? = null
    private var channel: PhoenixSocket.Channel? = null

    fun start() = synchronized(lock) {
        if (job != null) return@synchronized
        val ch = socket.channel(Fleets.TOPIC).also { channel = it }
        job = scope.launch { ch.events.collect { e -> onEvent(e.event, e.payload) } }
        ch.join()
    }

    fun stop() = synchronized(lock) {
        job?.cancel()
        job = null
        channel?.leave()
        channel = null
    }

    internal fun onEvent(event: String, payload: JsonObject) {
        when (event) {
            "seat_restart" -> runCatching { SeatRestartEvent.from(payload) }.getOrNull()?.let { e ->
                eventsState.update { (it + e).takeLast(MAX_EVENTS) }
            }
            "restart_batch" -> runCatching { RestartBatchEvent.from(payload) }.getOrNull()?.let { b ->
                batchesState.update { it + (b.batchId to b) }
            }
        }
    }

    private companion object {
        const val MAX_EVENTS = 500
    }
}
