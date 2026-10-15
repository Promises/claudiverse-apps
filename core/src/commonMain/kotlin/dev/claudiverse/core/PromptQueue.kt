package dev.claudiverse.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A message in Claude's own queue on the seat (claudiverse-seat 0.12.0): it
 * runs when the current turn ends, or at once when it interrupts it. Claude
 * Code cannot take a queued prompt back, so it cannot be cancelled. It leaves
 * the queue when its turn starts and shows in the transcript from then on.
 */
data class QueuedPrompt(
    /** The message_id a send answered with, or the seat's own id for one typed there. */
    val id: String,
    val text: String,
    /** When the seat queued it, ms; null when it did not say. */
    val queuedAt: Long?,
    /** "queued", or "interrupting" while the seat aborts main's turn for it. Anything else reads as queued. */
    val state: String,
) {
    val interrupting: Boolean get() = state == INTERRUPTING

    companion object {
        const val QUEUED = "queued"
        const val INTERRUPTING = "interrupting"

        fun from(o: JsonObject): QueuedPrompt? {
            val id = o.str("id") ?: o.long("id")?.toString() ?: return null
            return QueuedPrompt(
                id = id,
                text = o.str("text").orEmpty(),
                queuedAt = parseServerTime(o.str("queued_at")),
                state = if (o.str("state") == INTERRUPTING) INTERRUPTING else QUEUED,
            )
        }

        /** The whole queue, in the seat's order (the order Claude runs them in); entries without an id are dropped. */
        fun list(a: JsonArray?): List<QueuedPrompt> = a?.mapNotNull { (it as? JsonObject)?.let(::from) }.orEmpty()
    }
}

/** A message the server delivered to the seat, before the seat lists it in [QueuedPrompt]s. */
data class SentPrompt(val messageId: String, val text: String, val interrupt: Boolean, val sentAt: Long)

/** One row of a seat's Outbox panel. */
data class OutboxRow(
    val kind: Kind,
    /** Unique within the panel: "held:<id>", "sent:<message_id>" or "queued:<id>". */
    val key: String,
    val text: String,
    /** Only a message still held in this app can be cancelled. */
    val cancellable: Boolean,
    /** What it is waiting on, for people: "held in this app", "in Claude's queue", "interrupting…", … */
    val label: String,
    /** A held message's outbox id, for [Outbox.remove] and sending it now; null for the others. */
    val heldId: Long? = null,
    val interrupting: Boolean = false,
) {
    enum class Kind {
        /** In Claude's queue on the seat. */
        QUEUED,

        /** Delivered by the server, not yet listed by the seat (or a seat whose mod does not list its queue). */
        SENT,

        /** Held in this app (the seat offline, or a send that failed); cancellable. */
        HELD,
    }

    companion object {
        const val IN_QUEUE = "in Claude's queue"
        const val INTERRUPTING = "interrupting…"
        const val SENDING = "sent, waiting for the seat"
        const val HELD = "held in this app"
        const val HELD_INTERRUPT = "held in this app; will interrupt"
    }
}

/**
 * A seat's Outbox panel, in the order the messages will run: Claude's queue
 * first, in the seat's order (oldest first), then messages sent but not yet
 * listed, then those still held in this app, oldest first. A sent message
 * the seat lists is shown once, as the seat's.
 */
fun outboxView(held: List<QueuedMessage>, queue: List<QueuedPrompt>, sent: List<SentPrompt>): List<OutboxRow> {
    val listed = queue.map { it.id }.toSet()
    return queue.map { q ->
        OutboxRow(OutboxRow.Kind.QUEUED, "queued:${q.id}", q.text, cancellable = false,
            label = if (q.interrupting) OutboxRow.INTERRUPTING else OutboxRow.IN_QUEUE, interrupting = q.interrupting)
    } + sent.filter { it.messageId !in listed }.map { s ->
        OutboxRow(OutboxRow.Kind.SENT, "sent:${s.messageId}", s.text, cancellable = false,
            label = if (s.interrupt) OutboxRow.INTERRUPTING else OutboxRow.SENDING, interrupting = s.interrupt)
    } + held.sortedBy { it.queuedAt }.map { h ->
        OutboxRow(OutboxRow.Kind.HELD, "held:${h.id}", h.text, cancellable = true,
            label = if (h.interrupt) OutboxRow.HELD_INTERRUPT else OutboxRow.HELD, heldId = h.id)
    }
}
