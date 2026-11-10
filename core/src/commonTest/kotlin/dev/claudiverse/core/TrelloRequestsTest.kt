package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trello write requests as the apps keep them: one row per id, the newest copy, decisions kept. */
class TrelloRequestsTest {
    private fun req(id: Long, status: String = "open", updated: String = "2026-11-10T12:00:00Z", decided: String? = null, reason: String = "file the bugs") =
        """{"id":$id,"session_id":12,"seat_title":"pm-seat","board":"AbCd1234","board_name":"Example Board",""" +
            """"board_url":"https://trello.com/b/AbCd1234","reason":"$reason","status":"$status","decided_at":${decided?.let { "\"$it\"" } ?: "null"},""" +
            """"inserted_at":"2026-11-10T11:00:00Z","updated_at":"$updated"}"""

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    /** A store whose server answers each path with a body and a status; the socket never opens. */
    private fun store(answers: Map<String, Pair<HttpStatusCode, String>>): TrelloRequests {
        val api = Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { r ->
            val (status, body) = answers["${r.method.value} ${r.url.encodedPath}"] ?: (HttpStatusCode.NotFound to "{}")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return TrelloRequests(api, PhoenixSocket({ ServerAccess("", "") }, scope), scope)
    }

    private val empty = mapOf(
        "GET /api/trello/write_requests" to (HttpStatusCode.OK to """{"requests":[]}"""),
        "GET /api/trello/grants" to (HttpStatusCode.OK to """{"grants":[]}"""),
    )

    @Test
    fun anAskingAgainUpdatesTheSameRow() {
        val s = store(empty)
        s.onEvent("trello_write_request", json(req(4)))
        s.onEvent("trello_write_request", json(req(4, updated = "2026-11-10T12:05:00Z", reason = "and comment on them")))
        assertEquals(1, s.requests.value.size)
        assertEquals("and comment on them", s.requests.value.single().reason)
        assertEquals("pm-seat asks for write access to Example Board", s.open.value.single().prompt)
    }

    /** Decided anywhere: the row stays, decided, and leaves the open list, so every device can close its prompt. */
    @Test
    fun aDecisionUpdatesTheRowInPlace() {
        val s = store(empty)
        s.onEvent("trello_write_request", json(req(4)))
        s.onEvent("trello_write_request_decided", json(req(4, status = "approved", decided = "2026-11-10T12:01:00Z")))
        assertEquals("approved", s.requests.value.single().status)
        assertTrue(s.open.value.isEmpty())
    }

    /** A GET reply that raced a newer event never wins: the decision is not undone by an older "open". */
    @Test
    fun anOlderCopyNeverOverwritesANewerOne() = runTest {
        val s = store(empty + ("GET /api/trello/write_requests" to (HttpStatusCode.OK to """{"requests":[${req(4)}]}""")))
        s.onEvent("trello_write_request_decided", json(req(4, status = "denied", decided = "2026-11-10T12:02:00Z")))
        s.refresh()
        assertEquals("denied", s.requests.value.single().status)
        // And the other way: an old event after a newer GET.
        val t = store(empty + ("GET /api/trello/write_requests" to (HttpStatusCode.OK to """{"requests":[${req(5, status = "approved", decided = "2026-11-10T12:03:00Z")}]}""")))
        t.refresh()
        t.onEvent("trello_write_request", json(req(5)))
        assertEquals("approved", t.requests.value.single().status)
    }

    @Test
    fun anUnknownStatusIsNotOpen() {
        val r = TrelloWriteRequest.from(json(req(4, status = "withdrawn")))
        assertFalse(r.isOpen)
        assertFalse(TrelloWriteRequest.from(json(req(4).replace(""""status":"open",""", ""))).isOpen, "no status at all")
        assertTrue(TrelloWriteRequest.from(json(req(4))).isOpen)
    }

    /** 409: someone decided first; the card shows that decision. 404: the row goes. */
    @Test
    fun aDecisionThatLostTheRaceSaysWhatWon() = runTest {
        val s = store(empty + mapOf(
            "POST /api/trello/write_requests/4/approve" to (HttpStatusCode.Conflict to """{"error":"already decided","request":${req(4, status = "denied", decided = "2026-11-10T12:04:00Z")}}"""),
            "POST /api/trello/write_requests/5/deny" to (HttpStatusCode.NotFound to """{"error":"no such request"}"""),
        ))
        s.onEvent("trello_write_request", json(req(4)))
        s.onEvent("trello_write_request", json(req(5)))
        val lost = assertFailsWith<TrelloAlreadyDecided> { s.approve(4) }
        assertEquals("denied", lost.request?.status)
        assertEquals("denied", s.requests.value.first { it.id == 4L }.status)
        assertFailsWith<TrelloRequestGone> { s.deny(5) }
        assertNull(s.requests.value.firstOrNull { it.id == 5L })
    }

    @Test
    fun approvingAddsTheGrantAndRevokingDropsIt() = runTest {
        val grant = """{"id":1,"seat_title":"pm-seat","board":"AbCd1234","board_name":"Example Board","request_id":4,"granted_at":"2026-11-10T12:05:00Z"}"""
        val s = store(empty + mapOf(
            "POST /api/trello/write_requests/4/approve" to (HttpStatusCode.OK to """{"request":${req(4, status = "approved", decided = "2026-11-10T12:05:00Z")},"grant":$grant}"""),
            "DELETE /api/trello/grants/1" to (HttpStatusCode.NoContent to ""),
        ))
        s.onEvent("trello_write_request", json(req(4)))
        assertEquals(1L, s.approve(4).grant?.id)
        assertEquals(listOf(1L), s.grants.value.map { it.id })
        s.revoke(1)
        assertTrue(s.grants.value.isEmpty())
        // Revoked elsewhere: the event drops it too.
        s.onEvent("trello_write_request_decided", json(req(4, status = "approved", decided = "2026-11-10T12:05:00Z")))
        s.approve(4)
        s.onEvent("trello_write_grant_revoked", json("""{"id":1,"seat_title":"pm-seat","board":"AbCd1234"}"""))
        assertTrue(s.grants.value.isEmpty())
    }

    /** A request that arrives by event while a GET is on its way is kept, though the reply lacks it. */
    @Test
    fun aRequestArrivingDuringARefreshIsKept() = runTest {
        lateinit var s: TrelloRequests
        val api = Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { r ->
            if (r.url.encodedPath.endsWith("write_requests")) s.onEvent("trello_write_request", json(req(9)))
            val body = if (r.url.encodedPath.endsWith("grants")) """{"grants":[]}""" else """{"requests":[]}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        s = TrelloRequests(api, PhoenixSocket({ ServerAccess("", "") }, scope), scope)
        s.refresh()
        assertEquals(listOf(9L), s.requests.value.map { it.id })
    }
}
