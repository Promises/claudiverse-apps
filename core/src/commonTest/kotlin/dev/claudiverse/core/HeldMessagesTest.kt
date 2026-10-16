package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Messages the server holds for a busy seat for up to 15 s: they can be taken back until they go. */
@OptIn(ExperimentalCoroutinesApi::class)
class HeldMessagesTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val requests = mutableListOf<HttpRequestData>()

    private fun TestScope.api(reply: String, status: HttpStatusCode = HttpStatusCode.OK) = Api(
        { ServerAccess("http://server", "tok") },
        HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { r -> requests += r; respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        })),
    )

    private class MemoryStore : OutboxStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    @Test
    fun theHeldListReplacesTheLastAndIsNoTranscriptRow() {
        val feed = SeatFeed()
        feed.onEvent("replay", json("""{"messages":[],"held":[{"id":"h1","text":"one","held_at":"2026-10-16T08:00:00.000Z","deliver_at":"2026-10-16T08:00:15.000Z"}]}"""))
        assertEquals(listOf("h1"), feed.held.map { it.id })
        assertEquals(parseServerTime("2026-10-16T08:00:15.000Z"), feed.held.single().deliverAt)
        assertEquals(SeatFeed.Change.QUEUE, feed.onEvent("message", json("""{"type":"held_messages","held":[{"id":"h2","text":"two"}]}""")))
        assertEquals(listOf("h2"), feed.held.map { it.id }, "replaced, not merged")
        assertTrue(feed.transcript().items.isEmpty())
        feed.onEvent("replay", json("""{"messages":[]}"""))
        assertEquals(emptyList(), feed.held, "an older server holds nothing")
    }

    @Test
    fun heldRowsGoBetweenTheQueueAndSentAndShowOnce() {
        val rows = outboxView(
            held = listOf(QueuedMessage(1, 5, "app-held", 1)),
            queue = listOf(QueuedPrompt("q1", "queued", 1, QueuedPrompt.QUEUED)),
            sent = listOf(SentPrompt("h1", "held on server", false, 1), SentPrompt("s1", "just sent", false, 2)),
            serverHeld = listOf(HeldMessage("h1", "held on server", 1, 15_000)),
        )
        assertEquals(listOf("queued:q1", "server:h1", "sent:s1", "held:1"), rows.map { it.key })
        assertEquals("h1" to 15_000L, rows[1].messageId to rows[1].deliverAtMs)
        // Delivered: it moves from held to the queue and shows once, as queued.
        val moved = outboxView(emptyList(), listOf(QueuedPrompt("h1", "x", 1, QueuedPrompt.QUEUED)), emptyList(), listOf(HeldMessage("h1", "x", 1, 15_000)))
        assertEquals(listOf("queued:h1"), moved.map { it.key })
    }

    @Test
    fun aHeldRowCanBeTakenBackUntilItGoes() {
        val row = outboxView(emptyList(), emptyList(), emptyList(), listOf(HeldMessage("h1", "x", 0, 15_000))).single()
        assertTrue(cancellableNow(row, 14_999))
        assertFalse(cancellableNow(row, 15_000), "the trash greys at deliver_at, without waiting for the server")
        val queued = outboxView(emptyList(), listOf(QueuedPrompt("q", "x", 0, QueuedPrompt.QUEUED)), emptyList()).single()
        assertFalse(cancellableNow(queued, 0))
        assertTrue(cancellableNow(outboxView(listOf(QueuedMessage(1, 5, "x", 0)), emptyList(), emptyList()).single(), Long.MAX_VALUE))
    }

    @Test
    fun sendStillForcesAndAHeldReplyIsTrackedUntilListed() = runTest {
        val api = api("""{"status":"held","message_id":"h1","deliver_at":"2026-10-16T08:00:15.000Z"}""")
        assertEquals(parseServerTime("2026-10-16T08:00:15.000Z"), api.send(5, "x", force = true).deliverAtMs)
        val sends = Sends(api, Outbox(MemoryStore(), api, backgroundScope), 5, backgroundScope)
        assertNull(sends.send("busy seat"), "held is not a failure")
        assertTrue((requests.last().body as TextContent).text.contains("\"force\":true"), "the hold applies to a forced send only")
        assertEquals(listOf("h1"), sends.sent.value.map { it.messageId })
        sends.onQueue(emptyList(), listOf(HeldMessage("h1", "busy seat", null, null)))
        assertEquals(emptyList(), sends.sent.value, "the server's held list now shows it")
    }

    @Test
    fun cancelAndSendNowUseTheHeldCalls() = runTest {
        assertEquals("cancelled", api("""{"status":"cancelled"}""").cancelHeld("h1"))
        assertEquals(HttpMethod.Delete, requests.last().method)
        assertEquals("http://server/api/notify/held/h1", requests.last().url.toString())
        assertEquals("too_late", api("""{"status":"too_late"}""").cancelHeld("h1"))
        assertEquals("unknown", api("""{"error":"not found"}""", HttpStatusCode.NotFound).cancelHeld("h1"))
        val now = api("""{"status":"delivered","message_id":"h1"}""").sendHeldNow("h1")
        assertTrue(now.delivered)
        assertEquals("http://server/api/notify/held/h1/now", requests.last().url.toString())
        assertFalse(api("""{"status":"too_late"}""").sendHeldNow("h1").delivered)
    }

    /** too_late: interrupt only while it still waits in the queue; gone from it, it is the turn running now. */
    @Test
    fun aTooLateSendNowInterruptsOnlyWhileItWaitsInTheQueue() {
        val queue = listOf(QueuedPrompt("h1", "x", null, QueuedPrompt.QUEUED))
        assertTrue(interruptAfterTooLate("h1", queue))
        assertFalse(interruptAfterTooLate("h1", emptyList()), "the seat went idle and is running it")
        assertFalse(interruptAfterTooLate("h1", listOf(QueuedPrompt("other", "y", null, QueuedPrompt.QUEUED))))
    }
}
