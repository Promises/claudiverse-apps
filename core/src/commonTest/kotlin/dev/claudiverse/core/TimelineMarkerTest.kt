package dev.claudiverse.core

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An auto-restart is a line in the transcript at its time, not a banner that stays. */
class TimelineMarkerTest {

    private fun item(key: String) = TranscriptItem.UserText(key, key, injected = false)
    private val utc = TimeZone.UTC
    private fun shape(rows: List<TimelineRow>) = rows.map {
        when (it) {
            is TimelineRow.Entry -> it.key
            is TimelineRow.Marker -> "M:${it.label}"
            is TimelineRow.Divider -> "D"
            TimelineRow.Resumed -> "R"
        }
    }

    @Test
    fun aMarkerGoesBeforeTheFirstLaterEntryAndItsDivider() {
        val t0 = 1_760_000_000_000L
        val items = listOf(item("a"), item("b"), item("c"))
        // c comes after a long gap, so it has a time divider; the marker falls in that gap.
        val times = mapOf("a" to t0, "b" to t0 + 60_000, "c" to t0 + Timeline.GAP_MS + 120_000)
        val rows = Timeline.rows(items, times, utc, markers = listOf(TimelineMarker("r1", t0 + 90_000, "restarted")))
        assertEquals(listOf("D", "a", "b", "M:restarted", "D", "c"), shape(rows))
    }

    @Test
    fun aMarkerAfterEverythingGoesLastAndMarkersKeepTheirOrder() {
        val t0 = 1_760_000_000_000L
        val rows = Timeline.rows(
            listOf(item("a")), mapOf("a" to t0), utc,
            markers = listOf(TimelineMarker("r2", t0 + 2, "second"), TimelineMarker("r1", t0 + 1, "first")),
        )
        assertEquals(listOf("D", "a", "M:first", "M:second"), shape(rows))
        assertEquals(listOf("D", "a"), shape(Timeline.rows(listOf(item("a")), mapOf("a" to t0), utc)), "no markers, no change")
    }

    private fun event(s: String) = SeatRestartEvent.from(Json.parseToJsonElement(s).jsonObject)

    @Test
    fun onlyThisSeatsFinishedAutoRestartsBecomeMarkers() {
        val events = listOf(
            event("""{"restart_id":"r1","session_id":5,"outcome":"restarted","new_version":"2.1.300","origin":"auto"}""").copy(atMs = 10),
            event("""{"restart_id":"r2","session_id":5,"outcome":"started","origin":"auto"}""").copy(atMs = 11),
            event("""{"restart_id":"r3","session_id":5,"outcome":"restarted","origin":"manual"}""").copy(atMs = 12),
            event("""{"restart_id":"r4","session_id":9,"outcome":"restarted","origin":"auto","new_session_id":5}""").copy(atMs = 13),
            event("""{"restart_id":"r5","session_id":7,"outcome":"restarted","origin":"auto"}""").copy(atMs = 14),
        )
        val marks = restartMarkers(events, 5)
        assertEquals(listOf("restart:r1:5", "restart:r4:9"), marks.map { it.key })
        assertEquals("Auto-restarted on 2.1.300", marks[0].label)
    }

    @Test
    fun anEventWithoutATimeIsStampedWhenItArrives() {
        val r = Restarts(PhoenixSocket({ ServerAccess("http://server", "tok") }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val before = nowMs()
        r.onEvent("seat_restart", Json.parseToJsonElement("""{"session_id":1,"outcome":"restarted","origin":"auto"}""").jsonObject)
        assertTrue((r.events.value.single().atMs ?: 0) >= before)
        r.onEvent("seat_restart", Json.parseToJsonElement("""{"session_id":1,"outcome":"restarted","at":"2026-10-15T10:00:00Z"}""").jsonObject)
        assertEquals(parseServerTime("2026-10-15T10:00:00Z"), r.events.value.last().atMs, "the server's own time wins")
    }

    @Test
    fun aFailedAutoRestartSaysHowAndALeftStoppedOneSaysSo() {
        assertTrue(autoRestartLabel(event("""{"session_id":1,"outcome":"failed","reason":"x","reason_code":"not_restarted","origin":"auto"}""")).startsWith("Auto-restart stopped the seat"))
        assertEquals("Auto-restart failed: boom", autoRestartLabel(event("""{"session_id":1,"outcome":"failed","reason":"boom","origin":"auto"}""")))
    }
}
