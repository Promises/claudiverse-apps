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

/**
 * A machine's safe reboot (host agent 0.23+); null from an older agent, or
 * offline. [recoverable]: what an unprepared reboot left, reopened only when
 * asked. [prepared]: what Prepare reboot wrote, reopened by the machine itself
 * after its reboot. [lastReopen]: the last reopen's outcome. [reopening]: a
 * reopen running now.
 */
data class HostReboot(
    val recoverable: RebootBrief?,
    val prepared: RebootBrief?,
    val lastReopen: ReopenReport?,
    val reopening: RebootBrief?,
) {
    companion object {
        fun from(o: JsonObject?): HostReboot? = o?.let {
            HostReboot(
                recoverable = it.obj("recoverable")?.let(RebootBrief::from),
                prepared = it.obj("prepared")?.let(RebootBrief::from),
                lastReopen = it.obj("last_reopen")?.let(ReopenReport::from),
                reopening = it.obj("reopening")?.let(RebootBrief::from),
            )
        }
    }
}

data class RebootSeat(val title: String, val busy: Boolean)

/** A reboot list as a machine reports it: what a Reopen button lists. */
data class RebootBrief(
    /** rolling (kept every minute) or prepared. */
    val kind: String,
    val takenAt: String?,
    /** When the list was last known true. */
    val checkedAt: String?,
    val bootTime: String?,
    val seats: List<RebootSeat>,
    val runners: List<String>,
) {
    val midTurn: List<String> get() = seats.filter { it.busy }.map { it.title }

    companion object {
        fun from(o: JsonObject) = RebootBrief(
            kind = o.str("kind") ?: "rolling",
            takenAt = o.str("taken_at"),
            checkedAt = o.str("checked_at"),
            bootTime = o.str("boot_time"),
            seats = o.array("seats").objects().map { RebootSeat(it.text("title"), it.flag("busy")) },
            runners = o.array("runners").objects().mapNotNull { it.str("slot") },
        )
    }
}

data class RebootExcluded(val title: String, val reason: String)

/** What Prepare reboot wrote down: names and flags only, never a command line or a value. */
data class RebootSummary(
    val brief: RebootBrief,
    val excluded: List<RebootExcluded>,
    /** Runner workers: left to their runners. */
    val workers: Int,
    /** tmux sessions that will not come back (a pi seat, a shell). */
    val others: List<String>,
) {
    companion object {
        fun from(o: JsonObject) = RebootSummary(
            brief = RebootBrief.from(o),
            excluded = o.array("excluded").objects().map { RebootExcluded(it.text("title"), it.text("reason")) },
            workers = o.int("workers") ?: 0,
            others = o.array("others").strings(),
        )
    }
}

/** One seat's or runner's outcome in a reopen: started, skipped or failed. */
data class ReopenOutcome(
    /** The seat's title, or "runner <slot>". */
    val name: String,
    val busy: Boolean,
    val resumed: Boolean,
    val outcome: String,
    val code: String?,
    val reason: String?,
) {
    companion object {
        fun from(o: JsonObject) = ReopenOutcome(
            name = o.str("title") ?: "runner ${o.text("slot")}",
            busy = o.flag("busy"),
            resumed = o.flag("resumed"),
            outcome = o.str("outcome") ?: "unknown",
            code = o.str("code"),
            reason = o.str("reason"),
        )
    }
}

/** A reopen's outcome: a host_reopen event's result, and a machine's last_reopen. */
data class ReopenReport(
    val id: String?,
    /** prepared (the machine did it itself after a prepared reboot) or rolling (asked for). */
    val kind: String,
    val takenAt: String?,
    val reopenedAt: String?,
    val outcomes: List<ReopenOutcome>,
    val seatCounts: Map<String, Int>,
    val runnerCounts: Map<String, Int>,
) {
    companion object {
        fun from(o: JsonObject): ReopenReport {
            val counts = o.obj("counts")
            fun count(key: String): Map<String, Int> {
                val c = counts?.obj(key) ?: return emptyMap()
                return c.keys.mapNotNull { k -> c.int(k)?.let { k to it } }.toMap()
            }
            return ReopenReport(
                id = o.str("id"),
                kind = o.str("kind") ?: "rolling",
                takenAt = o.str("taken_at"),
                reopenedAt = o.str("reopened_at"),
                outcomes = o.array("seats").objects().map(ReopenOutcome::from) + o.array("runners").objects().map(ReopenOutcome::from),
                seatCounts = count("seats"),
                runnerCounts = count("runners"),
            )
        }
    }
}

/** A reopen finished: [ok] with its [result], or not, with [error]. [reopenId] is null for one the machine did itself. */
data class ReopenDone(val reopenId: String?, val host: String, val ok: Boolean, val error: String?, val result: ReopenReport?) {
    companion object {
        fun from(o: JsonObject) = ReopenDone(
            reopenId = o.str("reopen_id"),
            host = o.text("host"),
            ok = o.flag("ok"),
            error = o.str("error"),
            result = o.obj("result")?.let(ReopenReport::from),
        )
    }
}

/** reopen_seats answered 409: a reopen of this machine is running already. */
class AlreadyReopening(message: String) : Exception(message)

/** reopen_seats answered 404: the machine offers nothing to reopen. */
class NothingToReopen(message: String) : Exception(message)

/**
 * The words both apps use for a safe reboot. [whenLabel] is the app's own
 * rendering of a time ("4 min ago"), so each platform keeps its format.
 */
object RebootWording {
    fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"

