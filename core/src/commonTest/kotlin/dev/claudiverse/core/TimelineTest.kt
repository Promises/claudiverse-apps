package dev.claudiverse.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class TimelineTest {

    private val utc = TimeZone.UTC
    private fun at(s: String): Long = LocalDateTime.parse(s.replace(' ', 'T')).toInstant(utc).toEpochMilliseconds()

    private fun item(key: String) = TranscriptItem.AssistantText(key, "text $key")

    /** The kinds of rows, D for a divider and E for an entry, in order. */
    private fun shape(rows: List<TimelineRow>) = rows.joinToString("") {
        when (it) {
            is TimelineRow.Divider -> "D"
            TimelineRow.Resumed -> "R"
            is TimelineRow.Entry -> "E"
        }
    }

    @Test
    fun theResumedLineFollowsTheEarlierConversation() {
        val items = listOf(item("a"), item("b.0"), item("b.1"), item("c"), item("d"))
        val times = mapOf("a" to at("2026-10-04 21:01"), "b" to at("2026-10-04 21:02"), "c" to at("2026-10-04 21:13"), "d" to at("2026-10-04 21:14"))
        // A message's blocks share its key, so the line never splits one message.
        assertEquals("DEEEREE", shape(Timeline.rows(items, times, utc, earlier = setOf("a", "b"))))
    }

    @Test
    fun aResumedSeatThatHasSaidNothingYetEndsWithTheLine() {
        val items = listOf(item("a"), item("b"))
        val times = mapOf("a" to at("2026-10-04 21:01"), "b" to at("2026-10-04 21:02"))
        assertEquals("DEER", shape(Timeline.rows(items, times, utc, earlier = setOf("a", "b"))))
    }

    @Test
    fun aSeatThatWasNeverResumedHasNoLine() {
        val items = listOf(item("a"), item("b"))
        val times = mapOf("a" to at("2026-10-04 21:01"), "b" to at("2026-10-04 21:02"))
        assertEquals("DEE", shape(Timeline.rows(items, times, utc)))
    }

    @Test
    fun dividerFirstAndAfterALongGapButNotBetweenCloseMessages() {
        val items = listOf(item("a"), item("b"), item("c"))
        val times = mapOf("a" to at("2026-10-02 21:53"), "b" to at("2026-10-02 21:55"), "c" to at("2026-10-02 22:20"))
        assertEquals("DEEDE", shape(Timeline.rows(items, times, utc)))
    }

    @Test
    fun aNewDayAlwaysGetsADividerEvenAfterAShortGap() {
        val items = listOf(item("a"), item("b"))
        val times = mapOf("a" to at("2026-10-02 23:55"), "b" to at("2026-10-03 00:05"))
        assertEquals("DEDE", shape(Timeline.rows(items, times, utc)))
    }

    /** Prompts the server stores carry no timestamp: they take the time before them and add no divider. */
    @Test
    fun anUntimedItemBorrowsThePreviousTime() {
        val items = listOf(item("a"), item("m1"), item("c"))
        val times = mapOf("a" to at("2026-10-02 21:53"), "c" to at("2026-10-02 21:54"))
        val rows = Timeline.rows(items, times, utc)
        assertEquals("DEEE", shape(rows))
        assertEquals(at("2026-10-02 21:53"), (rows[2] as TimelineRow.Entry).at)
    }

    @Test
    fun blocksOfOneMessageShareItsTime() {
        assertEquals("u-1", Timeline.messageKey("u-1.2#1"))
        val items = listOf(item("u-1.0"), item("u-1.1"))
        assertEquals("DEE", shape(Timeline.rows(items, mapOf("u-1" to at("2026-10-02 21:53")), utc)))
    }

    /** Messenger's labels, as in the operator's screenshots. Now is Saturday 3 Oct 2026, 13:41 UTC. */
    @Test
    fun labelsFollowMessenger() {
        val now = at("2026-10-03 13:41")
        assertEquals("13:41", Timeline.label(at("2026-10-03 13:41"), now, utc))
        assertEquals("FRI AT 21:53", Timeline.label(at("2026-10-02 21:53"), now, utc))
        assertEquals("THU AT 09:12", Timeline.label(at("2026-10-01 09:12"), now, utc))
        assertEquals("21 SEP AT 09:12", Timeline.label(at("2026-09-21 09:12"), now, utc))
        assertEquals("3 OCT 2025 AT 13:41", Timeline.label(at("2025-10-03 13:41"), now, utc))
    }
}
