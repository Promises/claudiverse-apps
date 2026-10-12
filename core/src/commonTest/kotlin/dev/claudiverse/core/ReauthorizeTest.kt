package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Re-authorizing a pool account: the new call, its refusals, and the fallback for a server without it. */
class ReauthorizeTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { request -> requests += request; handler(request) }))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    private val account = AnthropicAccount(
        id = 7, label = "Personal", priority = 20, status = "needs_reauth", cooldownUntil = null,
        cooldownReason = null, tokenValid = false, usageCheckedAt = null, limits = emptyList(), email = "me@example.com",
    )
    private val signIn = AccountSignIn("https://claude.example/oauth?x=1", "ver", "st")
    private val rendered = """{"id":7,"label":"Personal","priority":20,"status":"available","account_email":"me@example.com"}"""

    @Test
    fun aSignInBeginsWithTheAuthorizeCall() = runTest {
        val api = api { json("""{"url":"https://claude.example/oauth?x=1","verifier":"ver","state":"st"}""") }
        assertEquals(signIn, api.startAccountSignIn())
        assertEquals("http://server:4010/api/anthropic_auths/authorize", requests.single().url.toString())
    }

    @Test
    fun reauthorizingSendsThePasteAsIsAndKeepsTheAccount() = runTest {
        val api = api { json(rendered) }
        val result = api.reauthorize(account, signIn, "  abc#st \n")
        assertEquals("available", assertIs<Reauthorization.Done>(result).account?.status)
        val r = requests.single()
        assertEquals("http://server:4010/api/anthropic_auths/7/reauthorize/complete", r.url.toString())
        val body = r.jsonBody()
        assertEquals("abc#st", body["code"]?.jsonPrimitive?.content)
        assertEquals("ver", body["verifier"]?.jsonPrimitive?.content)
        assertEquals("st", body["state"]?.jsonPrimitive?.content)
        assertNull(body["force"], "force is only sent to replace a different account")
    }

    @Test
    fun aCodeForAnotherAccountChangesNothingAndNamesBoth() = runTest {
        val api = api {
            json(
                """{"error":"account_mismatch","stored_email":"me@example.com","signed_in_email":"other@example.com","detail":"This code signed in as other@example.com, not me@example.com."}""",
                HttpStatusCode.Conflict,
            )
        }
        val r = assertIs<Reauthorization.OtherAccount>(api.reauthorize(account, signIn, "abc#st"))
        assertEquals("me@example.com", r.stored)
        assertEquals("other@example.com", r.signedIn)
        assertTrue(r.message.startsWith("This code signed in as"))
        assertEquals(1, requests.size, "a refusal is not retried, and nothing falls back")
    }

    @Test
    fun replacingAnywaySendsForce() = runTest {
        val api = api { json(rendered) }
        api.reauthorize(account, signIn, "abc#st", force = true)
        assertEquals("true", requests.single().jsonBody()["force"]?.jsonPrimitive?.content)
    }

    @Test
    fun aServerWithoutTheCallEnrolsAgainUnderTheSameLabelAndPriority() = runTest {
        val api = api { r ->
            if (r.url.encodedPath.endsWith("/reauthorize/complete")) json("""{"errors":{"detail":"Not Found"}}""", HttpStatusCode.NotFound)
            else json(rendered)
        }
        assertIs<Reauthorization.Done>(api.reauthorize(account, signIn, "abc#st"))
        val enrol = requests.last()
        assertEquals("http://server:4010/api/anthropic_auths/complete", enrol.url.toString())
        val body = enrol.jsonBody()
        assertEquals("Personal", body["label"]?.jsonPrimitive?.content)
        assertEquals("20", body["priority"]?.jsonPrimitive?.content, "without it the account would drop to priority 100")
        assertEquals("abc#st", body["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun anUnknownAccountIsNotEnrolledAgain() = runTest {
        val api = api { json("""{"error":"unknown auth"}""", HttpStatusCode.NotFound) }
        assertFailsWith<ApiException> { api.reauthorize(account, signIn, "abc#st") }
        assertEquals(1, requests.size, "a deleted account must not be re-created through /complete")
    }

    @Test
    fun aFailedExchangeSaysWhy() = runTest {
        val api = api { json("""{"error":"state_mismatch","detail":"the pasted code did not match this attempt"}""", HttpStatusCode.BadRequest) }
        val e = assertFailsWith<ApiException> { api.reauthorize(account, signIn, "abc#zz") }
        assertEquals("HTTP 400: the pasted code did not match this attempt", e.message)
    }

    @Test
    fun accountsCarryTheirEmailAndWhyTheyNeedReauth() {
        val a = AnthropicAccount.from(
            Json.parseToJsonElement("""{"id":7,"label":"Personal","status":"needs_reauth","account_email":"me@example.com","reauth_reason":"refresh rejected with HTTP 400"}""").jsonObject,
        )
        assertTrue(a.needsReauth)
        assertEquals("me@example.com", a.email)
        assertEquals("refresh rejected with HTTP 400", a.reauthReason)
    }

    @Test
    fun reauthEventsNameTheirAccountAndUnknownChangesStillParse() {
        fun change(s: String) = PoolChange.from(Json.parseToJsonElement(s).jsonObject)
        val needs = change("""{"change":"needs_reauth","auth_id":7,"label":"Personal","reason":"refresh rejected with HTTP 400","text":"Personal needs re-auth","at":"2026-10-12T05:51:00Z"}""")
        assertEquals(7L, needs.accountId)
        assertEquals("Personal", needs.label)
        assertEquals("refresh rejected with HTTP 400", needs.reason)
        assertNull(needs.currentId)
        for (c in listOf("preferred", "upgrade", "degrade", "cooling", "reset", "capped", "step", "reauthorized", "something_new")) {
            assertEquals(c, change("""{"change":"$c","extra":{"nested":[1,2]},"limit":90}""").change)
        }
        assertEquals("unknown", change("""{"label":"Personal"}""").change)
    }
}
