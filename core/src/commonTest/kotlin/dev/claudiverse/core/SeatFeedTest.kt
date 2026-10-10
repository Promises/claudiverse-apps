package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeatFeedTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun assistant(uuid: String, text: String, effort: String? = null) = json(
        """{"type":"assistant","uuid":"$uuid"${effort?.let { ",\"effort\":\"$it\"" } ?: ""},
           "message":{"content":[{"type":"text","text":"$text"}]}}""",
    )

    private fun replay(vararg messages: JsonObject) = JsonObject(mapOf("messages" to JsonArray(messages.toList())))

    private fun texts(feed: SeatFeed) =
        feed.transcript().items.filterIsInstance<TranscriptItem.AssistantText>().map { it.text }

    /** A message seen in the replay and again live replaces itself rather than showing twice. */
    @Test
    fun aRepeatedMessageReplacesItself() {
        val feed = SeatFeed()
        feed.onEvent("replay", replay(assistant("u1", "hi")))
        feed.onEvent("message", assistant("u1", "hi"))
        assertEquals(listOf("hi"), texts(feed))
    }

    @Test
    fun aRejoinReplayReplacesTheTranscript() {
        val feed = SeatFeed()
        feed.onEvent("message", assistant("u1", "old"))
        feed.onEvent("replay", replay(assistant("u2", "new")))
        assertEquals(listOf("new"), texts(feed))
    }

    @Test
    fun streamingTextGrowsAndClearsWhenTheMessageLands() {
        val feed = SeatFeed()
        val delta = { t: String -> json("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"$t"}}}""") }
        assertEquals(SeatFeed.Change.STREAMING, feed.onEvent("message", delta("Hel")))
        feed.onEvent("message", delta("lo"))
        assertEquals("Hello", feed.streaming)
        assertEquals(SeatFeed.Change.TRANSCRIPT, feed.onEvent("message", assistant("u1", "Hello")))
        assertEquals("", feed.streaming)
    }

    @Test
    fun aLivePermissionIsPendingUntilResolved() {
        val feed = SeatFeed()
        feed.onEvent("permission_request", json("""{"request_id":"r1","tool_name":"Bash","input":{"command":"ls"}}"""))
        val pending = feed.transcript().items.single() as TranscriptItem.Permission
        assertTrue(pending.pending)
        feed.onEvent("permission_resolved", json("""{"request_id":"r1"}"""))
        assertTrue(!(feed.transcript().items.single() as TranscriptItem.Permission).pending)
    }

    /** The effort shown is the latest reply's, and it holds when later replies carry none. */
    @Test
    fun effortIsTheLatestReported() {
        val feed = SeatFeed()
        feed.onEvent("message", assistant("u1", "a", effort = "high"))
        assertEquals("high", feed.transcript().effort)
        feed.onEvent("message", assistant("u2", "b", effort = "low"))
        assertEquals("low", feed.transcript().effort)
    }

    /**
     * The Resumed line follows a resumed seat's earlier conversation. A stored
     * operator prompt has no uuid: it must still count as earlier, or the line
     * lands above it (seen live on the kiwi seat, 204027).
     */
    @Test
    fun theResumedLineFollowsTheEarlierConversationIncludingPromptsWithoutUuid() {
        val feed = SeatFeed()
        val prompt = json("""{"type":"user","message":{"content":"What single word did you reply with earlier?"}}""")
        val payload = JsonObject(
            mapOf(
                "earlier_count" to JsonPrimitive(3),
                "messages" to JsonArray(listOf(assistant("u1", "a"), prompt, assistant("u2", "b"), assistant("u3", "c"))),
            ),
        )
        feed.onEvent("replay", payload)
        val shape = feed.transcript().rows.joinToString("") {
            when (it) {
                is TimelineRow.Divider -> "D"
                TimelineRow.Resumed -> "R"
                is TimelineRow.Entry -> "E"
            }
        }
        assertEquals("EEERE", shape)
    }

    @Test
    fun aSeatThatWasNeverResumedHasNoLine() {
        val feed = SeatFeed()
        feed.onEvent("replay", replay(assistant("u1", "a"), assistant("u2", "b")))
        assertTrue(feed.transcript().rows.none { it == TimelineRow.Resumed })
    }

    @Test
    fun oldestMessagesDropPastTheCap() {
        val feed = SeatFeed(maxMessages = 2)
        listOf("a", "b", "c").forEachIndexed { i, t -> feed.onEvent("message", assistant("u$i", t)) }
        assertEquals(listOf("b", "c"), texts(feed))
    }
}
