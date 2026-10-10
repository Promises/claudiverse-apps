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

/** A message waiting for its seat to go idle. */
data class QueuedMessage(val id: Long, val seatId: Long, val text: String, val queuedAt: Long)

/** Where the queue is kept between runs: SharedPreferences on Android, UserDefaults on macOS. */
interface OutboxStore {
    fun load(): String?
    fun save(json: String)
}

/**
 * Messages typed while their seat was busy, delivered when it goes idle.
 *
 * The server rejects a send to a busy seat rather than queueing it, so the
 * queue lives here: saved through [store] (it survives the app being killed),
 * and drained one message per seat per idle — a delivered send makes the seat
 * busy again, so the next one waits for the following idle. Delivery needs
 * the app running.
 */
class Outbox(private val store: OutboxStore, private val api: Api, private val scope: CoroutineScope) {

    private val state = MutableStateFlow(decode(store.load()))
    val items: StateFlow<List<QueuedMessage>> = state.asStateFlow()
    private var drainer: Job? = null

    init {
        if (state.value.isNotEmpty()) startDraining()
    }

    @OptIn(ExperimentalTime::class)
    fun enqueue(seatId: Long, text: String) {
        val id = (state.value.maxOfOrNull { it.id } ?: 0) + 1
        update { it + QueuedMessage(id, seatId, text, Clock.System.now().toEpochMilliseconds()) }
        startDraining()
    }

    fun remove(id: Long) = update { list -> list.filterNot { it.id == id } }

    /**
     * Deliver now, busy or not: the seat takes it into its own queue mid-turn,
     * as if typed while Claude works. Needs the server's `force` support; a
     * server without it answers `busy` and the message stays queued.
     */
    @Throws(Exception::class)
    suspend fun forceSend(id: Long): SendResult {
        val msg = state.value.firstOrNull { it.id == id } ?: return SendResult("error", "no longer queued")
        val result = api.send(msg.seatId, msg.text, force = true)
        if (result.delivered) remove(id)
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
            if (seat.status != "running") return
            if (live?.idle != true) return
            if (api.send(seatId, msg.text).delivered) remove(msg.id)
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
                QueuedMessage(id, seat, text, o.long("at") ?: 0)
            }
        }
    }
}
