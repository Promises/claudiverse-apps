package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A seat's background tasks (format v1): the whole list each time, apart from the transcript. */
class BackgroundTasksTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val shell = """{"id":"b3q8nagfw","type":"shell","status":"running","command":"sleep 40 && echo slept","started_at":"2026-10-09T14:11:25.585Z"}"""

    @Test
    fun eachListReplacesTheLastAndIsNoTranscriptRow() {
        val feed = SeatFeed()
        assertEquals(SeatFeed.Change.TRANSCRIPT, feed.onEvent("message", json("""{"type":"background_tasks","timestamp":"2026-10-09T14:11:26Z","tasks":[$shell]}""")))
        val t = feed.backgroundTasks.single()
        assertEquals("sleep 40 && echo slept", t.label)
        assertTrue(t.isRunning)
        assertEquals(parseServerTime("2026-10-09T14:11:25.585Z"), t.startedAt)
        assertTrue(feed.transcript().items.isEmpty())
        feed.onEvent("message", json("""{"type":"background_tasks","tasks":[]}"""))
        assertTrue(feed.backgroundTasks.isEmpty())
    }

    @Test
    fun aReplayCarriesTheLatestListAndAnOlderOneNone() {
        val feed = SeatFeed()
        feed.onEvent("replay", json("""{"messages":[],"background_tasks":[$shell,
            {"id":"a1","type":"subagent","status":"pending","agent_type":"Explore","description":"Find it"},
            {"id":"w1","type":"workflow","status":"running","name":"review"},
            {"id":"m1","type":"monitor","status":"running","server":"github","tool":"watch"}]}"""))
        assertEquals(listOf("b3q8nagfw", "w1", "m1"), BackgroundTask.shown(feed.backgroundTasks).map { it.id })
        assertEquals(listOf("Find it", "review", "github · watch"), feed.backgroundTasks.drop(1).map { it.label })
        assertEquals("Explore", feed.backgroundTasks[1].agentType)
        // A described shell is called by its description; its command stays to show apart.
        val described = BackgroundTask.from(json("""{"id":"b","type":"shell","status":"running","description":"Wait for the build","command":"sleep 9"}"""))
        assertEquals("Wait for the build", described.label)
        assertEquals("sleep 9", described.command)
        feed.onEvent("replay", json("""{"messages":[]}"""))
        assertTrue(feed.backgroundTasks.isEmpty())
    }

    @Test
    fun theSeatListCountsWhatRunsAndElapsedReads() {
        val row = Seat.from(json("""{"id":1,"title":"t","status":"running","background_running":2}"""))
        assertEquals(2, row.backgroundRunning)
        assertEquals(0, Seat.from(json("""{"id":1,"title":"t","status":"running"}""")).backgroundRunning)
        assertEquals("45 s", elapsedLabel(0, 45_000))
        assertEquals("12 min", elapsedLabel(0, 12 * 60_000 + 5_000))
        assertEquals("3 h 5 min", elapsedLabel(0, (3 * 3600 + 5 * 60) * 1000L))
        assertEquals("2 h", elapsedLabel(0, 2 * 3600 * 1000L))
    }
}
