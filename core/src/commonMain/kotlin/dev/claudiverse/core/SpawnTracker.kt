package dev.claudiverse.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What a host agent was asked to do: start a new seat, or start a stopped one again. */
enum class SpawnOp(val doing: String, val done: String, val notDone: String) {
    Spawn("Spawning", "started", "not started"),
    Resume("Resuming", "resumed", "not resumed"),
}

/** What the snackbar says about the latest spawn or resume. */
sealed interface SpawnNotice {
    val op: SpawnOp
    val title: String
    val host: String

    data class Pending(override val op: SpawnOp, override val title: String, override val host: String) : SpawnNotice

    /**
     * [sessionId]: the row the seat registered as (resumed_as for a resume,
     * started_as for a spawn), when the event names it; otherwise the seat is
     * found by its title.
     */
    data class Started(override val op: SpawnOp, override val title: String, override val host: String, val sessionId: Long?) : SpawnNotice
    data class Failed(override val op: SpawnOp, override val title: String, override val host: String, val error: String) : SpawnNotice
}

/**
 * Follows spawns and resumes started from the app. The server only accepts
 * one (202); the host agent's outcome arrives later as a "host_spawn" event on
 * the operator:events topic, since starting a seat can take up to 90 s. One
 * with no outcome after [GIVE_UP_MS] is reported as failed.
 */
class SpawnTracker(private val api: Api, socket: PhoenixSocket, private val scope: CoroutineScope) {

    private val state = MutableStateFlow<SpawnNotice?>(null)
    val notice: StateFlow<SpawnNotice?> = state.asStateFlow()

    private val channel = socket.channel(TOPIC)
    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<String, Pair<SpawnNotice.Pending, Job>>()
    /**
     * Outcomes that arrived before their request's 202 did: matched when it
     * does. Events for spawns this app did not start land here too, so only
     * the latest few are kept.
     */
    private val early = LinkedHashMap<String, Outcome>()
    private var listening: Job? = null

    private data class Outcome(val ok: Boolean, val title: String?, val error: String?, val sessionId: Long?)

    /** Ask [host] to spawn; returns once the server has accepted it. Throws on a refusal (403, 429, 504…). */
    @Throws(Exception::class)
    suspend fun spawn(host: String, path: String, title: String, mode: String, prompt: String?) {
        listen()
        track(api.spawn(host, path, title, mode, prompt), SpawnNotice.Pending(SpawnOp.Spawn, title, host))
    }

    /** Ask a stopped [seat]'s machine to start it again; returns once accepted. Throws on a refusal (409, 422, 429, 504…). */
    @Throws(Exception::class)
    suspend fun resume(seat: Seat) {
        val host = requireNotNull(seat.host) { "seat ${seat.id} reports no machine" }
        listen()
        track(api.resumeSeat(seat.id), SpawnNotice.Pending(SpawnOp.Resume, seat.title, host))
    }

    fun dismiss() {
        state.value = null
    }

    private fun track(id: String, notice: SpawnNotice.Pending) {
        CoreLog.info("${notice.op} ${notice.title} on ${notice.host} accepted as $id")
        synchronized(lock) { early.remove(id) }?.let {
            state.value = notice.outcome(it)
            return
        }
        state.value = notice
        val timeout = scope.launch {
            delay(GIVE_UP_MS)
            if (synchronized(lock) { pending.remove(id) } != null) {
                state.value = SpawnNotice.Failed(notice.op, notice.title, notice.host, "no answer from ${notice.host}")
            }
        }
        synchronized(lock) { pending[id] = notice to timeout }
    }

    private fun SpawnNotice.Pending.outcome(o: Outcome): SpawnNotice {
        val title = o.title ?: title
        return if (o.ok) SpawnNotice.Started(op, title, host, o.sessionId)
        else SpawnNotice.Failed(op, title, host, o.error ?: "${op.name.lowercase()} failed")
    }

    private fun listen() = synchronized(lock) {
        if (listening != null) return@synchronized
        listening = scope.launch {
            channel.events.collect { e ->
                if (e.event != "host_spawn") return@collect
                val p = e.payload
                val id = p.text("spawn_id")
                val row = (p.long("resumed_as") ?: p.long("started_as"))?.takeIf { it > 0 }
                CoreLog.info("host_spawn $id: ok=${p.flag("ok")} op=${p.str("op")} row=$row error=${p.str("error")}")
                val outcome = Outcome(p.flag("ok"), p.str("title"), p.str("error"), row)
                val entry = synchronized(lock) {
                    pending.remove(id) ?: run {
                        early[id] = outcome
                        if (early.size > EARLY_KEPT) early.remove(early.keys.first())
                        null
                    }
                }
                val (notice, timeout) = entry ?: return@collect
                timeout.cancel()
                state.value = notice.outcome(outcome)
            }
        }
        channel.join()
    }

    private companion object {
        const val TOPIC = "operator:events"
        const val GIVE_UP_MS = 120_000L
        const val EARLY_KEPT = 16
    }
}
