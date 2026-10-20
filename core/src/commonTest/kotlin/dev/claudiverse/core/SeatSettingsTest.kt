package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A seat's accept-all switch: GET and PUT /api/seat_settings/:title, as the server answers them (68c4dd5). */
class SeatSettingsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>) =
        Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { r ->
            val (status, body) = handler(r)
            respond(body, status, json)
        }))

    @Test
    fun readsAndSetsBySeatTitle() = runTest {
        val seen = mutableListOf<String>()
        val a = api { r ->
            seen += "${r.method.value} ${r.url.encodedPath} ${(r.body as? TextContent)?.text ?: ""}".trim()
            HttpStatusCode.OK to """{"title":"lorem admin","accept_all":${r.method.value == "PUT"}}"""
        }
        assertEquals(SeatSetting("lorem admin", false), a.seatSetting("lorem admin"))
        assertEquals(SeatSetting("lorem admin", true), a.setAcceptAll("lorem admin", true))
        // The title is one path segment, encoded; the body carries only the switch.
        assertEquals(listOf("GET /api/seat_settings/lorem%20admin", """PUT /api/seat_settings/lorem%20admin {"accept_all":true}"""), seen)
    }

    @Test
    fun listsOnlyTheSeatsWithItOn() = runTest {
        val a = api { HttpStatusCode.OK to """{"seats":[{"title":"ipsum-w1","accept_all":true}]}""" }
        assertEquals(listOf(SeatSetting("ipsum-w1", true)), a.seatSettings())
    }

    /** An older server has no seat settings: null, so the apps hide the switch rather than show one that cannot be set. */
    @Test
    fun anOlderServerHasNoSwitch() = runTest {
        assertNull(api { HttpStatusCode.NotFound to """{"errors":{"detail":"Not Found"}}""" }.seatSetting("ipsum-w1"))
    }

    /** Only an app or super sign-in may change it; the server's own sentence comes back. */
    @Test
    fun aSeatTokenMayNotChangeIt() = runTest {
        val e = assertFailsWith<ApiException> {
            api { HttpStatusCode.Forbidden to """{"error":"changing a seat's settings is app-only"}""" }.setAcceptAll("ipsum-w1", true)
        }
        assertEquals(403, e.httpStatus)
        assertTrue(e.message!!.contains("app-only"))
        assertTrue(Me("", "app").canChangeSettings)
        assertTrue(Me("", "super").canChangeSettings)
        assertFalse(Me("", "api").canChangeSettings)
        assertFalse(Me("", null).canChangeSettings)
    }
}
