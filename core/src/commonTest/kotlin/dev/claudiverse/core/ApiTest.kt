package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(
        access: ServerAccess = ServerAccess("http://server:4010", "tok"),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = Api({ access }, HttpClient(MockEngine { request -> requests += request; handler(request) }))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, extra: Pair<String, String>? = null) =
        respond(
            body,
            status,
            if (extra == null) headersOf(HttpHeaders.ContentType, "application/json")
            else headersOf(HttpHeaders.ContentType to listOf("application/json"), extra.first to listOf(extra.second)),
        )

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun signInPostsTheCredentialsWithoutABearerToken() = runTest {
        val api = api(ServerAccess("http://server:4010", "")) {
            json("""{"token":"new","scope":"super","user":{"id":1,"email":"op@example.com"}}""")
        }
        val signIn = api.signIn("op@example.com", "pw", client = "studio desktop")
        assertEquals(SignIn("new", "super", "op@example.com"), signIn)
        val r = requests.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://server:4010/api/auth/login", r.url.toString())
        assertNull(r.headers[HttpHeaders.Authorization])
        val body = r.jsonBody()
        assertEquals("super", body["scope"]?.jsonPrimitive?.content)
        assertEquals("studio desktop", body["client"]?.jsonPrimitive?.content)
    }

    /** Too many failed sign-ins: the wait the server asks for reaches the caller. */
    @Test
    fun aRateLimitedSignInCarriesRetryAfter() = runTest {
        val api = api(ServerAccess("http://server:4010", "")) {
            json("""{"error":"too many attempts","retry_after":42}""", HttpStatusCode.TooManyRequests)
        }
        val e = assertFailsWith<ApiException> { api.signIn("a", "b", "c") }
        assertEquals(429, e.httpStatus)
        assertEquals(42L, e.retryAfterSeconds)
        assertTrue("too many attempts" in e.message.orEmpty())
    }

    @Test
    fun retryAfterFallsBackToTheHeader() = runTest {
        val api = api { json("""{"error":"slow down"}""", HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "7") }
        assertEquals(7L, assertFailsWith<ApiException> { api.me() }.retryAfterSeconds)
    }

    @Test
    fun signedInCallsSendTheBearerToken() = runTest {
        val api = api { json("""{"user":{"email":"op@example.com"},"scope":"super"}""") }
        assertEquals(Me("op@example.com", "super"), api.me())
        assertEquals("Bearer tok", requests.single().headers[HttpHeaders.Authorization])
    }

    /** No token: refused before anything is sent. */
    @Test
    fun withoutATokenNothingIsSent() = runTest {
        val api = api(ServerAccess("http://server:4010", "")) { json("{}") }
        assertFailsWith<ApiException> { api.seats() }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aServerUrlThatIsNotHttpIsRefused() = runTest {
        val api = api(ServerAccess("ftp://server", "tok")) { json("{}") }
        assertFailsWith<ApiException> { api.seats() }
        assertTrue(requests.isEmpty())
    }

    /** A machine or channel name is one path segment, however it is spelled. */
    @Test
    fun namesAreEncodedAsOneSegment() = runTest {
        val api = api { json("""{"path":"/a b","parent":null,"dirs":[],"truncated":false}""") }
        api.listDir("odd/host name", "/a b")
        val url = requests.single().url
        assertEquals(listOf("api", "hosts", "odd/host name", "ls"), url.segments)
        assertTrue(url.encodedPath.contains("odd%2Fhost%20name"), url.encodedPath)
        assertEquals("/a b", url.parameters["path"])
    }

    @Test
    fun listEndpointsTakeABareArrayOrAnObject() = runTest {
        val bare = api { json("""[{"name":"studio","online":true,"seats":2,"roots":["/Users/me"],"remote_roots":true,"version":"0.3"}]""") }
        assertEquals(listOf(Host("studio", true, 2, listOf("/Users/me"), remoteRoots = true, version = "0.3")), bare.hosts())
        val wrapped = api { json("""{"hosts":[{"name":"laptop","online":false,"seats":0,"roots":[]}]}""") }
        assertEquals("laptop", wrapped.hosts().single().name)
    }

    @Test
    fun theServersErrorTextReachesTheCaller() = runTest {
        val api = api { json("""{"error":"its folder is outside the whitelist"}""", HttpStatusCode.Forbidden) }
        val e = assertFailsWith<ApiException> { api.stopSeat(5) }
        assertEquals(403, e.httpStatus)
        assertEquals("HTTP 403: its folder is outside the whitelist", e.message)
    }

    @Test
    fun noPublishedBuildIsNullNotAnError() = runTest {
        val api = api { json("""{"error":"not found"}""", HttpStatusCode.NotFound) }
        assertNull(api.latestBuild())
    }

    @Test
    fun removingARootSendsThePathInADeleteBody() = runTest {
        val api = api { json("""{"roots":["/Users/me"]}""") }
        assertEquals(listOf("/Users/me"), api.removeRoot("studio", "/Users/me/old"))
        val r = requests.single()
        assertEquals(HttpMethod.Delete, r.method)
        assertEquals("/Users/me/old", r.jsonBody()["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun theTokenListReadsHostTokens() = runTest {
        val api = api {
            json("""{"tokens":[{"id":3,"context":"host:laptop","label":null,"inserted_at":"2026-10-04T20:00:00Z","current":false},
                               {"id":4,"context":"super","label":"studio desktop","inserted_at":null,"current":true}]}""")
        }
        val tokens = api.tokens()
        assertEquals("laptop", tokens[0].host)
        assertNull(tokens[1].host)
        assertTrue(tokens[1].current)
    }

    @Test
    fun aSeatWithoutAnIdFailsTheReadRatherThanInventingOne() = runTest {
        val api = api { json("""{"sessions":[{"title":"no id"}]}""") }
        assertFailsWith<ApiException> { api.seats() }
    }

    /** The operator's choice of account (server: POST /api/anthropic_auths/:id/preferred). */
    @Test
    fun makingAnAccountPreferredAnswersTheNewOrder() = runTest {
        val api = api {
            json("""{"auths":[{"id":2,"label":"Work","priority":10,"status":"cooling_down"},{"id":1,"label":"Personal","priority":20,"status":"available","in_use":true}]}""")
        }
        val order = api.makePreferred(2)
        val r = requests.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://server:4010/api/anthropic_auths/2/preferred", r.url.toString())
        val pool = Pool(listOf(), listOf(SeatAssignment("ipsum-admin", "Personal", "proxy", null))).withOrder(order)
        assertEquals(2, pool.preferredId)
        assertEquals(1, pool.inUseId, "the preferred account is cooling, so another is in use")
        assertEquals(listOf("Work", "Personal"), pool.ordered.map { it.label })
        assertEquals(1, pool.seats.size, "who used what stays")
    }

    @Test
    fun anAccountWithoutAPriorityComesLastAndCurrentIdWins() {
        val a = { id: Long, p: Int? -> AnthropicAccount(id, "a$id", p, "available", null, null, null, null, emptyList()) }
        val pool = Pool(listOf(a(1, null), a(2, 30), a(3, 10)), emptyList(), currentId = 2)
        assertEquals(listOf(3L, 2L, 1L), pool.ordered.map { it.id })
        assertEquals(3, pool.preferredId)
        assertEquals(2, pool.inUseId)
        assertNull(Pool(listOf(a(1, 10)), emptyList()).inUseId, "unknown when the server does not say")
    }

    /** Server f9a9cb0: the "pool" event, pushed on a preference change, a recovery or fallback, and a cooldown. */
    @Test
    fun aPoolEventSaysWhatChangedAndWho() {
        val manual = PoolChange.from(Json.parseToJsonElement("""{"change":"preferred","by":"op@example.com","at":"2026-10-06T07:01:00Z","current_id":3,"preferred_id":4}""").jsonObject)
        assertEquals(PoolChange("preferred", "op@example.com", "2026-10-06T07:01:00Z", 3, 4), manual)
        val automatic = PoolChange.from(Json.parseToJsonElement("""{"change":"degrade","by":null,"at":"2026-10-06T07:05:00Z","current_id":3,"preferred_id":4}""").jsonObject)
        assertNull(automatic.by)
    }

    /** Server 750b5cd: a seat's access level, set by the operator or derived; null puts it back to the default. */
    @Test
    fun accessIsSetAndClearedWithNull() = runTest {
        val api = api { json("""{"session":{"id":203771,"title":"ipsum-admin","status":"running","can_notify":false,"access":"full","access_explicit":false}}""") }
        val seat = api.setAccess(203771, null)
        assertEquals("full", seat.access)
        assertEquals(false, seat.accessExplicit)
        assertEquals(false, seat.canNotify, "a default full can lack the paging right")
        val r = requests.single()
        assertEquals("http://server:4010/api/sessions/203771/access", r.url.toString())
        assertEquals("null", r.jsonBody()["level"].toString(), "null, not a missing key, clears it")

        api.setAccess(203771, "notify")
        assertEquals("notify", requests.last().jsonBody()["level"]?.jsonPrimitive?.content)
    }

    /** Server 30e9dfc: paging is its own switch, timed or until changed; null clears it. */
    @Test
    fun pagingIsSwitchedApartFromTools() = runTest {
        val api = api {
            json("""{"session":{"id":5,"title":"t","status":"running","access":"basic","can_notify":true,"notify_grant":true,"notify_until":"2026-10-06T12:30:00Z"}}""")
        }
        val seat = api.setNotify(5, true, 60)
        assertEquals(true, seat.notifyGrant)
        assertEquals("2026-10-06T12:30:00Z", seat.notifyUntil)
        assertEquals(true, seat.canNotify)
        assertEquals("basic", seat.access, "basic tools can still page")
        val timed = requests.last().jsonBody()
        assertEquals("http://server:4010/api/sessions/5/notify", requests.last().url.toString())
        assertEquals("true", timed["allow"].toString())
        assertEquals("60", timed["minutes"].toString())

        api.setNotify(5, null, 60)
        val cleared = requests.last().jsonBody()
        assertEquals("null", cleared["allow"].toString())
        assertNull(cleared["minutes"], "clearing takes no duration")
    }
}
