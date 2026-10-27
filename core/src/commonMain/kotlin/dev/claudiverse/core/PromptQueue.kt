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
    /** "queued", "interrupting" while a Claude seat aborts main's turn for it, or "steering" while a pi seat's enters the run. Anything else reads as queued. */
    val state: String,
    /** A pi seat's entry: "followUp" (after this run) or "steer" (at the next step); null for a Claude seat's. */
    val kind: String? = null,
    /** Where a pi seat's entry came from: app, cv, ping or terminal. */
    val source: String? = null,
) {
    val interrupting: Boolean get() = state == INTERRUPTING

    /** A pi seat's entry still waiting: it can be deleted, and a follow-up made to steer. A Claude seat's never. */
    val editable: Boolean get() = kind != null && state == QUEUED
    val canSteer: Boolean get() = editable && kind == FOLLOW_UP

    /** Where it stands, for people. */
    val label: String get() = when {
        state == INTERRUPTING -> OutboxRow.INTERRUPTING
        state == STEERING -> "entering the run"
        kind == STEER -> "steers at the next step"
        kind == FOLLOW_UP -> "after this run"
        else -> OutboxRow.IN_QUEUE
    }

    companion object {
        const val QUEUED = "queued"
        const val INTERRUPTING = "interrupting"
        const val STEERING = "steering"
        const val FOLLOW_UP = "followUp"
        const val STEER = "steer"

        fun from(o: JsonObject): QueuedPrompt? {
            val id = o.str("id") ?: o.long("id")?.toString() ?: return null
            return QueuedPrompt(
                id = id,
                text = o.str("text").orEmpty(),
                queuedAt = parseServerTime(o.str("queued_at")),
                state = when (val s = o.str("state")) {
                    INTERRUPTING, STEERING -> s
                    else -> QUEUED
                },
                kind = o.str("kind"),
                source = o.str("source"),
            )
        }

        /** The whole queue, in the seat's order (the order Claude runs them in); entries without an id are dropped. */
        fun list(a: JsonArray?): List<QueuedPrompt> = a?.mapNotNull { (it as? JsonObject)?.let(::from) }.orEmpty()
    }
}

/**
 * A message the server holds for a busy seat before handing it over: until
 * the seat goes idle or [deliverAt], at most 15 s, whichever comes first.
 * Until then it can be taken back ([Api.cancelHeld]) or sent at once
 * ([Api.sendHeldNow]); once delivered it is in Claude's queue ([QueuedPrompt]).
 */
data class HeldMessage(val id: String, val text: String, val heldAt: Long?, val deliverAt: Long?) {
    companion object {
        fun from(o: JsonObject): HeldMessage? {
            val id = o.str("id") ?: o.long("id")?.toString() ?: return null
            return HeldMessage(id, o.str("text").orEmpty(), parseServerTime(o.str("held_at")), parseServerTime(o.str("deliver_at")))
        }

        /** The whole list, oldest first; entries without an id are dropped. */
        fun list(a: JsonArray?): List<HeldMessage> = a?.mapNotNull { (it as? JsonObject)?.let(::from) }.orEmpty()
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
    /** A message the server holds: its id, for [Api.cancelHeld] and [Api.sendHeldNow]. */
    val messageId: String? = null,
    /** When the server hands it to the seat, ms; after that it cannot be taken back. */
    val deliverAtMs: Long? = null,
    /** A waiting pi-seat entry's id, for [Api.deleteQueueItem]; null when it cannot be deleted. */
    val queueItemId: String? = null,
    /** A waiting pi-seat follow-up: [Api.steerQueueItem] makes it steer the running run. */
    val canSteer: Boolean = false,
) {
    enum class Kind {
        /** In Claude's queue on the seat. */
        QUEUED,

        /** Held by the server for a busy seat, for at most 15 s; can be taken back until [deliverAtMs]. */
        SERVER_HELD,

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
        const val HELD_ON_SERVER = "sends when the seat is idle, or within 15 s"
    }
}

/**
 * After Send now on a held message answered too_late (it already went to the
 * seat): interrupt only while it still waits in Claude's queue behind a turn.
 * Gone from the queue, it is the turn running now (the seat went idle and
 * took it), and an interrupt would abort the very message asked for.
 */
fun interruptAfterTooLate(messageId: String, queue: List<QueuedPrompt>): Boolean = queue.any { it.id == messageId }

/**
 * Whether [row] can still be taken back at [nowMs]: one held in this app
 * always; one the server holds until its [OutboxRow.deliverAtMs] (the trash
 * greys at that moment, without waiting for the server's next list); one in
 * Claude's queue never.
 */
fun cancellableNow(row: OutboxRow, nowMs: Long): Boolean = when (row.kind) {
    OutboxRow.Kind.HELD -> true
    OutboxRow.Kind.SERVER_HELD -> row.deliverAtMs == null || nowMs < row.deliverAtMs
    // A pi seat's waiting entry can be deleted; a Claude seat's queue never.
    OutboxRow.Kind.QUEUED -> row.queueItemId != null
    else -> false
}

/**
 * A seat's Outbox panel, in the order the messages will run: Claude's queue
 * first, in the seat's order (oldest first), then those the server holds
 * (they join the queue behind it), then messages sent but not yet listed,
 * then those still held in this app, oldest first. A sent message the seat
 * or the server lists is shown once, as theirs.
 */
fun outboxView(
    held: List<QueuedMessage>,
    queue: List<QueuedPrompt>,
    sent: List<SentPrompt>,
    serverHeld: List<HeldMessage> = emptyList(),
): List<OutboxRow> {
    val inQueue = queue.map { it.id }.toSet()
    val onServer = serverHeld.filter { it.id !in inQueue }
    val listed = inQueue + onServer.map { it.id }
    return queue.map { q ->
        OutboxRow(OutboxRow.Kind.QUEUED, "queued:${q.id}", q.text, cancellable = false,
            label = q.label, interrupting = q.interrupting,
            queueItemId = q.id.takeIf { q.editable }, canSteer = q.canSteer)
    } + onServer.map { h ->
        OutboxRow(OutboxRow.Kind.SERVER_HELD, "server:${h.id}", h.text, cancellable = true,
            label = OutboxRow.HELD_ON_SERVER, messageId = h.id, deliverAtMs = h.deliverAt)
    } + sent.filter { it.messageId !in listed }.map { s ->
        OutboxRow(OutboxRow.Kind.SENT, "sent:${s.messageId}", s.text, cancellable = false,
            label = if (s.interrupt) OutboxRow.INTERRUPTING else OutboxRow.SENDING, interrupting = s.interrupt)
    } + held.sortedBy { it.queuedAt }.map { h ->
        OutboxRow(OutboxRow.Kind.HELD, "held:${h.id}", h.text, cancellable = true,
            label = if (h.interrupt) OutboxRow.HELD_INTERRUPT else OutboxRow.HELD, heldId = h.id)
    }
}

/**
 * What to tell the operator after deleting or steering a pi seat's queued
 * entry ([Api.deleteQueueItem], [Api.steerQueueItem]); null when it went
 * through. "already": it was done before; "too_late": it entered a run.
 */
fun queueActionNotice(status: String, steer: Boolean): String? = when (status) {
    "requested", "already" -> null
    "too_late" -> if (steer) "Too late to steer: it has entered the run" else "Too late to delete: it has entered the run"
    "not_found" -> "The seat no longer lists it"
    "not_connected" -> "The seat is not connected"
    else -> (if (steer) "Not steered: " else "Not deleted: ") + status
}
