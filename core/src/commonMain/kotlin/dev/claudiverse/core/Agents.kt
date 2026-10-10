package dev.claudiverse.core

import kotlinx.serialization.json.JsonObject

/**
 * One of a seat's agents besides its main conversation: a fork, a subagent
 * (Explore, general-purpose, …) or a teammate, as Claude Code's agent
 * switcher lists them. Its rows arrive apart from main's ("agent_message"),
 * so main's transcript is unchanged by it.
 */
data class SeatAgent(
    val id: String,
    /** "fork", "Explore", "general-purpose", …: Claude Code's subagent type. */
    val type: String,
    /** The switcher's label: what it was asked to do. */
    val description: String,
    val name: String?,
    val fork: Boolean,
    val background: Boolean,
    /** pending, running, waiting (a teammate waiting for a message), idle, completed, failed or killed. */
    val status: String,
    /** The Agent tool call in main that spawned it, for a subagent; its tool row opens this agent's thread. */
    val toolUseId: String? = null,
    val parentAgentId: String? = null,
) {
    /** Working now: what makes a seat busy beyond its main conversation. */
    val isBusy: Boolean get() = status == "pending" || status == "running"
    val hasEnded: Boolean get() = status in ENDED

    /** Its name in the switcher: the description, else its name, else its type. */
    val label: String get() = description.ifBlank { name?.takeIf { it.isNotBlank() } ?: type }

    companion object {
        val ENDED = setOf("completed", "failed", "killed")

        /** From an "agents" snapshot entry or an "agent_started" message. */
        fun from(o: JsonObject, status: String? = null) = SeatAgent(
            id = o.text("agent_id"),
            type = o.str("agent_type") ?: "agent",
            description = o.str("description").orEmpty(),
            name = o.str("name"),
            fork = o.bool("fork") ?: false,
            background = o.bool("background") ?: false,
            status = status ?: o.str("status") ?: "running",
            toolUseId = o.str("tool_use_id"),
            parentAgentId = o.str("parent_agent_id"),
        )

        /** The message types that carry an agent's state or rows, kept apart from main's. */
        val TYPES = setOf("agent_started", "agent_message", "agent_turn_complete", "agent_ended", "agents")
    }
}
