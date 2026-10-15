package dev.claudiverse.core

import kotlinx.serialization.json.JsonObject

/** What a seat's transcript shows at one moment. */
data class Transcript(
    val items: List<TranscriptItem>,
    /** [items] with time dividers, as the transcript draws them. */
    val rows: List<TimelineRow>,
    /** The effort the seat last ran at, from its latest reply; null until one arrives. */
    val effort: String?,
)

/**
 * One seat's transcript, fed the events of its `session:<id>` channel: the
 * replay, then every message, permission request and resolution after it.
 *
 * Not thread-safe: feed it from one thread (the UI's).
 */
class SeatFeed(private val maxMessages: Int = MAX_MESSAGES) {

    /** What an event changed, so a UI redraws no more than it must. */
    enum class Change { NONE, STREAMING, TRANSCRIPT, QUEUE }

    /**
     * Messages in arrival order, keyed by uuid. The same message can arrive
     * twice — in the replay and again live when a join races a broadcast, or
     * mirrored twice by the sidecar — and a repeat must replace, not append.
     */
    private val messages = LinkedHashMap<String, JsonObject>()
    private val livePermissions = LinkedHashMap<String, JsonObject>()
    /**
     * The [messages] ids of what a resumed seat's replay carried from its
     * earlier rows. Ids, not transcript keys: a message without a uuid (a
     * stored operator prompt) is keyed by its position, which trimming shifts.
     */
    private var earlierMessages: Set<String> = emptySet()
    private val resolvedPermissions = HashSet<String>()
    private var lastEffort: String? = null

    /** Each agent's rows by uuid, in arrival order: never mixed into [messages]. */
    private val agentRows = LinkedHashMap<String, LinkedHashMap<String, JsonObject>>()
    /** The seat's agents by id: the latest snapshot, updated by each start and end in between. */
    private val agentsById = LinkedHashMap<String, SeatAgent>()

    /** What the seat runs in the background now: Claude Code's whole list, as last sent. */
    var backgroundTasks: List<BackgroundTask> = emptyList()
        private set

    /** Claude's own queue on the seat, as last sent: the whole list, in the seat's order. */
    var promptQueue: List<QueuedPrompt> = emptyList()
        private set

    /** The seat's agents in the order they started, as the switcher lists them. */
    val agents: List<SeatAgent> get() = agentsById.values.toList()

    /** Text the model is writing right now, from stream events; empty between messages. */
    var streaming: String = ""
        private set

    fun onEvent(event: String, payload: JsonObject): Change = when (event) {
        "replay" -> {
            // A rejoin replays the buffer again: replace, never append.
            messages.clear()
            agentRows.clear()
            agentsById.clear()
            // A resumed seat's replay starts with the conversation it continues, from its earlier rows.
            val earlierCount = payload.long("earlier_count") ?: 0
            val earlier = HashSet<String>()
            payload.array("messages")?.forEachIndexed { i, element ->
                val m = element as? JsonObject ?: return@forEachIndexed
                if (isAgents(m)) { agentEvent(m); return@forEachIndexed }
                val id = keep(m)
                if (i < earlierCount && id != null) earlier += id
            }
            // Agents' rows have a replay buffer of their own; the snapshot names agents whose start scrolled out.
            payload.array("agent_messages")?.forEach { (it as? JsonObject)?.let(::agentEvent) }
            payload.array("agents")?.forEach { (it as? JsonObject)?.let(::snapshotAgent) }
            // The latest background list; replays without one (an older server) leave none.
            backgroundTasks = BackgroundTask.list(payload.array("background_tasks"))
            // Likewise Claude's queue; a seat on an older mod sends none.
            promptQueue = QueuedPrompt.list(payload.array("prompt_queue"))
            earlierMessages = earlier
            streaming = ""
            Change.TRANSCRIPT
        }
        "message" -> when {
            payload.text("type") == "stream_event" -> if (streamDelta(payload)) Change.STREAMING else Change.NONE
            // An agent's rows and state: its own thread, never main's (a transcript change, so both redraw).
            // The whole list each time: it replaces the last one, and is never a transcript row.
            payload.text("type") == "background_tasks" -> {
                backgroundTasks = BackgroundTask.list(payload.array("tasks"))
                Change.TRANSCRIPT
            }
            // Claude's queue: the whole list each time, replacing the last; never a transcript row.
            payload.text("type") == "prompt_queue" -> {
                promptQueue = QueuedPrompt.list(payload.array("queue"))
                Change.QUEUE
            }
            isAgents(payload) -> if (agentEvent(payload)) Change.TRANSCRIPT else Change.NONE
            TranscriptBuilder.isNoise(payload) -> Change.NONE
            else -> {
                keep(payload)
                if (payload.text("type") == "assistant") streaming = ""
                Change.TRANSCRIPT
            }
        }
        "permission_request" -> {
            livePermissions[payload.text("request_id")] = payload
            Change.TRANSCRIPT
        }
        "permission_resolved" -> {
            resolvedPermissions += payload.text("request_id")
            Change.TRANSCRIPT
        }
        else -> Change.NONE
    }

    /** A permission answered from here: no longer pending, before the seat confirms it. */
    fun resolveLocally(requestId: String) {
        resolvedPermissions += requestId
    }

    /** Main's transcript: no agent's rows, nor an agent's permission requests. */
    fun transcript(): Transcript = build(messages, null)

