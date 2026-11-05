package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A request the server never answers fails within the request timeout rather
 * than hanging: the Mac's seat-list poll waits for each request, and one stuck
 * on a dead connection froze the list for five hours (2026-11-05).
 */
class RequestTimeoutTest {
    @Test
    fun aRequestThatNeverAnswersFails() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { never.await(); respond("{}") }) { timeouts(requestMs = 300) }
        val api = Api({ ServerAccess("http://server:4010", "tok") }, client)
        // The client's own timeout, not this outer one: a hang ends here with a different exception and fails the test.
        withTimeout(5_000) { assertFailsWith<HttpRequestTimeoutException> { api.seats(includeStopped = false) } }
        Unit
    }
}
