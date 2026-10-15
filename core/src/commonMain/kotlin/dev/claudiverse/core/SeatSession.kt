package dev.claudiverse.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One seat as its screen shows it. */
data class SeatState(
    val seat: Seat? = null,
    val live: SeatLive? = null,
    val items: List<TranscriptItem> = emptyList(),
    /**
     * Changes whenever [items] or [rows] do, and only then: streaming text and
     * status polls leave it alone, so a UI can redraw the transcript only when
     * it changed (comparing the lists themselves costs as much as drawing them).
     */
    val transcriptVersion: Long = 0,
    /** [items] with time dividers, as the transcript draws them. */
    val rows: List<TimelineRow> = emptyList(),
    /** Text the model is writing right now, from stream events; empty between messages. */
    val streaming: String = "",
    val socketConnected: Boolean = false,
    val joined: Boolean = false,
    val joinError: String? = null,
    val sending: Boolean = false,
    /** The effort the seat last ran at, from its latest reply; null until one arrives. */
    val effort: String? = null,
    /** True while a file is uploading. */
    val uploading: Boolean = false,
    /** This seat's messages held in this app (cancellable), oldest first. */
    val queued: List<QueuedMessage> = emptyList(),
    /** Claude's own queue on the seat, in the seat's order; empty from a seat whose mod does not list it. */
    val promptQueue: List<QueuedPrompt> = emptyList(),
    /** The Outbox panel: Claude's queue, sent but not yet listed, then held; see [outboxView]. */
    val outbox: List<OutboxRow> = emptyList(),
    /** The last thing the server said about a send, a question answer or a permission. */
    val notice: String? = null,
    val loadError: String? = null,
    /** True while a stop or resume request is in flight. */
    val acting: Boolean = false,
    /** Its agents (forks, subagents, teammates), in the order they started; main is not one of them. */
    val agents: List<SeatAgent> = emptyList(),
    /** Each agent's thread by agent id, changed with [transcriptVersion]. */
    val agentThreads: Map<String, Transcript> = emptyMap(),
    /** What it runs in the background now (shells, monitors, workflows, subagents). */
    val backgroundTasks: List<BackgroundTask> = emptyList(),
)

/**
 * One seat, watched live: joins `session:<id>` as an observer, keeps the
 * replay plus every message after it, and acts on the seat (send, answer,
 * decline, permission, interrupt, stop, resume, files).
 *
 * The seat's status is polled, and its request for the operator answered,
 * only while [setWatched] says its screen is showing. [close] when the screen
 * goes for good.
 */
class SeatSession internal constructor(private val client: ClaudiverseClient, val seatId: Long, parent: CoroutineScope) {

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val state = MutableStateFlow(SeatState())
    val ui: StateFlow<SeatState> = state.asStateFlow()

    private val watched = MutableStateFlow(false)
    /** When the seat was last opened (it is showing), or null while hidden. */
    private val openedAt = MutableStateFlow<Long?>(null)
    private val channel = client.socket.channel("session:$seatId", buildJsonObject { put("role", "observer") })
    /** Fed from the socket and from the caller's thread: guarded. */
    private val feed = SeatFeed()
    private val feedLock = SynchronizedObject()
    private var fetch: Job? = null
    private val sends = Sends(client.api, client.outbox, seatId, scope)

    init {
        scope.launch { channel.events.collect(::onEvent) }
        scope.launch {
            channel.joined.collect { j ->
                state.update { it.copy(joined = j) }
                // A (re)join means the network is up again: fetch the seat now
                // rather than waiting on a request that may have hung before it.
                if (j) restartFetch()
            }
        }
        scope.launch { channel.error.collect { e -> state.update { it.copy(joinError = e) } } }
        scope.launch { client.socket.connected.collect { c -> state.update { it.copy(socketConnected = c) } } }
        scope.launch {
            client.outbox.items.collect { all -> state.update { withOutbox(it.copy(queued = all.filter { q -> q.seatId == seatId })) } }
        }
        scope.launch { sends.sent.collect { state.update(::withOutbox) } }
        scope.launch {
            watched.collectLatest { on ->
                while (on && isActive) {
                    refreshSeat()
                    delay(POLL_MS)
                }
            }
        }
        channel.join()
        // Opening the seat answers a request that was waiting for the operator.
        // One that arrives while it is open is left open, so the operator's
        // other devices still alert: answering it then silently was how a seat
        // left open in an active window swallowed every request it made.
        scope.launch {
            combine(client.humanRequests.open, openedAt) { open, at ->
                at != null && open[seatId]?.let { answeredByOpening(it, at) } == true
            }
                .distinctUntilChanged()
                .collect { if (it) client.humanRequests.resolve(seatId) }
        }
    }

