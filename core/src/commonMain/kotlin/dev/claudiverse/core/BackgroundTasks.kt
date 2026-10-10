package dev.claudiverse.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Something a seat runs in the background, as Claude Code's own task list
 * has it (format v1, claudiverse-seat 0.10.0): a shell (`run_in_background`),
 * a monitor, a subagent or a workflow. In the list from when it starts until
 * main's turn ends after it finished, or it is stopped.
 */
data class BackgroundTask(
    val id: String,
    /** "shell", "monitor", "subagent", "workflow", … */
    val type: String,
    /** "running", "pending", … */
    val status: String,
    val description: String?,
    /** A shell's or monitor's command (a WebSocket monitor's url). */
    val command: String?,
    /** When the seat saw it start, ms; null for one first seen at a turn end. */
    val startedAt: Long?,
    /** A subagent's type ("Explore", …). */
    val agentType: String? = null,
    /** A workflow's name. */
    val name: String? = null,
    /** An MCP monitor's server and tool. */
    val server: String? = null,
    val tool: String? = null,
) {
    val isRunning: Boolean get() = status == "running" || status == "pending"

    /** A subagent's type, a workflow's name, or an MCP monitor's "server · tool". */
    val detail: String? get() = agentType ?: name ?: listOfNotNull(server, tool).joinToString(" · ").ifEmpty { null }

    /**
     * What to call it: its description (Claude Code's own words for a
     * background shell), else its detail, else its command, else its type.
     */
    val label: String get() =
        description?.takeIf { it.isNotBlank() } ?: detail?.takeIf { it.isNotBlank() } ?: command?.takeIf { it.isNotBlank() } ?: type

    companion object {
        fun from(o: JsonObject) = BackgroundTask(
            id = o.text("id"),
            type = o.str("type") ?: "task",
            status = o.str("status") ?: "running",
            description = o.str("description"),
            command = o.str("command"),
            startedAt = parseServerTime(o.str("started_at")),
            agentType = o.str("agent_type"),
            name = o.str("name"),
            server = o.str("server"),
            tool = o.str("tool"),
        )

        fun list(a: JsonArray?): List<BackgroundTask> = a?.mapNotNull { (it as? JsonObject)?.let(::from) }.orEmpty()

        /**
         * The ones to list beside the agents: shells, monitors and workflows.
         * Subagents are in the list too, but show as agents already.
         */
        fun shown(tasks: List<BackgroundTask>): List<BackgroundTask> = tasks.filter { it.type != "subagent" }
    }
}

/** How long something has run: "45 s", "12 min", "3 h 5 min". */
fun elapsedLabel(fromMs: Long, nowMs: Long): String {
    val s = ((nowMs - fromMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h${(s % 3600 / 60).takeIf { it > 0 }?.let { " $it min" } ?: ""}"
    }
}
