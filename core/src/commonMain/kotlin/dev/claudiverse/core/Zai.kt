package dev.claudiverse.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A Z.ai (GLM) Coding Plan key on the server: its plan and quota windows as
 * the server last read them (every 5 minutes). The key itself never leaves
 * the server. On [status] "error" the windows keep the last good numbers and
 * [lastError] says why the last check failed.
 */
data class ZaiKey(
    val id: Long,
    val label: String,
    /** "pro", "lite", …; null when not read yet. */
    val level: String?,
    val status: String,
    val lastError: String?,
    /** The server's last check, ISO. */
    val checkedAt: String?,
    val windows: List<ZaiWindow>,
) {
    val ok: Boolean get() = status == "ok"

    companion object {
        fun from(o: JsonObject) = ZaiKey(
            id = o.requireLong("id"),
            label = o.str("label") ?: "Z.ai key",
            level = o.str("level"),
            status = o.str("status") ?: "unknown",
            lastError = o.str("last_error"),
            checkedAt = o.str("checked_at"),
            windows = o.array("windows").objects().map(ZaiWindow::from),
        )
    }
}

/** One quota window of a key: credits used of the limit, and when it resets (null on an unused window). */
data class ZaiWindow(
    val window: String,
    val used: Long?,
    val limit: Long?,
    val remaining: Long?,
    val percent: Int?,
    val resetsAt: String?,
) {
    /** Used up: the quota is gone until it resets. */
    val spent: Boolean get() = (percent ?: 0) >= 100 || (remaining == 0L && (limit ?: 0) > 0)

    companion object {
        fun from(o: JsonObject) = ZaiWindow(
            window = o.str("window") ?: "?",
            used = o.long("used"),
            limit = o.long("limit"),
            remaining = o.long("remaining"),
            percent = o.int("percent"),
            resetsAt = o.str("resets_at"),
        )
    }
}

/** A window's name for people, as the web has it: "5h" → "5 hours", "week" → "Week", any other as is. */
fun zaiWindowLabel(w: ZaiWindow): String = when (w.window) {
    "5h" -> "5 hours"
    "week" -> "Week"
    else -> w.window
}

/**
 * A window's line under its bar: "60078 of 60000 credits · spent until
 * tomorrow 09:08" when used up, else "0 of 12000 credits" and when it resets
 * if the server says.
 */
@OptIn(ExperimentalTime::class)
fun zaiWindowLine(w: ZaiWindow, now: Long = Clock.System.now().toEpochMilliseconds(), zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val credits = "${w.used ?: "?"} of ${w.limit ?: "?"} credits"
    val reset = parseServerTime(w.resetsAt)?.let { resetTime(it, now, zone) }
    return when {
        w.spent -> "$credits · spent until ${reset ?: "it resets"}"
        reset != null -> "$credits · resets $reset"
        else -> credits
    }
}

/** A coming time, briefly: "09:08" today, "tomorrow 09:08", "Mon 09:08" within the week, else "2 Nov 09:08". */
@OptIn(ExperimentalTime::class)
fun resetTime(at: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
    val hm = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    val days = t.date.toEpochDays() - today.toEpochDays()
    return when {
        days <= 0 -> hm
        days == 1L -> "tomorrow $hm"
        days < 7 -> t.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + hm
        else -> "${t.day} ${t.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} $hm"
    }
}
