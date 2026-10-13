package dev.claudiverse.app.seat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.claudiverse.app.AppContainer
import dev.claudiverse.core.Seat
import dev.claudiverse.core.SeatLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import dev.claudiverse.core.ApiException
import dev.claudiverse.core.RestartPlan
import dev.claudiverse.core.RestartRefused
import dev.claudiverse.core.SeatRestartEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import dev.claudiverse.core.answeredByOpening
import dev.claudiverse.core.nowMs
import dev.claudiverse.core.BackgroundTask
import dev.claudiverse.core.ChannelEvent
import dev.claudiverse.core.QueuedMessage
import dev.claudiverse.core.SeatAgent
import dev.claudiverse.core.SeatFeed
import dev.claudiverse.core.TimelineRow
import dev.claudiverse.core.Transcript
import dev.claudiverse.core.SeatSession
import dev.claudiverse.core.TranscriptItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.claudiverse.core.Upload

data class SeatUiState(
    val seat: Seat? = null,
    val live: SeatLive? = null,
    val items: List<TranscriptItem> = emptyList(),
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
    /** This seat's messages waiting for it to go idle, oldest first. */
    val queued: List<QueuedMessage> = emptyList(),
    /** The last thing the server said about a send, a question answer or a permission. */
    val notice: String? = null,
    val loadError: String? = null,
    /** True while a stop or resume request is in flight. */
    val acting: Boolean = false,
    /**
     * The permission mode this app last set and the server confirmed. A mod
     * seat does not report its mode (live.permissionMode stays null), so this
     * is the best known one until the seat reports its own.
     */
    val appliedMode: String? = null,
    /** What restarting this seat would do, as the server last planned it; null until asked ([SeatViewModel.planRestart]). */
    val restartPlan: RestartPlan? = null,
    /** True while the plan is being read. */
    val restartPlanning: Boolean = false,
    /** The seat's forks and subagents, in the order they started; main is not one of them. */
    val agents: List<SeatAgent> = emptyList(),
    /** Each agent's own thread, by agent id. */
    val agentThreads: Map<String, Transcript> = emptyMap(),
    /** What the seat runs in the background (shells, monitors, subagents, workflows), as it last reported. */
    val backgroundTasks: List<BackgroundTask> = emptyList(),
)

/**
 * One seat, watched live: joins `session:<id>` as an observer, keeps the
 * replay plus every message after it, and acts on the seat (send, answer,
 * decline, permission, interrupt).
 */
class SeatViewModel(private val app: AppContainer, val seatId: Long) : ViewModel() {

    /** Pictures in this seat's messages, loaded through the app's shared cache. */
    val images: TranscriptImages get() = app.images

    private val state = MutableStateFlow(SeatUiState())
    val ui: StateFlow<SeatUiState> = state.asStateFlow()

    private val channel = app.socket.channel("session:$seatId", buildJsonObject { put("role", "observer") })
    private val feed = SeatFeed()

