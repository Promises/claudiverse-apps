package dev.claudiverse.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A channel's events reach a subscriber that is behind, every one: a busy
 * seat's burst outran a 256-event buffer and the rest were dropped in silence,
 * the replay among them, and a seat showed nothing (2026-11-06).
 */
class ChannelDeliveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val socket = PhoenixSocket({ ServerAccess(baseUrl = "", token = "") }, scope)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun aSubscriberThatIsBehindMissesNothing() = runBlocking {
        val channel = socket.channel("session:9")
        val gate = CompletableDeferred<Unit>()
        val got = mutableListOf<String>()
        val done = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.events.take(1000).collect { e ->
                gate.await()
                got += e.event
            }
        }
        // The subscriber holds the first event until all 1000 are delivered.
        repeat(999) { channel.deliver("message", JsonObject(emptyMap()), null) }
        channel.deliver("replay", JsonObject(emptyMap()), null)
        gate.complete(Unit)
        withTimeout(5_000) { done.join() }
        assertEquals(1000, got.size)
        assertEquals("replay", got.last(), "the last one, the replay, is not the one dropped")
    }
}
