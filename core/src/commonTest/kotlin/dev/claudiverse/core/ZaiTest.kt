package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Z.ai Coding Plan quota on the Pool screen: what the server lists, and how a window reads. */
class ZaiTest {

    private val requests = mutableListOf<HttpRequestData>()
    private fun api(reply: String, status: HttpStatusCode = HttpStatusCode.OK) = Api(
        { ServerAccess("http://server", "tok") },
        HttpClient(MockEngine { r -> requests += r; respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json")) }),
    )

    private val live = """{"keys":[{"id":1,"label":"coding plan","status":"ok","level":"pro","checked_at":"2026-11-01T15:55:25Z","last_error":null,
        "windows":[{"limit":12000,"percent":0,"remaining":12000,"resets_at":null,"used":0,"window":"5h"},
                   {"limit":60000,"percent":100,"remaining":0,"resets_at":"2026-11-02T08:08:37Z","used":60078,"window":"week"}]}]}"""

    @Test
    fun keysAndWindowsAreRead() = runTest {
        val k = api(live).zaiKeys()!!.single()
        assertEquals("coding plan" to "pro", k.label to k.level)
        assertTrue(k.ok)
        assertEquals(listOf("5 hours", "Week"), k.windows.map(::zaiWindowLabel))
        assertEquals(listOf(false, true), k.windows.map { it.spent })
        assertNull(api("""{"errors":{"detail":"Not Found"}}""", HttpStatusCode.NotFound).zaiKeys(), "an older server has no Z.ai")
    }

    @Test
    fun aSpentWindowSaysUntilWhen() {
        val utc = TimeZone.UTC
        val now = parseServerTime("2026-11-01T16:00:00Z")!!
        val (fiveH, week) = runCatching { kotlinx.coroutines.runBlocking { api(live).zaiKeys()!!.single().windows } }.getOrThrow()
        assertEquals("0 of 12000 credits", zaiWindowLine(fiveH, now, utc), "unused, no reset: just the credits")
        assertEquals("60078 of 60000 credits · spent until tomorrow 08:08", zaiWindowLine(week, now, utc))
        val notSpent = week.copy(used = 100, remaining = 59900, percent = 0)
        assertEquals("100 of 60000 credits · resets tomorrow 08:08", zaiWindowLine(notSpent, now, utc))
        assertEquals("Wed 08:08", resetTime(parseServerTime("2026-11-04T08:08:00Z")!!, now, utc))
        assertEquals("12 Nov 08:08", resetTime(parseServerTime("2026-11-12T08:08:00Z")!!, now, utc))
        assertEquals("spent until it resets", zaiWindowLine(week.copy(resetsAt = null), now, utc).substringAfter(" · "))
    }

    @Test
    fun addCheckAndRemoveUseTheKeyCalls() = runTest {
        api("""{"id":2,"label":"second","status":"ok","windows":[]}""").addZaiKey("second", "secret-key")
        assertEquals("http://server/api/zai/keys", requests.last().url.toString())
        api("""{"id":2,"label":"second","status":"ok","windows":[]}""").checkZaiKey(2)
        assertEquals("http://server/api/zai/keys/2/check", requests.last().url.toString())
        api("", HttpStatusCode.NoContent).deleteZaiKey(2)
        assertEquals(HttpMethod.Delete, requests.last().method)
        val e = assertFailsWith<ApiException> { api("""{"error":"Z.ai refused the key (401)"}""", HttpStatusCode.UnprocessableEntity).addZaiKey("x", "bad") }
        assertTrue(e.message!!.contains("Z.ai refused the key"), e.message)
        assertTrue(e.message!!.contains("bad").not(), "the key never appears in an error")
    }
}
