package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One event pushed by the server on a channel. */
data class ChannelEvent(val event: String, val payload: JsonObject)

/**
 * A Phoenix socket (serializer V2: `[join_ref, ref, topic, event, payload]`).
 *
 * Opens when the first channel joins and closes when the last one leaves.
 * While any channel wants to be joined it reconnects with backoff and rejoins
 * every channel, so a server restart or a tunnel drop heals on its own.
 */
class PhoenixSocket internal constructor(
    private val access: () -> ServerAccess,
    private val scope: CoroutineScope,
    private val http: HttpClient,
) {
    constructor(access: () -> ServerAccess, scope: CoroutineScope) : this(access, scope, socketClient())

    private val connectedState = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = connectedState.asStateFlow()

    private val lock = SynchronizedObject()
    private var refs = 0L
    private val channels = mutableMapOf<String, Channel>()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    /** The running connection, from the first attempt until it is lost or closed. */
    private var connection: Job? = null
    /** Set once the handshake is done; joins sent before that would be sent twice. */
    private var session: DefaultClientWebSocketSession? = null
    /**
     * Frames waiting to be written. Ktor's outgoing channel is not a buffer: a
     * frame offered while the previous one is still being written is dropped,
     * which lost the second of two joins sent back to back.
     */
    private var queue: SendChannel<String>? = null
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private var attempt = 0

    /**
     * The channel for [topic], shared: the server allows one join per topic on
     * a socket. Each call is a claim that [Channel.leave] gives back, and only
     * the last claim's leave leaves the topic — a seat shown twice (a board
     * tile being moved, a seat window beside the main view) must not have one
     * view's close silence the other.
     */
    fun channel(topic: String, params: JsonObject = JsonObject(emptyMap())): Channel = synchronized(lock) {
        channels.getOrPut(topic) { Channel(topic, params) }.also { it.users++ }
    }

    inner class Channel internal constructor(val topic: String, private val params: JsonObject) {
        private val eventsFlow = MutableSharedFlow<ChannelEvent>(extraBufferCapacity = 256)
        val events: SharedFlow<ChannelEvent> = eventsFlow.asSharedFlow()

        private val joinedState = MutableStateFlow(false)
        val joined: StateFlow<Boolean> = joinedState.asStateFlow()

        private val errorState = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = errorState.asStateFlow()

        internal var wanted = false
        /** Claims from [channel] not yet given back by [leave]. */
        internal var users = 0
        internal var joinRef: String? = null
        /**
         * The connection this channel last sent a join on. A join() racing the
         * socket opening would otherwise join twice: once from join(), once
         * from the open handler.
         */
        private var joinedOn: DefaultClientWebSocketSession? = null

        /**
         * Join, or for a second user of a joined channel, join again: the server
         * replays the topic only in answer to a join, and the new user's view
         * needs that replay. Earlier users get it too, and replace with it.
         */
        fun join() {
            synchronized(lock) {
                if (wanted) joinedOn = null
                wanted = true
            }
            ensureOpen()
            sendJoin()
        }

        /** Give back one claim; the last one leaves the topic. */
        fun leave() {
            val last = synchronized(lock) {
                users = (users - 1).coerceAtLeast(0)
                users == 0
            }
            if (!last) return
            wanted = false
            joinedState.value = false
            joinRef?.let { send(it, nextRef(), topic, "phx_leave", JsonObject(emptyMap())) }
            joinRef = null
            synchronized(lock) { joinedOn = null }
            synchronized(lock) { channels.remove(topic) }
            closeIfUnused()
        }

        /** Push an event and wait for the server's reply; null if none came in time. */
        @Throws(CancellationException::class)
        suspend fun push(event: String, payload: JsonObject, timeoutMs: Long = 10_000): JsonObject? {
            val ref = nextRef()
            val reply = CompletableDeferred<JsonObject>()
            synchronized(lock) { pending[ref] = reply }
            if (!send(joinRef, ref, topic, event, payload)) {
                synchronized(lock) { pending.remove(ref) }
                return null
            }
            return withTimeoutOrNull(timeoutMs) { reply.await() }.also { synchronized(lock) { pending.remove(ref) } }
        }

        /** Push without waiting: for events the server never replies to. */
        fun cast(event: String, payload: JsonObject): Boolean = send(joinRef, nextRef(), topic, event, payload)

        internal fun sendJoin() {
            val ref = synchronized(lock) {
                val current = session
                if (!wanted || current == null || joinedOn === current) return
                joinedOn = current
                // Not nextRef(): it takes the lock, and the lock is not re-entrant on every platform.
                (++refs).toString().also { joinRef = it }
            }
            val reply = CompletableDeferred<JsonObject>()
            synchronized(lock) { pending[ref] = reply }
            if (!send(ref, ref, topic, "phx_join", params)) return
            scope.launch {
                val r = withTimeoutOrNull(10_000) { reply.await() }
                synchronized(lock) { pending.remove(ref) }
                when (r?.str("status")) {
                    "ok" -> {
                        joinedState.value = true
                        errorState.value = null
                    }
                    null -> errorState.value = "join timed out"
                    else -> errorState.value = r.obj("response")?.str("reason") ?: "join refused"
                }
            }
        }

        internal fun deliver(event: String, payload: JsonObject, frameJoinRef: String?) {
            // Every server push carries the join_ref of the join it belongs to.
            // A frame from an earlier join (a rejoin replaced it, or a socket
            // being closed is still draining) would deliver the same broadcast
            // a second time, so only the current join is listened to.
            if (frameJoinRef != null && frameJoinRef != joinRef) return
            when (event) {
                "phx_error", "phx_close" -> {
                    joinedState.value = false
                    synchronized(lock) { joinedOn = null }
                    if (wanted) scope.launch {
                        delay(2_000)
                        sendJoin()
                    }
                }
                else -> eventsFlow.tryEmit(ChannelEvent(event, payload))
            }
        }

        internal fun socketLost() {
            joinedState.value = false
            joinedOn = null
        }
    }

    // --- socket lifecycle ---

    private fun ensureOpen() {
        synchronized(lock) {
            if (connection != null) return
            val url = socketUrl() ?: return
            // Started only once recorded: run() checks it is still the current connection.
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) { run(job, url) }
            connection = job
            job.start()
        }
    }

    /** `ws(s)://<base>/socket/websocket?token=…&vsn=2.0.0`, or null when the server URL is not usable. */
    private fun socketUrl(): String? {
        val a = access()
        val builder = runCatching { URLBuilder(a.baseUrl) }.getOrNull() ?: return null
        builder.protocol = when (builder.protocol) {
            URLProtocol.HTTP -> URLProtocol.WS
            URLProtocol.HTTPS -> URLProtocol.WSS
            else -> return null
        }
        if (builder.host.isBlank()) return null
        builder.appendPathSegments("socket", "websocket")
        builder.parameters.append("token", a.token)
        builder.parameters.append("vsn", "2.0.0")
        return builder.buildString()
    }

    private suspend fun run(job: Job, url: String) {
        try {
            http.webSocket(url) {
                val frames = kotlinx.coroutines.channels.Channel<String>(UNLIMITED)
                val writer = launch { for (text in frames) outgoing.send(Frame.Text(text)) }
                try {
                    opened(job, this, frames)
                    for (frame in incoming) {
                        if (frame is Frame.Text) onMessage(frame.readText())
                    }
                    lost(job, "closed ${closeReason.await()?.toString().orEmpty()}")
                } finally {
                    frames.close()
                    writer.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lost(job, "failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun opened(job: Job, ws: DefaultClientWebSocketSession, frames: SendChannel<String>) {
        val toJoin = synchronized(lock) {
            if (connection !== job) return
            CoreLog.info("socket open")
            attempt = 0
            session = ws
            queue = frames
            heartbeat?.cancel()
            heartbeat = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    probe(job, HEARTBEAT_TIMEOUT_MS)
                }
            }
            channels.values.filter { it.wanted }
        }
        connectedState.value = true
        toJoin.forEach { it.sendJoin() }
    }

    private fun onMessage(text: String) {
        val frame = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
        fun at(i: Int): String? = (frame.getOrNull(i) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        val joinRef = at(0)
        val ref = at(1)
        val topic = at(2).orEmpty()
        val event = at(3).orEmpty()
        val payload = frame.getOrNull(4) as? JsonObject ?: JsonObject(emptyMap())
        if (event == "phx_reply" && ref != null) {
            synchronized(lock) { pending[ref] }?.complete(payload)
            return
        }
        synchronized(lock) { channels[topic] }?.deliver(event, payload, joinRef)
    }

    private fun closeIfUnused() {
        val closing = synchronized(lock) {
            if (channels.values.any { it.wanted }) return
            reconnect?.cancel()
            heartbeat?.cancel()
            val s = session
            val c = connection
            session = null
            queue = null
            connection = null
            s to c
        }
        connectedState.value = false
        val (s, c) = closing
        scope.launch {
            runCatching { s?.close() }
            c?.cancel()
        }
    }

    private fun lost(job: Job, why: String) {
        val retry = synchronized(lock) {
            if (connection !== job) return
            CoreLog.info("socket lost ($why)")
            connection = null
            session = null
            queue = null
            heartbeat?.cancel()
            channels.values.forEach { it.socketLost() }
            channels.values.any { it.wanted }
        }
        connectedState.value = false
        if (!retry) return
        synchronized(lock) {
            reconnect?.cancel()
            reconnect = scope.launch {
                delay(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)])
                attempt++
                ensureOpen()
            }
        }
    }

    /**
     * Check the socket now: after a send, or when a screen comes back. Without
     * this a link that died silently stays "connected" until the next
     * heartbeat notices.
     */
    fun checkAlive() {
        val job = synchronized(lock) { connection.takeIf { session != null } }
        if (job == null) reconnectNow() else scope.launch { probe(job, PROBE_TIMEOUT_MS) }
    }

    /**
     * Skip the backoff wait: the network just came back, or the user is
     * looking. Backoff is for an unreachable server, not a returned network.
     */
    fun reconnectNow() {
        synchronized(lock) {
            if (connection != null || channels.values.none { it.wanted }) return
            reconnect?.cancel()
            attempt = 0
        }
        ensureOpen()
    }

    /**
     * A heartbeat the server must answer. A mobile or VPN link can drop with no
     * error on our side, and a socket that is only ever written to looks alive
     * forever: no reply in time means dead, so it is torn down and the
     * reconnect path rejoins (and replays) every channel.
     */
    private suspend fun probe(job: Job, timeoutMs: Long): Boolean {
        val ref = nextRef()
        val reply = CompletableDeferred<JsonObject>()
        synchronized(lock) { pending[ref] = reply }
        val alive = send(null, ref, "phoenix", "heartbeat", JsonObject(emptyMap())) &&
            withTimeoutOrNull(timeoutMs) { reply.await() } != null
        synchronized(lock) { pending.remove(ref) }
        if (!alive) {
            lost(job, "no heartbeat reply in $timeoutMs ms")
            job.cancel()
        }
        return alive
    }

    private fun nextRef(): String = synchronized(lock) { (++refs).toString() }

    private fun send(joinRef: String?, ref: String, topic: String, event: String, payload: JsonObject): Boolean {
        val frames = synchronized(lock) { queue } ?: return false
        val frame = JsonArray(listOf(joinRef?.let(::JsonPrimitive) ?: JsonNull, JsonPrimitive(ref), JsonPrimitive(topic), JsonPrimitive(event), payload))
        return frames.trySend(frame.toString()).isSuccess
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        const val HEARTBEAT_MS = 20_000L
        const val HEARTBEAT_TIMEOUT_MS = 10_000L
        const val PROBE_TIMEOUT_MS = 5_000L

        /** No read timeout: a quiet channel is normal, and the heartbeat is what detects a dead link. */
        fun socketClient(): HttpClient = platformHttpClient {
            install(WebSockets)
            install(HttpTimeout) { connectTimeoutMillis = 10_000 }
        }
    }
}
