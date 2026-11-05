package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Restart anyway": only for a seat that is merely not idle; everything else stays refused. */
class ForceRestartTest {
    private val FORCE = """"force":true"""

    @Test
    fun onlyTheIdleChecksCanBeForced() {
        for (code in listOf("busy", "background", "permission", "question", "recent_activity")) {
            assertTrue(RestartRefused("x", code).canForce, code)
        }
        for (code in listOf("not_running", "offline", "runner", "self", "no_machine", "fleet", "excluded", "paused", "protected", "up_to_date")) {
            assertFalse(RestartRefused("x", code).canForce, code)
        }
        // An unknown or missing code never gets a "Restart anyway".
        assertFalse(RestartRefused("x", "something_new").canForce)
        assertFalse(RestartRefused("x", null).canForce)
    }

    @Test
    fun theConfirmSaysWhatIsLost() {
        assertEquals(
            "vm_management-c579 has 1 background task(s) running. Restart anyway? Its running work is stopped; the conversation is kept.",
            RestartRefused("vm_management-c579 has 1 background task(s) running.", "background").forcePrompt,
        )
        assertEquals("The seat is busy. Restart anyway? Its running work is stopped; the conversation is kept.", RestartRefused("", "busy").forcePrompt)
    }

    /** The plan's 409 is a refusal with its code (the operator saw "Not planned: HTTP 409"); a forced restart sends force. */
    @Test
    fun thePlansRefusalCarriesItsCodeAndForceIsSent() = runTest {
        val bodies = mutableListOf<String>()
        val api = Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { r ->
            val body = (r.body as TextContent).text
            bodies += body
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (FORCE in body) respond("""{"restart_id":"r9","seat":{"action":"restart","force":true}}""", HttpStatusCode.Accepted, json)
            else respond("""{"error":"vm_management-c579 has 1 background task(s) running","reason_code":"background"}""", HttpStatusCode.Conflict, json)
        }))
        val refused = assertFailsWith<RestartRefused> { api.planSeatRestart(7) }
        assertEquals("background", refused.reasonCode)
        assertTrue(refused.canForce)
        assertEquals("r9", api.restartSeat(7, force = true))
        assertEquals("""{"force":true}""", bodies.last())
        assertFalse("force" in bodies.first(), "a plan never forces")
    }
}
