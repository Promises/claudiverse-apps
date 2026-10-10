package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {

    private class MemoryStore(var json: String? = null) : OutboxStore {
        override fun load() = json
        override fun save(json: String) {
            this.json = json
        }
    }

    /** A mock server on the test's clock: replies land within the virtual time the test advances. */
    private fun TestScope.api(handler: MockRequestHandler) = Api(
        { ServerAccess("http://server", "tok") },
        HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = UnconfinedTestDispatcher(testScheduler); addHandler(handler) })),
    )

    /** What the Android app stored before the move to core, as org.json wrote it. */
    @Test
    fun aQueueStoredByTheOldAppReadsBack() {
        val stored = """[{"id":1,"seat":204013,"text":"hello \"seat\"","at":1759600000000},{"id":2,"seat":7,"text":"two","at":1759600001000}]"""
        assertEquals(
            listOf(QueuedMessage(1, 204013, "hello \"seat\"", 1759600000000), QueuedMessage(2, 7, "two", 1759600001000)),
            Outbox.decode(stored),
        )
    }

    @Test
    fun encodingRoundTrips() {
        val list = listOf(QueuedMessage(3, 9, "line one\nline two", 42))
        assertEquals(list, Outbox.decode(Outbox.encode(list)))
    }

    /** One unreadable row is dropped; the rest of the queue survives. */
    @Test
    fun anUnreadableRowDoesNotLoseTheQueue() {
        assertEquals(listOf(QueuedMessage(2, 7, "ok", 0)), Outbox.decode("""[{"id":1},{"id":2,"seat":7,"text":"ok"}]"""))
        assertEquals(emptyList(), Outbox.decode("not json"))
        assertEquals(emptyList(), Outbox.decode(null))
    }

    /** Busy: held. Idle: sent once and removed, and the store follows. */
    @Test
    fun aMessageWaitsForIdleThenSendsOnce() = runTest {
        var idle = false
        val sends = mutableListOf<String>()
        val api = api { request ->
            val body = when {
                request.method == HttpMethod.Get -> """{"session":{"id":1,"title":"s","status":"running"},"live":{"idle":$idle}}"""
                else -> {
                    sends += request.url.encodedPath
                    """{"status":"delivered"}"""
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val store = MemoryStore()
        val outbox = Outbox(store, api, backgroundScope)

        outbox.enqueue(1, "queued while busy")
        runCurrent()
        advanceTimeBy(Outbox.POLL_MS * 2)
        assertTrue(sends.isEmpty())
        assertTrue(store.json!!.contains("queued while busy"))

        idle = true
        advanceTimeBy(Outbox.POLL_MS + 1)
        assertEquals(listOf("/api/notify/send"), sends)
        assertEquals(emptyList(), outbox.items.value)
        assertEquals("[]", store.json)
    }

    /** A stopped seat never goes idle: its message stays, and nothing is sent to it. */
    @Test
    fun nothingIsSentToAStoppedSeat() = runTest {
        var sent = 0
        val api = api { request ->
            if (request.method != HttpMethod.Get) sent++
            respond(
                """{"session":{"id":1,"title":"s","status":"stopped"},"live":{"idle":true}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val outbox = Outbox(MemoryStore(), api, backgroundScope)
        outbox.enqueue(1, "too late")
        advanceTimeBy(Outbox.POLL_MS * 3)
        assertEquals(0, sent)
        assertEquals(1, outbox.items.value.size)
    }
}