    /** "Ready for a reboot: 5 seats (mid-turn: a, b), 2 runners, written down 4 min ago. After the reboot m5 reopens them by itself." */
    fun prepared(host: String, brief: RebootBrief, whenLabel: String): String {
        val mid = brief.midTurn.takeIf { it.isNotEmpty() }?.let { " (mid-turn: ${it.joinToString(", ")})" }.orEmpty()
        return "Ready for a reboot: ${plural(brief.seats.size, "seat")}$mid, ${plural(brief.runners.size, "runner")}, " +
            "written down $whenLabel. After the reboot $host reopens them by itself."
    }

    /** What Prepare reboot left out, line by line: the others, the workers, the excluded with reasons; empty when nothing. */
    fun preparedNotes(s: RebootSummary): List<String> = listOfNotNull(
        s.others.takeIf { it.isNotEmpty() }?.let { "Not coming back (no Claude Code seat in them): ${it.joinToString(", ")}." },
        s.workers.takeIf { it > 0 }?.let { "${plural(it, "runner worker")} left to their runners." },
        s.excluded.takeIf { it.isNotEmpty() }?.let { x -> "Left out: ${x.joinToString("; ") { "${it.title} (${it.reason})" }}." },
    )

    /** The Reopen button: "Reopen seats (5)". */
    fun reopenButton(brief: RebootBrief) = "Reopen seats (${brief.seats.size})"

    /** The confirm's text; the seats and runners themselves are listed under it. */
    fun reopenConfirm(host: String, brief: RebootBrief, whenLabel: String): String {
        val busy = brief.midTurn.size
        val mid = when (busy) {
            0 -> ""
            1 -> " The seat that was mid-turn gets one \"continue\" once back."
            else -> " The $busy seats that were mid-turn get one \"continue\" once back."
        }
        return "$host rebooted without Prepare reboot. As of $whenLabel it ran these. Reopen them, each seat on its own conversation?$mid"
    }

    /** The confirm's button: "Reopen 5 seats and 2 runners". */
    fun reopenAction(brief: RebootBrief) = "Reopen ${plural(brief.seats.size, "seat")} and ${plural(brief.runners.size, "runner")}"

    /** "38 started, 2 skipped", zeros left out; "nothing" when all are. */
    fun counts(c: Map<String, Int>): String =
        listOf("started", "skipped", "failed").filter { (c[it] ?: 0) > 0 }.joinToString(", ") { "${c[it]} $it" }.ifEmpty { "nothing" }

    /** "Reopened: seats 5 started; runners 2 started." */
    fun reopened(r: ReopenReport) = "Reopened: seats ${counts(r.seatCounts)}; runners ${counts(r.runnerCounts)}."

    /** "Reopened 4 min ago (after a prepared reboot): seats …; runners …" */
    fun lastReopen(r: ReopenReport, whenLabel: String) =
        "Reopened $whenLabel (${if (r.kind == "prepared") "after a prepared reboot" else "when asked"}): " +
            "seats ${counts(r.seatCounts)}; runners ${counts(r.runnerCounts)}"

    /** One outcome: "pe-ceo started (mid-turn: it gets one continue)", "ps2-w3 skipped: busy". */
    fun outcome(o: ReopenOutcome): String =
        "${o.name} ${o.outcome}" +
            (if (o.outcome == "started" && o.busy && o.resumed) " (mid-turn: it gets one continue)" else "") +
            (o.reason?.let { ": $it" } ?: "")

    /** A reopen that failed as a whole. */
    fun failed(d: ReopenDone) = "The reopen failed: ${d.error ?: "no reason given"}"
}

/**
 * Safe reboots as they happen, from `operator:events`: each machine's reboot
 * state as its "host_reboot" events say it, and every finished reopen. A
 * reopen's "done" can come before the reply that names it (a fast machine),
 * so finished ones are kept by id ([done]) for a lookup after the reply; one
 * the machine did itself has no id and is kept apart ([machineReopens]),
 * never taken for one the app asked for.
 */
class Reboots(private val socket: PhoenixSocket, private val scope: CoroutineScope) {
    private val rebootsState = MutableStateFlow<Map<String, HostReboot?>>(emptyMap())
    /**
     * What each machine's last "host_reboot" said, by name: a signal to read
     * the machine list again, which is the copy to show (an event's copy here
     * goes stale once the list is newer).
     */
    val reboots: StateFlow<Map<String, HostReboot?>> = rebootsState.asStateFlow()

    private val doneState = MutableStateFlow<Map<String, ReopenDone>>(emptyMap())
    /** Finished reopens the app asked for, by reopen id. */
    val done: StateFlow<Map<String, ReopenDone>> = doneState.asStateFlow()

    private val machineState = MutableStateFlow<Map<String, ReopenDone>>(emptyMap())
    /** The latest reopen each machine did by itself (after a prepared reboot), by machine. */
    val machineReopens: StateFlow<Map<String, ReopenDone>> = machineState.asStateFlow()

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

    /** The finished reopen with this id, whether it came before or after the reply naming it. */
    fun finished(reopenId: String): ReopenDone? = doneState.value[reopenId]

    internal fun onEvent(event: String, payload: JsonObject) {
        when (event) {
            "host_reboot" -> payload.str("host")?.let { h -> rebootsState.update { it + (h to HostReboot.from(payload.obj("reboot"))) } }
            "host_reopen" -> {
                if (payload.str("state") != "done") return
                val d = runCatching { ReopenDone.from(payload) }.getOrNull() ?: return
                val id = d.reopenId
                if (id == null) machineState.update { it + (d.host to d) } else doneState.update { it + (id to d) }
            }
        }
    }
}
