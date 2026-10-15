package dev.claudiverse.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The server's auto-restart: every minute it restarts, one at a time, seats
 * that run an outdated Claude Code or mod and have been idle at least
 * [minIdleS] seconds. Never a protected, paused, runner or busy seat, and a
 * seat at most once an hour. Off unless the operator turns it on.
 */
data class AutoRestart(val enabled: Boolean = false, val minIdleS: Int = DEFAULT_MIN_IDLE_S) {
    val minIdleMinutes: Int get() = minIdleS / 60

    companion object {
        const val DEFAULT_MIN_IDLE_S = 300
        /** The server's bounds for min_idle_s. */
        const val MIN_IDLE_S = 60
        const val MAX_IDLE_S = 86_400

        fun from(o: JsonObject?): AutoRestart =
            AutoRestart(o?.bool("enabled") ?: false, o?.int("min_idle_s") ?: DEFAULT_MIN_IDLE_S)
    }
}

/** A fleet's own auto-restart setting: a null field inherits the global value. */
data class AutoRestartOverride(val enabled: Boolean? = null, val minIdleS: Int? = null) {
    val isEmpty: Boolean get() = enabled == null && minIdleS == null

    companion object {
        /** Null when the fleet has no override at all. */
        fun from(o: JsonObject?): AutoRestartOverride? = o?.let { AutoRestartOverride(it.bool("enabled"), it.int("min_idle_s")) }
    }
}

/** The body that changes the global setting: only the fields given; the server keeps the rest. */
internal fun autoRestartBody(enabled: Boolean?, minIdleS: Int?): JsonObject = buildJsonObject {
    put("auto_restart", buildJsonObject {
        enabled?.let { put("enabled", it) }
        minIdleS?.let { put("min_idle_s", it) }
    })
}

/**
 * The body that sets a fleet's override as a whole: each field given, or null
 * to inherit it; an empty override clears it (auto_restart: null).
 */
internal fun fleetAutoRestartBody(o: AutoRestartOverride): JsonObject = buildJsonObject {
    if (o.isEmpty) put("auto_restart", JsonNull)
    else put("auto_restart", buildJsonObject {
        put("enabled", o.enabled?.let { JsonPrimitive(it) } ?: JsonNull)
        put("min_idle_s", o.minIdleS?.let { JsonPrimitive(it) } ?: JsonNull)
    })
}