    /** The seat's screen is showing (true) or hidden (false). Showing again also checks the socket. */
    fun setWatched(on: Boolean) {
        if (on && !watched.value) {
            client.socket.checkAlive()
            openedAt.value = nowMs()
        }
        if (!on) openedAt.value = null
        watched.value = on
    }

    /** Fetch the seat now. */
    @Throws(CancellationException::class)
    suspend fun refreshSeat() {
        restartFetch()
        fetch?.join()
    }

    /** One fetch at a time; a new one abandons a running one (it may be hung). */
    private fun restartFetch() {
        fetch?.cancel()
        fetch = scope.launch {
            try {
                val (seat, live) = client.api.seat(seatId)
                state.update { it.copy(seat = seat, live = live, loadError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoreLog.info("seat $seatId not loaded: ${e.message}")
                state.update { it.copy(loadError = e.message) }
            }
        }
    }

    private fun onEvent(e: ChannelEvent) {
        if (e.event == "session_ended") {
            state.update { it.copy(notice = "Session ended") }
            return
        }
        synchronized(feedLock) {
            when (feed.onEvent(e.event, e.payload)) {
                SeatFeed.Change.TRANSCRIPT -> publishLocked()
                SeatFeed.Change.STREAMING -> state.update { it.copy(streaming = feed.streaming) }
                SeatFeed.Change.QUEUE -> publishQueueLocked()
                SeatFeed.Change.NONE -> Unit
            }
        }
    }

    private fun publishLocked() {
        val t = feed.transcript()
        val agents = feed.agents
        val threads = agents.associate { it.id to feed.agentTranscript(it.id) }
        state.update {
            it.copy(items = t.items, rows = t.rows, transcriptVersion = it.transcriptVersion + 1, streaming = feed.streaming, effort = t.effort,
                agents = agents, agentThreads = threads, backgroundTasks = feed.backgroundTasks)
        }
        publishQueueLocked()
    }

    private fun publishQueueLocked() {
        val queue = feed.promptQueue
        sends.onQueue(queue)
        state.update { withOutbox(it.copy(promptQueue = queue)) }
    }

    private fun withOutbox(s: SeatState): SeatState = s.copy(outbox = outboxView(s.queued, s.promptQueue, sends.sent.value))

    /**
     * Send: into Claude's own queue on the seat, to run when the current turn
     * ends. A seat that cannot take it now holds it in the Outbox ([Sends]).
     */
    fun send(text: String) = deliver(text, interrupt = false)

    /** Send now: as [send], and the seat interrupts main's running turn to run it at once. */
    fun sendNow(text: String) = deliver(text, interrupt = true)

    private fun deliver(text: String, interrupt: Boolean) {
        if (text.isBlank()) return
        // Writing to the seat answers its request for the operator.
        client.humanRequests.resolve(seatId)
        state.update { it.copy(sending = true, notice = null) }
        scope.launch {
            val notice = sends.send(text, interrupt)
            // The reply streams back over the socket: make sure it is alive.
            if (notice == null) client.socket.checkAlive()
            state.update { it.copy(sending = false, notice = notice) }
        }
    }

    /**
     * Answer a question with [response], the text the seat sees; [answers]
     * (from [answerMap], when the answer is picked choices) also gives each
     * question its own choice, so a server need not split [response] by header.
     */
    fun answer(toolUseId: String, response: String, answers: Map<String, String> = emptyMap()): Boolean =
        cast("answer", buildJsonObject {
            put("tool_use_id", toolUseId)
            put("response", response)
            if (answers.isNotEmpty()) put("answers", buildJsonObject { answers.forEach { (q, a) -> put(q, a) } })
        }, "Answer sent")

    fun decline(toolUseId: String): Boolean =
        cast("decline", buildJsonObject { put("tool_use_id", toolUseId) }, "Question declined")

    fun permission(requestId: String, allow: Boolean) {
        val decision = if (allow) "allow" else "deny"
        if (cast("permission_response", buildJsonObject { put("request_id", requestId); put("decision", decision) }, null)) {
            synchronized(feedLock) {
                feed.resolveLocally(requestId)
                publishLocked()
            }
        }
    }

    fun interrupt(): Boolean = cast("interrupt", JsonObject(emptyMap()), "Interrupt sent")

    /** Close the seat on its machine; its status turns stopped as its sidecar disconnects. */
    fun stop() {
        val host = state.value.seat?.host ?: return
        state.update { it.copy(acting = true, notice = "Stopping on $host…") }
        scope.launch {
            val notice = try {
                client.api.stopSeat(seatId)
                "Stopped on $host"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when ((e as? ApiException)?.httpStatus) {
                    403 -> "Not stopped: its folder is outside $host's allowed folders, or this app may not stop seats"
                    404 -> "Not stopped: no seat by this title runs on $host (renamed since it started?)"
                    422 -> "Not stopped: the seat never reported its machine"
                    504 -> "Not stopped: $host did not answer (offline?)"
                    else -> "Not stopped: ${e.message}"
                }
            }
            state.update { it.copy(acting = false, notice = notice) }
            refreshSeat()
        }
    }

    /** Start the stopped seat again on its machine; the outcome follows in [ClaudiverseClient.spawns]. */
    fun resume() {
        val seat = state.value.seat?.takeIf { it.canResume } ?: return
        state.update { it.copy(acting = true, notice = null) }
        scope.launch {
            val error = try {
                client.spawns.resume(seat)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when ((e as? ApiException)?.httpStatus) {
                    400 -> "Not resumed: ${e.message?.substringAfter(": ")}"
                    409 -> "Not resumed: the seat is still running"
                    422 -> "Not resumed: the seat never reported its Claude session or folder"
                    429 -> "Not resumed: ${seat.host} is rate-limited, try again in a few seconds"
                    504 -> "Not resumed: ${seat.host} did not answer (offline?)"
                    else -> "Not resumed: ${e.message}"
                }
            }
            state.update { it.copy(acting = false, notice = error) }
        }
    }

    /** `/model` and `/effort` are slash commands: sent as messages, queued while the seat is busy. */
    fun setModel(model: String) = send("/model $model")

    fun setEffort(level: String) = send("/effort $level")

    /** The operator's paging switch: [allow] for [minutes] (null: until changed), or [allow] null for the seat's default. */
    fun setNotify(allow: Boolean?, minutes: Int?) {
        scope.launch {
            val notice = try {
                val seat = client.api.setNotify(seatId, allow, minutes)
                state.update { it.copy(seat = seat) }
                val now = if (seat.canNotify) "may page you" else "may not page you"
                when (allow) {
                    null -> "Paging back to the seat's default: it $now."
                    else -> "It $now" + (seat.notifyUntil?.let { " until the time shown" } ?: " until you change it") + "."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when ((e as? ApiException)?.httpStatus) {
                    403 -> "Paging not changed: only the app's own sign-in may change it"
                    404 -> "Paging not changed: this server does not know this seat, or cannot switch paging yet"
                    else -> "Paging not changed: ${e.message}"
                }
            }
            state.update { it.copy(notice = notice) }
        }
    }

    /** Set the seat's tools, or [level] null for its default; the seat applies it within a minute. */
    fun setAccess(level: String?) {
        scope.launch {
            val notice = try {
                val seat = client.api.setAccess(seatId, level)
                state.update { it.copy(seat = seat) }
                val name = SessionChoices.accessLevels.firstOrNull { it.id == seat.access }?.label ?: seat.access ?: "unknown"
                (if (level == null) "Access back to its default: $name" else "Access set to $name") + ". The seat applies it within a minute."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when ((e as? ApiException)?.httpStatus) {
                    403 -> "Access not changed: only the app's own sign-in may change it"
                    404 -> "Access not changed: this server does not know this seat, or cannot set access yet"
                    else -> "Access not changed: ${e.message}"
                }
            }
            state.update { it.copy(notice = notice) }
        }
    }

    fun setPermissionMode(mode: String) {
        scope.launch {
            val notice = try {
                "Mode set to ${client.api.setPermissionMode(seatId, mode)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when ((e as? ApiException)?.httpStatus) {
                    409 -> "The seat refused: ${e.message?.substringAfter(": ")}"
                    503 -> "Mode not changed: the seat is not connected"
                    504 -> "This seat cannot change mode yet: it needs a restart on a build with the mode hook"
                    else -> "Mode not changed: ${e.message}"
                }
            }
            state.update { it.copy(notice = notice) }
            // The seat reports its mode within ~2 s; show it, not what was picked.
            delay(2_500)
            refreshSeat()
        }
    }

    /**
     * Upload files for the seat, then tell it where to fetch them, in one
     * message with [note]: the seat runs on another machine, so it pulls each
     * file with its own token. Uploads go one after another so the message
     * lists them in the order given. The message goes through [send], so it
     * queues while the seat is busy. Nothing is sent if any upload fails.
     */
    fun sendFiles(files: List<Upload>, note: String) {
        if (files.isEmpty()) return
        client.humanRequests.resolve(seatId)
        state.update { it.copy(uploading = true, notice = null) }
        scope.launch {
            try {
                val uploaded = files.map { client.api.uploadFile(seatId, it) }
                state.update { it.copy(uploading = false) }
                send(filesMessage(uploaded, note))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = if ((e as? ApiException)?.httpStatus == 404) "this server cannot take files yet" else e.message
                state.update { it.copy(uploading = false, notice = "${if (files.size == 1) "File" else "Files"} not sent: $why") }
            }
        }
    }

    fun sendFile(file: Upload, note: String) = sendFiles(listOf(file), note)

    /** Send a held message now, into Claude's queue; [interrupt] also interrupts main's turn. */
    fun forceSend(id: Long, interrupt: Boolean = false) {
        scope.launch {
            val notice = try {
                val r = client.outbox.forceSend(id, interrupt)
                when (r.status) {
                    "delivered" -> {
                        client.socket.checkAlive()
                        null
                    }
                    "busy" -> "Not sent: this server cannot queue into a busy seat — still held"
                    else -> "Not sent: ${r.hint ?: r.status} — still held"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Not sent: ${e.message} — still held"
            }
            state.update { it.copy(notice = notice) }
        }
    }

    fun unqueue(id: Long) = client.outbox.remove(id)

    fun dismissNotice() = state.update { it.copy(notice = null) }

    fun close() {
        channel.leave()
        scope.cancel()
    }

    private fun cast(event: String, payload: JsonObject, okNotice: String?): Boolean {
        val sent = channel.cast(event, payload)
        state.update { it.copy(notice = if (sent) okNotice else "Not connected — $event not sent") }
        return sent
    }

    companion object {
        internal const val POLL_MS = 5_000L

        /** What a seat is told about a file: where to fetch it with its own token. */
        internal fun fileMessage(f: UploadedFile, note: String): String = filesMessage(listOf(f), note)

        /**
         * What a seat is told about files: where to fetch each with its own
         * token, then the operator's note. Public so the Android app, which
         * keeps its own view model, sends the same words as the Mac.
         */
        fun filesMessage(files: List<UploadedFile>, note: String): String = buildString {
            append(if (files.size == 1) "The operator sent you a file: " else "The operator sent you ${files.size} files:")
            files.forEachIndexed { i, f ->
                val safeName = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
                if (files.size > 1) append(if (i == 0) "\n" else "\n\n").append("${i + 1}. ")
                append("${f.name} (${f.size} bytes). Fetch it with:\n")
                append("curl -fsS -H \"Authorization: Bearer \$CLAUDIVERSE_TOKEN\" \"\$CLAUDIVERSE_URL${f.url}\" -o $safeName")
            }
            if (note.isNotBlank()) append("\n\n").append(note.trim())
        }
    }
}
