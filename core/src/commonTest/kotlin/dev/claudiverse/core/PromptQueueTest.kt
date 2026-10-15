package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The send model of claudiverse-seat 0.12.0: Send and Send now, Claude's queue, and the Outbox panel. */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptQueueTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private class MemoryStore : OutboxStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) {
            this.json = json
        }
    }

    private fun TestScope.api(handler: MockRequestHandler) = Api(
        { ServerAccess("http://server", "tok") },
        HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = UnconfinedTestDispatcher(testScheduler); addHandler(handler) })),
    )

    private val bodies = mutableListOf<JsonObject>()

    private fun TestScope.sendingApi(reply: String) = api { request ->
        if (request.method == HttpMethod.Post) bodies += json((request.body as TextContent).text)
        respond(reply, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun sendForcesAndSendNowAlsoInterrupts() = runTest {
        val api = sendingApi("""{"status":"delivered","message_id":"m-7"}""")
        assertEquals("m-7", api.send(5, "hi", force = true).messageId)
        api.send(5, "now", interrupt = true)
        api.send(5, "plain")
        assertEquals("true", bodies[0]["force"]?.jsonPrimitive?.content)
        assertNull(bodies[0]["interrupt"])
        assertEquals("true", bodies[1]["force"]?.jsonPrimitive?.content, "interrupt implies force")
        assertEquals("true", bodies[1]["interrupt"]?.jsonPrimitive?.content)
        assertNull(bodies[2]["force"])
        assertNull(bodies[2]["interrupt"])
    }

    @Test
    fun anOlderServerSendsNoMessageId() = runTest {
        assertNull(sendingApi("""{"status":"delivered"}""").send(5, "hi", force = true).messageId)
    }

    @Test
    fun eachQueueReplacesTheLastAndIsNoTranscriptRow() {
        val feed = SeatFeed()
        feed.onEvent("replay", json("""{"messages":[],"prompt_queue":[{"id":"a","text":"one","queued_at":"2026-10-15T10:00:00Z","state":"queued"}]}"""))
        assertEquals(listOf("a"), feed.promptQueue.map { it.id })

        val change = feed.onEvent("message", json("""{"type":"prompt_queue","queue":[{"id":"b","text":"two","state":"interrupting"},{"id":"c","text":"three","state":"queued"}],"timestamp":"2026-10-15T10:00:01Z"}"""))
        assertEquals(SeatFeed.Change.QUEUE, change)
        assertEquals(listOf("b", "c"), feed.promptQueue.map { it.id }, "replaced, not merged: a is gone")
        assertTrue(feed.promptQueue[0].interrupting)
        assertTrue(feed.transcript().items.isEmpty(), "the queue is never a transcript row")

        feed.onEvent("message", json("""{"type":"prompt_queue","queue":[]}"""))
        assertEquals(emptyList(), feed.promptQueue, "an empty list empties the queue")
    }

    @Test
    fun aReplayFromAnOlderModHasNoQueueAndOddEntriesAreRead() {
        val feed = SeatFeed()
        feed.onEvent("message", json("""{"type":"prompt_queue","queue":[{"id":"x","text":"t"}]}"""))
        feed.onEvent("replay", json("""{"messages":[]}"""))
        assertEquals(emptyList(), feed.promptQueue)
        val q = QueuedPrompt.list(Json.parseToJsonElement("""[{"text":"no id"},{"id":42,"text":"numeric id","state":"later"}]""").let { it as kotlinx.serialization.json.JsonArray })
        assertEquals(listOf(QueuedPrompt("42", "numeric id", null, QueuedPrompt.QUEUED)), q)
    }

    @Test
    fun theOutboxListsTheQueueThenSentThenHeld() {
        val queue = listOf(QueuedPrompt("m1", "first", 1, QueuedPrompt.INTERRUPTING), QueuedPrompt("seat-9", "typed at the seat", 2, QueuedPrompt.QUEUED))
        val sent = listOf(SentPrompt("m1", "first", interrupt = true, sentAt = 1), SentPrompt("m2", "second", interrupt = false, sentAt = 3))
        val held = listOf(QueuedMessage(8, 5, "later", 20), QueuedMessage(7, 5, "earlier", 10, interrupt = true))
        val rows = outboxView(held, queue, sent)

        assertEquals(listOf("queued:m1", "queued:seat-9", "sent:m2", "held:7", "held:8"), rows.map { it.key }, "m1 shows once, as the seat's")
        assertEquals(listOf(OutboxRow.INTERRUPTING, OutboxRow.IN_QUEUE, OutboxRow.SENDING, OutboxRow.HELD_INTERRUPT, OutboxRow.HELD), rows.map { it.label })
        assertEquals(listOf(false, false, false, true, true), rows.map { it.cancellable }, "only what this app holds can be cancelled")
        assertEquals(7L, rows[3].heldId)
    }

    /** A delivered send shows as sent until the seat lists its message_id, then only as the seat's. */
    @Test
    fun aSentMessageIsMatchedByItsIdAndExpiresWhenNeverListed() = runTest {
        val outbox = Outbox(MemoryStore(), sendingApi("""{"status":"delivered","message_id":"m1"}"""), backgroundScope)
        val sends = Sends(sendingApi("""{"status":"delivered","message_id":"m1"}"""), outbox, 5, backgroundScope)

        assertNull(sends.send("hello"))
        assertEquals(listOf("m1"), sends.sent.value.map { it.messageId })
        sends.onQueue(listOf(QueuedPrompt("other", "x", null, QueuedPrompt.QUEUED)))
        assertEquals(listOf("m1"), sends.sent.value.map { it.messageId }, "another id leaves it")
        sends.onQueue(listOf(QueuedPrompt("m1", "hello", null, QueuedPrompt.QUEUED)))
        assertEquals(emptyList(), sends.sent.value)

        assertNull(sends.send("never listed"))
        advanceTimeBy(Sends.SENT_GRACE_MS + 1)
        assertEquals(emptyList(), sends.sent.value)
    }

    /** An idle seat starts a sent message at once: once the transcript shows it, it is no longer "sent". */
    @Test
    fun aSentMessageTheTranscriptShowsIsNoLongerSent() = runTest {
        val api = sendingApi("""{"status":"delivered","message_id":"m1"}""")
        val sends = Sends(api, Outbox(MemoryStore(), api, backgroundScope), 5, backgroundScope)
        sends.send("hello there")
        sends.onTranscript(listOf(TranscriptItem.UserText("u0", "something else", injected = false)))
        assertEquals(listOf("m1"), sends.sent.value.map { it.messageId })
        sends.onTranscript(listOf(TranscriptItem.UserText("u1", "hello there\n", injected = false)))
        assertEquals(emptyList(), sends.sent.value)
    }

    /** Offline: held, as a Send now when it was one. Behind a held message: held too, so order holds. */
    @Test
    fun aSeatThatCannotTakeItHoldsItInOrder() = runTest {
        val api = sendingApi("""{"status":"error","error":"not connected"}""")
        val outbox = Outbox(MemoryStore(), api, backgroundScope)
        val sends = Sends(api, outbox, 5, backgroundScope)

        assertTrue(sends.send("one", interrupt = true)!!.startsWith(Sends.HELD_OFFLINE))
        assertEquals(1, bodies.size)
        assertEquals(Sends.BEHIND_HELD, sends.send("two"))
        assertEquals(1, bodies.size, "nothing jumps the held one")
        assertEquals(listOf("one" to true, "two" to false), outbox.items.value.map { it.text to it.interrupt })
    }
}
