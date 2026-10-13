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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Restarting seats: the plan, the events, a batch's progress, and the requests. */
class RestartsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun plan(id: Long, action: String = "restart", reason: String? = null) =
        RestartPlan(id, "lorem-w$id", "ipsum-box", null, action, reason, null, "2.1.292", "2.1.296")

    private fun event(id: Long, outcome: String, batch: String? = "b1") =
        SeatRestartEvent(id, "lorem-w$id", "ipsum-box", outcome, null, null, "2.1.292", null, null, false, null, "r$id", batch)

    @Test
    fun aPlanCarriesItsNamesAndSkipReason() {
        val p = RestartPlan.from(obj("""{"session_id":5,"title":"lorem-w1","host":"ipsum-box","fleet":"lorem-fleet","action":"skip",
            "reason":"It is busy.","reason_code":"busy","client_version":"2.1.292","target_version":"2.1.296",
            "launch":{"argv":["--model","--mcp-config"],"env":["CLAUDE_CONFIG_DIR"],"carried":[],"dropped":[]}}"""))
        assertTrue(p.skipped)
        assertEquals("busy", p.reasonCode)
        assertEquals(listOf("--model", "--mcp-config"), p.argvNames)
        assertEquals(listOf("CLAUDE_CONFIG_DIR"), p.envNames)
    }

    @Test
    fun anUnknownActionReadsAsSkipWithAReason() {
        val p = RestartPlan.from(obj("""{"session_id":5,"title":"x","action":"teleport","reason":null}"""))
        assertEquals(RestartPlan.SKIP, p.action)
        assertTrue(p.reason!!.contains("teleport"))
        assertEquals(emptyList(), p.argvNames)
    }

    @Test
    fun eventsAndBatchesParse() {
        val e = SeatRestartEvent.from(obj("""{"restart_id":"r1","batch_id":null,"session_id":5,"outcome":"restarted",
            "old_version":"2.1.292","new_version":"2.1.296","new_session_id":9,"killed":true,"note":null}"""))
        assertEquals(9L, e.newSessionId)
        assertTrue(e.killed && e.finished)
        assertNull(e.batchId)
        val b = RestartBatchEvent.from(obj("""{"batch_id":"b1","host":null,"state":"done","total":4,"counts":{"restarted":2,"adopted":1,"skipped":1,"failed":0}}"""))
        assertTrue(b.done)
        assertEquals(4, b.total)
        assertEquals(mapOf("restarted" to 2, "adopted" to 1, "skipped" to 1, "failed" to 0), b.counts)
    }

    @Test
    fun aBatchFoldsIntoItsPlanOrderWithTheLatestOutcome() {
        val plan = listOf(plan(1), plan(2, "adopt"), plan(3, "skip", "It is busy."))
        val events = listOf(event(2, "started"), event(1, "started"), event(1, "restarted"), event(1, "failed", batch = "other"))
        val rows = foldRestart(plan, events, "b1")
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.plan.sessionId }, "plan order, not event order")
        assertEquals(listOf("restarted", "started", "skipped"), rows.map { it.status }, "the latest event in this batch; a planned skip reads skipped")
    }

    @Test
    fun restartsKeepEveryEventAndEachBatchsLatest() {
        val r = Restarts(PhoenixSocket({ ServerAccess("", "") }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        r.onEvent("seat_restart", obj("""{"session_id":1,"outcome":"started","batch_id":"b1"}"""))
        r.onEvent("seat_restart", obj("""{"session_id":1,"outcome":"restarted","batch_id":"b1"}"""))
        r.onEvent("restart_batch", obj("""{"batch_id":"b1","state":"started","total":1,"counts":{}}"""))
        r.onEvent("restart_batch", obj("""{"batch_id":"b1","state":"done","total":1,"counts":{"restarted":1}}"""))
        r.onEvent("seat_restart", obj("""{"outcome":"started"}"""))
        assertEquals(listOf("started", "restarted"), r.events.value.map { it.outcome }, "a malformed event is dropped")
        assertTrue(r.batches.value.getValue("b1").done)
    }

    // --- requests ---

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { r -> requests += r; handler(r) }))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.body(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun aSeatsPlanIsADryRun() = runTest {
        val api = api { json("""{"seat":{"session_id":5,"title":"lorem-w1","action":"adopt","reason":null}}""") }
        assertEquals(RestartPlan.ADOPT, api.planSeatRestart(5).action)
        assertEquals("http://server:4010/api/sessions/5/restart", requests.single().url.toString())
        assertEquals("true", requests.single().body()["dry_run"]?.jsonPrimitive?.content)
    }

    @Test
    fun aRefusedRestartSaysWhyInTheServersWords() = runTest {
        val api = api { json("""{"error":"lorem-w1 is working: try again when it is idle.","reason_code":"busy"}""", HttpStatusCode.Conflict) }
        val e = assertFailsWith<RestartRefused> { api.restartSeat(5) }
        assertEquals("lorem-w1 is working: try again when it is idle.", e.message)
        assertEquals("busy", e.reasonCode)
        assertNull(requests.single().body()["dry_run"], "a real restart is no dry run")
    }

    @Test
    fun anIdleRestartGoesToOneMachineOrAll() = runTest {
        val api = api { json("""{"batch_id":"b1","seats":[{"session_id":5,"title":"lorem-w1","action":"restart","reason":null}]}""") }
        val started = api.restartIdle("ipsum-box", RestartOptions(excludeFleets = listOf("lorem-fleet")))
        assertEquals("b1", started.batchId)
        assertEquals("http://server:4010/api/hosts/ipsum-box/restart_idle", requests.last().url.toString())
        val body = requests.last().body()
        assertEquals("true", body["only_outdated"]?.jsonPrimitive?.content)
        assertEquals(listOf("lorem-fleet"), body["exclude_fleets"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(body["dry_run"])
        api.planIdleRestart(null)
        assertEquals("http://server:4010/api/hosts/restart_idle", requests.last().url.toString())
        assertEquals("true", requests.last().body()["dry_run"]?.jsonPrimitive?.content)
    }

    @Test
    fun anExactSetPausedFleetsAndProtectedSeatsAreSentAsAsked() = runTest {
        val api = api { json("""{"batch_id":"b1","seats":[]}""") }
        api.restartIdle(null, RestartOptions(sessionIds = listOf(5, 7), excludeSessionIds = listOf(9)))
        val body = requests.last().body()
        assertEquals(listOf("5", "7"), body["session_ids"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(listOf("9"), body["exclude_session_ids"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("true", body["exclude_paused"]?.jsonPrimitive?.content, "paused fleets are left out by default")
        api.restartIdle(null, RestartOptions(excludePaused = false))
        assertNull(requests.last().body()["exclude_paused"])
        assertNull(requests.last().body()["session_ids"], "no set: the server chooses")
    }

    @Test
    fun aProtectedSeatIsRestartedOnlyWhenConfirmed() = runTest {
        val api = api { json("""{"restart_id":"r1"}""") }
        api.restartSeat(5)
        assertNull(requests.last().body()["confirm_protected"])
        api.restartSeat(5, confirmProtected = true)
        assertEquals("true", requests.last().body()["confirm_protected"]?.jsonPrimitive?.content)
    }

    @Test
    fun protectedAndPausedAreRead() {
        assertTrue(RestartPlan.from(obj("""{"session_id":5,"title":"x","action":"restart","reason":null,"protected":true}""")).isProtected)
        assertTrue(Fleet.from(obj("""{"fleet":"lorem-fleet","paused":true,"runners":[],"seats":[]}""")).paused)
        assertTrue(!Fleet.from(obj("""{"fleet":"ipsum-fleet","runners":[],"seats":[]}""")).paused)
    }
}
