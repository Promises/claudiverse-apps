package dev.claudiverse.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A transcript row: an item, a time divider above a gap, or the line where a resumed seat took over. */
sealed interface TimelineRow {
    val key: String

    data class Divider(override val key: String, val at: Long) : TimelineRow

    /** Above the first item of a resumed seat: everything before it is the conversation it continues. */
    data object Resumed : TimelineRow {
        override val key = "resumed"
    }

    /** [at] is the item's time: its message's own, or the last known before it when the message has none. */
    data class Entry(override val key: String, val item: TranscriptItem, val at: Long?) : TimelineRow
}

/**
 * Messenger-style timestamps: a divider above an item when it comes after a
 * long gap ([GAP_MS]) or on a new day, nothing between items close together.
 * The exact time of any item is shown on tap (in the UI).
 */
@OptIn(ExperimentalTime::class)
object Timeline {
    const val GAP_MS = 15 * 60 * 1000L

    /** Each message's time by the key TranscriptBuilder gives its items. */
    fun messageTimes(messages: List<JsonObject>): Map<String, Long> =
        messages.mapIndexedNotNull { index, m ->
            val at = parseServerTime(m.str("timestamp")) ?: return@mapIndexedNotNull null
            TranscriptBuilder.messageKey(m, index) to at
        }.toMap()

    /**
     * [earlier]: keys of the messages a resumed seat's replay carries from its
     * earlier rows. The [TimelineRow.Resumed] line goes after the last of them,
     * at the end while the resumed seat has said nothing yet.
     */
    fun rows(
        items: List<TranscriptItem>,
        times: Map<String, Long>,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        earlier: Set<String> = emptySet(),
    ): List<TimelineRow> {
        val out = ArrayList<TimelineRow>(items.size + 8)
        var last: Long? = null
        var seenEarlier = false
        var resumedDrawn = false
        for (item in items) {
            val isEarlier = messageKey(item.key) in earlier
            if (seenEarlier && !isEarlier && !resumedDrawn) {
                out += TimelineRow.Resumed
                resumedDrawn = true
            }
            if (isEarlier) seenEarlier = true
            val own = times[messageKey(item.key)]
            if (own != null && (last == null || own - last > GAP_MS || !sameDay(last, own, zone))) {
                out += TimelineRow.Divider("time:${item.key}", own)
            }
            if (own != null) last = own
            out += TimelineRow.Entry(item.key, item, own ?: last)
        }
        if (seenEarlier && !resumedDrawn) out += TimelineRow.Resumed
        return out
    }

    /** Item keys are "<message key>[.<block>][#<n>]"; the message key is what carries the time. */
    fun messageKey(itemKey: String): String = itemKey.substringBefore('#').substringBefore('.')

    /**
     * Messenger's labels: today the time ("13:41"), this past week the day
     * ("FRI AT 21:53"), this year the date ("1 OCT AT 09:12"), else with the year.
     */
    fun label(at: Long, now: Long = Clock.System.now().toEpochMilliseconds(), zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val time = local(at, zone)
        val days = dayIndex(now, zone) - dayIndex(at, zone)
        val format = when {
            days == 0L -> TIME
            days in 1..6 -> WEEKDAY
            time.year == local(now, zone).year -> DATE
            else -> DATE_YEAR
        }
        return time.format(format).uppercase()
    }

    private val TIME = LocalDateTime.Format { hour(); char(':'); minute() }
    private val WEEKDAY = LocalDateTime.Format {
        dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED); chars(" AT "); hour(); char(':'); minute()
    }
    private val DATE = LocalDateTime.Format {
        day(Padding.NONE); char(' '); monthName(MonthNames.ENGLISH_ABBREVIATED); chars(" AT "); hour(); char(':'); minute()
    }
    private val DATE_YEAR = LocalDateTime.Format {
        day(Padding.NONE); char(' '); monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); year()
        chars(" AT "); hour(); char(':'); minute()
    }

    private fun local(t: Long, zone: TimeZone): LocalDateTime = Instant.fromEpochMilliseconds(t).toLocalDateTime(zone)

    private fun sameDay(a: Long, b: Long, zone: TimeZone) = dayIndex(a, zone) == dayIndex(b, zone)

    private fun dayIndex(t: Long, zone: TimeZone): Long = local(t, zone).date.toEpochDays().toLong()
}
