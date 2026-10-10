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
import kotlinx.serialization.json.JsonObject

/**
 * Open requests from seats asking for the operator, kept current while
 * something watches them (the phone's watch service, the desktop's menu bar,
 * or an app on screen).
 *
 * Live through the socket channel `operator:human_requests`, with a REST
 * read on every (re)join and every [RECHECK_MS] as a safety net: a request
 * that arrives while the socket is down is still picked up.
 */
class HumanRequests(private val api: Api, private val socket: PhoenixSocket, private val scope: CoroutineScope) {

    private val state = MutableStateFlow<Map<Long, HumanRequest>>(emptyMap())

    /** Open requests by seat (session) id. */
    val open: StateFlow<Map<Long, HumanRequest>> = state.asStateFlow()

    private val listState = MutableStateFlow<List<HumanRequest>>(emptyList())
    /**
     * The same requests as a list, newest change first. For Swift: a Kotlin
     * map keyed by Long crossed into Swift crashed the macOS app on a failed
     * key cast the first time a seat asked for the operator.
     */
    val requests: StateFlow<List<HumanRequest>> = listState.asStateFlow()

    private fun publish(update: (Map<Long, HumanRequest>) -> Map<Long, HumanRequest>) {
        state.update(update)
        listState.value = state.value.values.sortedByDescending { it.updatedAt ?: it.insertedAt.orEmpty() }
    }

    private val supportedState = MutableStateFlow(true)
    /** False when the server has no human-request endpoints (404). */
    val supported: StateFlow<Boolean> = supportedState.asStateFlow()

    private val lock = SynchronizedObject()
    private var jobs: List<Job> = emptyList()
    private var channel: PhoenixSocket.Channel? = null

    fun start() = synchronized(lock) {
        if (jobs.isNotEmpty()) return@synchronized
        val ch = socket.channel(TOPIC).also { channel = it }
        jobs = listOf(
            scope.launch { ch.events.collect { onEvent(it.event, it.payload) } },
            // Every successful (re)join: re-read, so nothing missed while disconnected is lost.
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
        channel?.leave()
        channel = null
    }

    @Throws(CancellationException::class)
    suspend fun refresh() {
        try {
            val list = api.openHumanRequests()
            supportedState.value = true
            publish { list.associateBy { it.sessionId } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiException)?.httpStatus == 404) supportedState.value = false
            CoreLog.info("human requests not read: ${e.message}")
        }
    }

    /** The operator opened this seat: its request is answered. */
    fun resolve(seatId: Long) {
        if (seatId !in state.value) return
        publish { it - seatId }
        scope.launch {
            runCatching { api.resolveHumanRequests(seatId) }
                .onFailure { CoreLog.info("resolve for seat $seatId failed: ${it.message}") }
        }
    }

    private fun onEvent(event: String, payload: JsonObject) {
        when (event) {
            "human_request" -> runCatching { HumanRequest.from(payload) }.getOrNull()
                ?.let { r -> publish { it + (r.sessionId to r) } }
            "human_request_resolved" -> {
                val seat = payload.long("session_id")
                publish { m -> if (seat != null) m - seat else m.filterValues { it.id != payload.long("id") } }
            }
        }
    }

    private companion object {
        const val TOPIC = "operator:human_requests"
        const val RECHECK_MS = 120_000L
    }
}