    /** One agent's thread, built as main's is; empty for an agent with no rows yet. */
    fun agentTranscript(agentId: String): Transcript = build(agentRows[agentId] ?: LinkedHashMap(), agentId)

    private fun build(rows: LinkedHashMap<String, JsonObject>, agentId: String?): Transcript {
        val all = rows.values.toList()
        val items = TranscriptBuilder.build(all, resolvedPermissions).toMutableList()
        val inTranscript = items.filterIsInstance<TranscriptItem.Permission>().map { it.requestId }.toSet()
        livePermissions.values
            .filter { it.str("agent_id") == agentId && it.text("request_id") !in inTranscript }
            .forEach { items += TranscriptBuilder.permissionFromEvent(it, it.text("request_id") !in resolvedPermissions) }
        // The seat stamps every reply with the effort it ran at: the latest
        // one is the effort actually in use, whatever was last requested.
        if (agentId == null) all.lastOrNull { it.text("type") == "assistant" && "effort" in it }?.let { lastEffort = it.text("effort") }
        val unique = TranscriptBuilder.uniqueKeys(if (agentId != null) TranscriptBuilder.agentItems(items) else items)
        val earlier = if (agentId != null || earlierMessages.isEmpty()) emptySet()
        else rows.keys.mapIndexedNotNull { i, id ->
            if (id in earlierMessages) TranscriptBuilder.messageKey(all[i], i) else null
        }.toSet()
        return Transcript(unique, Timeline.rows(unique, Timeline.messageTimes(all), earlier = earlier), if (agentId == null) lastEffort else null)
    }

    /** Keep [m] unless it is noise; returns the id it is kept under. */
    private fun keep(m: JsonObject): String? {
        if (TranscriptBuilder.isNoise(m)) return null
        // Without a uuid the record itself is the identity: an operator prompt
        // noted by the client carries only {type, message}, and a second copy
        // of it is byte-identical.
        val id = m.str("uuid") ?: m.str("request_id")?.let { "req:$it" } ?: "raw:$m"
        messages[id] = m
        val excess = messages.size - maxMessages
        if (excess > 0) messages.keys.take(excess).forEach(messages::remove)
        return id
    }

    /** An agent's state or rows, or any other row an agent made (its permission request, say): not main's. */
    private fun isAgents(m: JsonObject) = m.text("type") in SeatAgent.TYPES || m.str("agent_id") != null

    /** Apply one agent message; true when anything shown changed. */
    private fun agentEvent(m: JsonObject): Boolean {
        val id = m.str("agent_id")
        return when (m.text("type")) {
            "agents" -> {
                val snapshot = m.array("agents")?.mapNotNull { (it as? JsonObject)?.let { a -> SeatAgent.from(a) } }.orEmpty()
                // The snapshot is the seat's own list; a start's tool call (absent from it) is kept.
                val known = agentsById.toMap()
                agentsById.clear()
                snapshot.forEach { a -> agentsById[a.id] = a.copy(toolUseId = a.toolUseId ?: known[a.id]?.toolUseId, parentAgentId = a.parentAgentId ?: known[a.id]?.parentAgentId) }
                true
            }
            "agent_started" -> {
                if (id == null) return false
                agentsById[id] = SeatAgent.from(m, status = agentsById[id]?.status ?: "running")
                true
            }
            "agent_ended" -> {
                val a = id?.let(agentsById::get) ?: return false
                agentsById[id] = a.copy(status = m.str("status") ?: "completed")
                true
            }
            "agent_turn_complete" -> {
                val a = id?.let(agentsById::get) ?: return false
                if (a.hasEnded) return false
                agentsById[id] = a.copy(status = "idle")
                true
            }
            "agent_message" -> {
                val row = m.obj("message") ?: return false
                if (id == null || TranscriptBuilder.isNoise(row)) return false
                val rows = agentRows.getOrPut(id) { LinkedHashMap() }
                rows[row.str("uuid") ?: "raw:$row"] = row
                val excess = rows.size - maxMessages
                if (excess > 0) rows.keys.take(excess).forEach(rows::remove)
                // A row from an agent nothing announced (its start scrolled out): list it until a snapshot names it.
                if (id !in agentsById) agentsById[id] = SeatAgent(id, "agent", "", null, fork = false, background = false, status = "running")
                true
            }
            // Another row an agent made: its thread's, as is.
            else -> {
                if (id == null || TranscriptBuilder.isNoise(m)) return false
                agentRows.getOrPut(id) { LinkedHashMap() }[m.str("uuid") ?: m.str("request_id")?.let { "req:$it" } ?: "raw:$m"] = m
                true
            }
        }
    }

    private fun snapshotAgent(o: JsonObject) {
        val a = SeatAgent.from(o)
        agentsById[a.id] = a.copy(toolUseId = a.toolUseId ?: agentsById[a.id]?.toolUseId)
    }

    /** True when [streaming] changed. */
    private fun streamDelta(m: JsonObject): Boolean {
        val event = m.obj("event") ?: return false
        return when (event.text("type")) {
            "message_start" -> {
                streaming = ""
                true
            }
            "content_block_delta" -> {
                val delta = event.obj("delta")?.takeIf { it.text("type") == "text_delta" } ?: return false
                streaming += delta.text("text")
                true
            }
            else -> false
        }
    }

    companion object {
        const val MAX_MESSAGES = 2_000
    }
}
