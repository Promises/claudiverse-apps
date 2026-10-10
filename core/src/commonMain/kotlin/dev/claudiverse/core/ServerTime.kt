package dev.claudiverse.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Server timestamps are ISO-8601 UTC, with or without a trailing Z or
 * fraction (2026-10-02T18:30:17.049Z); the second is enough here.
 */
@OptIn(ExperimentalTime::class)
fun parseServerTime(iso: String?): Long? {
    if (iso == null || iso.length < 19) return null
    return runCatching { LocalDateTime.parse(iso.substring(0, 19)).toInstant(TimeZone.UTC).toEpochMilliseconds() }.getOrNull()
}

/** "just now", "5m ago", "2h 10m ago", "in 3d 1h"; empty when there is no time. */
@OptIn(ExperimentalTime::class)
fun relativeTime(iso: String?, now: Long = Clock.System.now().toEpochMilliseconds()): String {
    val t = parseServerTime(iso) ?: return ""
    val s = (now - t) / 1000
    return when {
        s < -60 -> "in " + span(-s)
        s < 60 -> "just now"
        else -> span(s) + " ago"
    }
}

private fun span(seconds: Long): String = when {
    seconds < 3_600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    else -> "${seconds / 86_400}d ${seconds % 86_400 / 3_600}h"
}