    /** One seat fetch at a time; restart() abandons a running one (it may be hung) and fetches anew. */
    private val seatFetch = object {
        private var job: kotlinx.coroutines.Job? = null

        suspend fun run() {
            restart()
            job?.join()
        }

        fun restart() {
            job?.cancel()
            job = viewModelScope.launch {
                runCatching { app.api.seat(seatId) }
                    .onSuccess { (seat, live) -> state.update { it.copy(seat = seat, live = live, loadError = null) } }
                    .onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.i("claudiverse", "seat $seatId not loaded: ${e.message}")
                        state.update { it.copy(loadError = e.message) }
                    }
            }
        }
    }

    init {
        viewModelScope.launch { channel.events.collect(::onEvent) }
        viewModelScope.launch {
            channel.joined.collect { j ->
                state.update { it.copy(joined = j) }
                // A (re)join means the network is up again: fetch the seat now
                // rather than waiting on a request that may have hung before it.
                if (j) seatFetch.restart()
            }
        }
        viewModelScope.launch { channel.error.collect { e -> state.update { it.copy(joinError = e) } } }
        viewModelScope.launch { app.socket.connected.collect { c -> state.update { it.copy(socketConnected = c) } } }
        viewModelScope.launch {
            app.outbox.items.collect { all -> state.update { it.copy(queued = all.filter { q -> q.seatId == seatId }) } }
        }
        viewModelScope.launch {
            // Seat status is polled only while the screen is collecting it.
            state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                while (watched && isActive) {
                    refreshSeat()
                    delay(5_000)
                }
            }
        }
        channel.join()
        // Opening the seat answers a request that was waiting for the operator.
        // One that arrives while it is on screen is left open, so it still
        // alerts the operator's other devices (core's answeredByOpening).
        viewModelScope.launch {
            var openedAt: Long? = null
            combine(app.humanRequests.open, state.subscriptionCount) { open, watchers -> open[seatId] to (watchers > 0) }
                .collect { (request, showing) ->
                    openedAt = if (showing) openedAt ?: nowMs() else null
                    val at = openedAt
                    if (request != null && at != null && answeredByOpening(request, at)) app.humanRequests.resolve(seatId)
                }
        }
    }

    private suspend fun refreshSeat() = seatFetch.run()


    private fun onEvent(e: ChannelEvent) {
        if (e.event == "session_ended") {
            state.update { it.copy(notice = "Session ended") }
            return
        }
        when (feed.onEvent(e.event, e.payload)) {
            SeatFeed.Change.TRANSCRIPT -> publish()
            SeatFeed.Change.STREAMING -> state.update { it.copy(streaming = feed.streaming) }
            SeatFeed.Change.NONE -> Unit
        }
    }

    private fun publish() {
        val t = feed.transcript()
        val agents = feed.agents
        val threads = agents.associate { it.id to feed.agentTranscript(it.id) }
        state.update {
            it.copy(items = t.items, rows = t.rows, streaming = feed.streaming, effort = t.effort, agents = agents, agentThreads = threads,
                backgroundTasks = feed.backgroundTasks)
        }
    }

    /**
     * Send now if the seat is idle; otherwise queue it for the next idle. A send
     * that races the seat into busy is queued too, never dropped.
     */
    fun send(text: String) {
        if (text.isBlank()) return
        // Writing to the seat answers its request for the operator.
        app.humanRequests.resolve(seatId)
        // Queue behind earlier queued messages so order holds.
        if (state.value.live?.idle == false || state.value.queued.isNotEmpty()) {
            app.outbox.enqueue(seatId, text)
            state.update { it.copy(notice = "Seat busy — queued, sends when it goes idle") }
            return
        }
        state.update { it.copy(sending = true, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val notice = runCatching { app.api.send(seatId, text) }.fold(
                onSuccess = { r ->
                    when (r.status) {
                        "delivered" -> {
                            // The reply streams back over the socket: make sure it is alive.
                            app.socket.checkAlive()
                            null
                        }
                        "busy" -> {
                            app.outbox.enqueue(seatId, text)
                            "Seat busy — queued, sends when it goes idle"
                        }
                        else -> "Not delivered: ${r.hint ?: r.status}"
                    }
                },
                onFailure = { "Not delivered: ${it.message}" },
            )
            state.update { it.copy(sending = false, notice = notice) }
        }
    }

    /**
     * Answers a question with [response], the text the seat sees. [answers]
     * (core's answerMap, for picked choices) also gives each question its own
     * choice, which the server hands to the seat as the question's answers.
     */
    fun answer(toolUseId: String, response: String, answers: Map<String, String> = emptyMap()) =
        cast("answer", buildJsonObject {
            put("tool_use_id", toolUseId)
            put("response", response)
            if (answers.isNotEmpty()) put("answers", buildJsonObject { answers.forEach { (q, a) -> put(q, a) } })
        }, "Answer sent")

    fun decline(toolUseId: String) =
        cast("decline", buildJsonObject { put("tool_use_id", toolUseId) }, "Question declined")

    /**
     * Answer a permission prompt, and wait for the server to take it: an
     * allow is refused for a token without the app's powers, and that comes
     * back on the reply, not as an event. The card closes only once taken.
     */
    fun permission(requestId: String, allow: Boolean) {
        val decision = if (allow) "allow" else "deny"
        viewModelScope.launch {
            val reply = channel.push("permission_response", buildJsonObject { put("request_id", requestId); put("decision", decision) })
            val status = (reply?.get("status") as? JsonPrimitive)?.contentOrNull
            val reason = ((reply?.get("response") as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
            val refusal = when {
                reply == null -> "Not answered: no reply from the server (offline?). The prompt is still open."
                status == "ok" -> null
                else -> "Not answered: " + (reason ?: "the server refused ($status)")
            }
            if (refusal == null) {
                feed.resolveLocally(requestId)
                publish()
            } else state.update { it.copy(notice = refusal) }
        }
    }

    fun interrupt() = cast("interrupt", JsonObject(emptyMap()), "Interrupt sent")

    /** Close the seat on its machine; its status turns stopped as its sidecar disconnects. */
    fun stop() {
        val host = state.value.seat?.host ?: return
        state.update { it.copy(acting = true, notice = "Stopping on $host…") }
        viewModelScope.launch {
            val notice = runCatching { app.api.stopSeat(seatId) }.fold(
                onSuccess = { "Stopped on $host" },
                onFailure = { e ->
                    when ((e as? ApiException)?.httpStatus) {
                        403 -> "Not stopped: its folder is outside $host's allowed folders, or this app may not stop seats"
                        404 -> "Not stopped: no seat by this title runs on $host (renamed since it started?)"
                        422 -> "Not stopped: the seat never reported its machine"
                        504 -> "Not stopped: $host did not answer (offline?)"
                        else -> "Not stopped: ${e.message}"
                    }
                },
            )
            state.update { it.copy(acting = false, notice = notice) }
            refreshSeat()
        }
    }

    /** Start the stopped seat again on its machine; the outcome shows in the app's snackbar. */
    fun resume() {
        val seat = state.value.seat?.takeIf { it.canResume } ?: return
        state.update { it.copy(acting = true, notice = null) }
        viewModelScope.launch {
            val error = runCatching { app.spawns.resume(seat) }.exceptionOrNull()?.let { e ->
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

    /** Ask the server what restarting this seat would do: restart, adopt into tmux, or why not now. */
    fun planRestart() {
        if (state.value.restartPlanning) return
        state.update { it.copy(restartPlanning = true) }
        viewModelScope.launch {
            val plan = runCatching { app.api.planSeatRestart(seatId) }.getOrElse { e ->
                RestartPlan(seatId, state.value.seat?.label.orEmpty(), state.value.seat?.host, null, RestartPlan.SKIP,
                    "The server could not plan it: ${e.message}", null, null, null)
            }
            state.update { it.copy(restartPlan = plan, restartPlanning = false) }
        }
    }

    /**
     * Restart the seat on its machine's current Claude Code (or adopt it into
     * a tmux seat), following its events until it is done. A seat that is not
     * idle enough is refused in the server's words.
     */
    fun restart(confirmProtected: Boolean = false) {
        val plan = state.value.restartPlan?.takeIf { it.choosable } ?: return
        if (plan.needsProtectedConfirm && !confirmProtected) return
        val host = plan.host ?: state.value.seat?.host ?: "its machine"
        val doing = if (plan.action == RestartPlan.ADOPT) "Adopting into a tmux seat" else "Restarting"
        state.update { it.copy(acting = true, notice = "$doing on $host…") }
        app.watchRestarts()
        viewModelScope.launch {
            try {
                val id = app.api.restartSeat(seatId, confirmProtected = confirmProtected)
                // One final event follows "started"; a machine that never sends it leaves a line saying so, not a stuck seat.
                val done = withTimeoutOrNull(RESTART_WAIT_MS) {
                    app.restarts.events.mapNotNull { all -> all.lastOrNull { it.restartId == id && it.finished } }.first()
                }
                state.update { it.copy(notice = done?.let(::restartOutcome) ?: "Restart sent; $host has not said how it went yet", restartPlan = null) }
                refreshSeat()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RestartRefused) {
                state.update { it.copy(notice = "Not restarted: ${e.message}", restartPlan = null) }
            } catch (e: Exception) {
                val why = when ((e as? ApiException)?.httpStatus) {
                    403 -> "this app may not restart seats"
                    404 -> "the server does not know this seat"
                    504 -> "$host did not answer (offline?)"
                    else -> e.message
                }
                state.update { it.copy(notice = "Not restarted: $why", restartPlan = null) }
            } finally {
                state.update { it.copy(acting = false) }
                app.unwatchRestarts()
            }
        }
    }

    /** The screen is visible again: a socket may have died while it was not. */
    fun onResume() = app.socket.checkAlive()

    /** `/model` and `/effort` are slash commands: sent as messages, queued while the seat is busy. */
    fun setModel(alias: String) = send("/model $alias")

    fun setEffort(level: String) = send("/effort $level")

    /** Set the seat's Claudiverse tools; null returns them to the default for how it was started. */
    fun setTools(level: String?) = changeAccess(if (level == null) "Tools back to their default" else "Tools set to $level") {
        app.api.setAccess(seatId, level)
    }

    /** Allow or forbid paging, for [minutes] or until changed; null for the default. */
    fun setNotify(allow: Boolean?, minutes: Int?) = changeAccess(
        when (allow) {
            null -> "Paging back to its default"
            true -> "May page you" + (minutes?.let { " for ${duration(it)}" } ?: "")
            false -> "May not page you" + (minutes?.let { " for ${duration(it)}" } ?: "")
        },
    ) { app.api.setNotify(seatId, allow, minutes) }

    private fun duration(minutes: Int) = if (minutes % 1440 == 0) "${minutes / 1440} d" else "${minutes / 60} h"

    private fun changeAccess(done: String, call: suspend () -> Unit) {
        viewModelScope.launch {
            val notice = runCatching { call() }.fold(
                onSuccess = { done },
                onFailure = { e ->
                    when ((e as? ApiException)?.httpStatus) {
                        403 -> "Not changed: this needs the app's own token"
                        404 -> "Not changed: this server has no access settings yet"
                        else -> "Not changed: ${e.message}"
                    }
                },
            )
            state.update { it.copy(notice = notice) }
            refreshSeat()
        }
    }

    fun setPermissionMode(mode: String) {
        viewModelScope.launch {
            // A mod seat's mode is switched by its machine's agent pressing Shift+Tab
            // in its pane (server bc45601): the server's sentence says why it could
            // not (mid-turn, a dialog open, the agent offline or silent).
            val result = runCatching { app.api.setPermissionMode(seatId, mode) }
            val notice = result.fold(
                onSuccess = { applied -> "Mode set to $applied" },
                onFailure = { e ->
                    val why = e.message?.substringAfter(": ")
                    when ((e as? ApiException)?.httpStatus) {
                        409 -> "Mode not changed: $why"
                        503, 504 -> "Mode not changed: $why"
                        else -> "Mode not changed: ${e.message}"
                    }
                },
            )
            state.update { s -> s.copy(notice = notice, appliedMode = result.getOrNull() ?: s.appliedMode) }
            // The seat reports its mode within ~2 s; show it, not what was tapped.
            delay(2_500)
            refreshSeat()
        }
    }

    /**
     * Upload a file for the seat, then tell it where to fetch it: the seat runs
     * on another machine, so it pulls the file with its own token. The message
     * goes through [send], so it queues while the seat is busy.
     */
    /**
     * Upload [files] in order, then tell the seat about all of them in one
     * message (core's wording, as the Mac sends), the note last. The message
     * goes through [send], so it queues while the seat is busy.
     */
    fun sendFiles(files: List<Upload>, note: String) {
        if (files.isEmpty()) return
        app.humanRequests.resolve(seatId)
        state.update { it.copy(uploading = true, notice = null) }
        viewModelScope.launch {
            runCatching { files.map { app.api.uploadFile(seatId, it) } }
                .onSuccess { uploaded ->
                    state.update { it.copy(uploading = false) }
                    send(SeatSession.filesMessage(uploaded, note))
                }
                .onFailure { e ->
                    val why = if ((e as? ApiException)?.httpStatus == 404) "this server cannot take files yet" else e.message
                    state.update { it.copy(uploading = false, notice = "Not sent: $why") }
                }
        }
    }

    fun forceSend(id: Long) {
        viewModelScope.launch {
            val notice = runCatching { app.outbox.forceSend(id) }.fold(
                onSuccess = { r ->
                    when (r.status) {
                        "delivered" -> { app.socket.checkAlive(); "Sent now — the seat takes it mid-turn" }
                        "busy" -> "Not sent: this server cannot force a send yet — still queued"
                        else -> "Not sent: ${r.hint ?: r.status} — still queued"
                    }
                },
                onFailure = { "Not sent: ${it.message} — still queued" },
            )
            state.update { it.copy(notice = notice) }
        }
    }

    fun unqueue(id: Long) = app.outbox.remove(id)

    fun dismissNotice() = state.update { it.copy(notice = null) }

    private fun cast(event: String, payload: JsonObject, okNotice: String?): Boolean {
        val sent = channel.cast(event, payload)
        state.update { it.copy(notice = if (sent) okNotice else "Not connected — $event not sent") }
        return sent
    }

    override fun onCleared() {
        channel.leave()
    }
}

private const val RESTART_WAIT_MS = 5 * 60_000L

/** What a finished restart did, in a line: "Restarted on 2.1.296", "Adopted into a tmux seat", or why not. */
internal fun restartOutcome(e: SeatRestartEvent): String = when (e.outcome) {
    "restarted" -> "Restarted" + (e.newVersion?.let { " on $it" } ?: "") + (if (e.killed) " (its old process had to be killed)" else "")
    "adopted" -> "Adopted into a tmux seat" + (e.newVersion?.let { " on $it" } ?: "") + (e.note?.let { ". $it" } ?: "")
    "skipped" -> "Not restarted: " + (e.reason ?: "the machine skipped it")
    "failed" -> when {
        e.leftStopped -> "Stopped and not started again: " + (e.reason ?: "no reason given")
        e.unconfirmed -> "Started again, but it has not registered yet; it may be running: " + (e.reason ?: "no reason given")
        else -> "Restart failed: " + (e.reason ?: "no reason given")
    }
    else -> "Restart ${e.outcome}"
}
