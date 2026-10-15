package dev.claudiverse.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sending to one seat, as both apps do it. Send goes straight into Claude's
 * own queue on the seat (force): it runs when the current turn ends. Send now
 * also interrupts: the seat aborts main's turn and runs the message at once.
 *
 * A message the seat cannot take now (offline, stopped, a send that failed,
 * or a server too old to force) is held in the [Outbox] and sent when the
 * seat is back; one sent behind held messages waits behind them, so order
 * holds. A delivered message shows in [sent] until the seat lists it in its
 * queue ([onQueue]), until its turn starts and the transcript shows it
 * ([onTranscript]), or for [SENT_GRACE_MS] when neither happens.
 */
class Sends(private val api: Api, private val outbox: Outbox, private val seatId: Long, private val scope: CoroutineScope) {

    private val sentState = MutableStateFlow<List<SentPrompt>>(emptyList())
    val sent: StateFlow<List<SentPrompt>> = sentState.asStateFlow()

    /** Send [text]; [interrupt] for Send now. Returns a notice for people, or null when it went through. */
    @Throws(CancellationException::class)
    suspend fun send(text: String, interrupt: Boolean = false): String? {
        if (outbox.items.value.any { it.seatId == seatId }) {
            outbox.enqueue(seatId, text, interrupt)
            return BEHIND_HELD
        }
        return try {
            val r = api.send(seatId, text, force = true, interrupt = interrupt)
            when (r.status) {
                "delivered" -> {
                    r.messageId?.let { track(SentPrompt(it, text, interrupt, nowMs())) }
                    null
                }
                "refused" -> "Not delivered: ${r.hint ?: r.status}"
                "busy" -> {
                    // A server that cannot force: the seat is mid-turn, so hold it for the next idle.
                    outbox.enqueue(seatId, text, interrupt)
                    HELD_BUSY
                }
                else -> {
                    outbox.enqueue(seatId, text, interrupt)
                    "$HELD_OFFLINE (${r.hint ?: r.status})"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outbox.enqueue(seatId, text, interrupt)
            "$HELD_OFFLINE (${e.message})"
        }
    }

    /** The seat's queue as last sent: a sent message it lists is the seat's from now on. */
    fun onQueue(queue: List<QueuedPrompt>) {
        val listed = queue.map { it.id }.toSet()
        if (sentState.value.any { it.messageId in listed }) sentState.update { list -> list.filterNot { it.messageId in listed } }
    }

    /**
     * The transcript changed: a sent message it now shows as the user's has
     * started its turn (an idle seat starts it at once, without queueing it).
     */
    fun onTranscript(items: List<TranscriptItem>) {
        if (sentState.value.isEmpty()) return
        val shown = items.takeLast(RECENT_ITEMS).filterIsInstance<TranscriptItem.UserText>().map { it.text.trim() }.toSet()
        if (sentState.value.any { it.text.trim() in shown }) sentState.update { list -> list.filterNot { it.text.trim() in shown } }
    }

    private fun track(s: SentPrompt) {
        sentState.update { it + s }
        scope.launch {
            delay(SENT_GRACE_MS)
            sentState.update { list -> list.filterNot { it.messageId == s.messageId } }
        }
    }

    companion object {
        /** How long a delivered message the seat never lists stays shown as sent. */
        const val SENT_GRACE_MS = 15_000L
        /** How far back [onTranscript] looks for a sent message: the latest turn or two. */
        private const val RECENT_ITEMS = 40
        const val BEHIND_HELD = "Held behind this seat's earlier held messages"
        const val HELD_BUSY = "Seat busy and this server cannot queue into it: held, sends when it goes idle"
        const val HELD_OFFLINE = "Seat not reachable: held in this app, sends when it is back"
    }
}
