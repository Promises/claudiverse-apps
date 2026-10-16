package dev.claudiverse.core

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A message held in this app until its seat can take it; [interrupt]: sent as Send now. */
data class QueuedMessage(val id: Long, val seatId: Long, val text: String, val queuedAt: Long, val interrupt: Boolean = false)

/** Where the queue is kept between runs: SharedPreferences on Android, UserDefaults on macOS. */
interface OutboxStore {
    fun load(): String?
    fun save(json: String)
}

/**
 * Messages held in this app because their seat could not take them: it was
 * offline or stopped, a send failed, or the server cannot force a send into
 * a busy seat. Saved through [store] (it survives the app being killed) and
 * drained one message per seat per poll, in order, into the seat's own queue
 * (force) once the seat runs with its sidecar connected; on a server that
 * cannot force, only when the seat is idle. Delivery needs the app running.
 */
class Outbox(private val store: OutboxStore, private val api: Api, private val scope: CoroutineScope) {

    private val state = MutableStateFlow(decode(store.load()))
    val items: StateFlow<List<QueuedMessage>> = state.asStateFlow()
    private var drainer: Job? = null

    init {
        if (state.value.isNotEmpty()) startDraining()
    }

    @OptIn(ExperimentalTime::class)
    fun enqueue(seatId: Long, text: String, interrupt: Boolean = false) {
        val id = (state.value.maxOfOrNull { it.id } ?: 0) + 1
        update { it + QueuedMessage(id, seatId, text, Clock.System.now().toEpochMilliseconds(), interrupt) }
        startDraining()
    }

    fun remove(id: Long) = update { list -> list.filterNot { it.id == id } }

    /**
     * Deliver a held message now, busy or not: into the seat's own queue, and
     * with [interrupt] (or when it was held as Send now) interrupting main's
     * turn. A server without `force` answers `busy` and it stays held.
     */
    @Throws(Exception::class)
    suspend fun forceSend(id: Long, interrupt: Boolean = false): SendResult {
        val msg = state.value.firstOrNull { it.id == id } ?: return SendResult("error", "no longer held")
        val result = api.send(msg.seatId, msg.text, force = true, interrupt = interrupt || msg.interrupt)
        if (result.accepted) remove(id)
        return result
    }

    private fun startDraining() {
        if (drainer?.isActive == true) return
        drainer = scope.launch {
            while (isActive && state.value.isNotEmpty()) {
                state.value.groupBy { it.seatId }.forEach { (seatId, queued) -> drainOne(seatId, queued.first()) }
                delay(POLL_MS)
            }
        }
    }

    private suspend fun drainOne(seatId: Long, msg: QueuedMessage) {
        try {
            val (seat, live) = api.seat(seatId)
            // A seat that has ended will never go idle: its queue is dead.
            if (seat.status != "running" || live?.sidecarConnected == false) return
            val r = api.send(seatId, msg.text, force = true, interrupt = msg.interrupt)
            // A server that cannot force answers busy until the seat is idle. A held
            // message is the server's now: sending it again would queue another copy.
            if (r.accepted) remove(msg.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CoreLog.info("outbox: seat $seatId not drained (${e.message})")
        }
    }

    private fun update(f: (List<QueuedMessage>) -> List<QueuedMessage>) {
        state.update(f)
        store.save(encode(state.value))
    }

    internal companion object {
        const val POLL_MS = 4_000L

        /** The stored form, unchanged from the Android app's: [{id, seat, text, at}]. A queue already on a phone reads back. */
        fun encode(list: List<QueuedMessage>): String = JsonArray(
            list.map {
                buildJsonObject {
                    put("id", it.id)
                    put("seat", it.seatId)
                    put("text", it.text)
                    put("at", it.queuedAt)
                    if (it.interrupt) put("interrupt", true)
                }
            },
        ).toString()

        /** Rows that cannot be read are dropped rather than failing the whole queue. */
        fun decode(json: String?): List<QueuedMessage> {
            val array = json?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
            return array.objects().mapNotNull { o ->
                val id = o.long("id") ?: return@mapNotNull null
                val seat = o.long("seat") ?: return@mapNotNull null
                val text = o.str("text") ?: return@mapNotNull null
                QueuedMessage(id, seat, text, o.long("at") ?: 0, o.flag("interrupt"))
            }
        }
    }
}
