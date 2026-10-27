package dev.claudiverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** pi seats: what New seat sends (as the web's spawnRequest), and a pi seat's own queue. */
class PiSeatTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val m5 = Host.from(json("""{"name":"ipsum-box","online":true,"spawn":{"options":["model","pooled","runtime","write","effort"],
        "runtimes":["claude","pi"],"pi":{"default_model":"zai/glm-5.3-flash","efforts":["off","minimal","low","medium","high","xhigh","max"],
        "models":[{"id":"zai/glm-5.3-flash","label":"GLM-5.3 Flash"},{"id":"zai/glm-5.3","label":"GLM-5.3"}]}}}"""))
    private val old = Host.from(json("""{"name":"lorem-box","online":true,"spawn":{"options":["model","pooled"],"runtimes":["claude"]}}"""))

    @Test
    fun aMachineOffersPiOnlyFromAgent019WithModels() {
        val pi = piChoices(m5)!!
        assertEquals(listOf("zai/glm-5.3-flash", "zai/glm-5.3"), pi.models.map { it.id })
        assertEquals("GLM-5.3 Flash", pi.models.first().label)
        assertTrue("high" in pi.efforts)
        assertNull(piChoices(old), "an agent before 0.19 has no pi")
        assertNull(piChoices(Host.from(json("""{"name":"x","spawn":{"options":["runtime"],"runtimes":["claude","pi"],"pi":{"models":[],"efforts":[]}}}"""))), "no models, no pi seat")
    }

    @Test
    fun aPiSpawnIsAutoWithItsModelAndThinkingAndNoPool() {
        val r = spawnRequest(m5, mode = "plan", model = "claude-opus-5-5", pooled = true, runtime = PI, piModel = "zai/glm-5.3")
        assertEquals(SpawnRequest(mode = "auto", model = "zai/glm-5.3", pooled = null, runtime = PI, effort = PI_DEFAULT_EFFORT), r)
        assertNull(spawnRequest(m5, "auto", null, null, runtime = PI, piModel = "zai/not-listed").model, "a model the machine doesn't list is left out")
        assertEquals("low", spawnRequest(m5, "auto", null, null, runtime = PI, effort = "low").effort)
        assertNull(spawnRequest(m5, "auto", null, null, runtime = PI, effort = "turbo").effort, "a level the machine doesn't list is left out")
        // A Claude seat: its mode, model and pool as before, and no effort.
        assertEquals(SpawnRequest("plan", "claude-opus-5-5", true), spawnRequest(m5, "plan", "claude-opus-5-5", true, effort = "high"))
        // pi asked of a machine without it: a Claude seat.
        assertNull(spawnRequest(old, "auto", null, null, runtime = PI).runtime)
    }

    private val requests = mutableListOf<HttpRequestData>()
    private fun api(reply: String, status: HttpStatusCode = HttpStatusCode.OK) = Api(
        { ServerAccess("http://server", "tok") },
        HttpClient(MockEngine { r -> requests += r; respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json")) }),
    )

    @Test
    fun theSpawnBodyCarriesRuntimeAndEffort() = runTest {
        api("""{"spawn_id":"s1"}""").spawn("ipsum-box", "/work", "pi-1", spawnRequest(m5, "auto", null, null, runtime = PI), null)
        val body = json((requests.single().body as TextContent).text)
        assertEquals("pi", body.str("runtime"))
        assertEquals("high", body.str("effort"))
        assertEquals("auto", body.str("mode"))
        assertNull(body["pooled"])
        assertNull(body["model"], "no model: the machine's default")
    }

    @Test
    fun aPiSeatsQueueEntriesCanBeDeletedAndAFollowUpSteered() {
        val q = QueuedPrompt.list(Json.parseToJsonElement("""[
            {"id":"a","text":"later","kind":"followUp","source":"app","state":"queued"},
            {"id":"b","text":"now","kind":"steer","source":"cv","state":"queued"},
            {"id":"c","text":"going in","kind":"followUp","state":"steering"},
            {"id":"d","text":"claude's","state":"queued"}]""") as kotlinx.serialization.json.JsonArray)
        assertEquals(listOf("after this run", "steers at the next step", "entering the run", "in Claude's queue"), q.map { it.label })
        val rows = outboxView(emptyList(), q, emptyList())
        assertEquals(listOf("a", "b", null, null), rows.map { it.queueItemId }, "only a waiting pi entry can be deleted")
        assertEquals(listOf(true, false, false, false), rows.map { it.canSteer }, "only a waiting follow-up can be made to steer")
        assertEquals(listOf(true, true, false, false), rows.map { cancellableNow(it, 0) })
        assertEquals("app", q[0].source)
    }

    @Test
    fun deleteAndSteerUseTheQueueCallsAndSayWhy() = runTest {
        assertEquals("requested", api("""{"status":"requested"}""").deleteQueueItem(7, "a b"))
        assertEquals(HttpMethod.Delete, requests.last().method)
        assertEquals("http://server/api/sessions/7/queue/a%20b", requests.last().url.toString())
        assertEquals("too_late", api("""{"status":"too_late"}""").steerQueueItem(7, "a"))
        assertEquals("http://server/api/sessions/7/queue/a/steer", requests.last().url.toString())
        assertEquals("not_found", api("""{"error":"x"}""", HttpStatusCode.NotFound).deleteQueueItem(7, "a"))
        assertEquals("not_connected", api("""{"error":"x"}""", HttpStatusCode.Conflict).steerQueueItem(7, "a"))
        assertNull(queueActionNotice("already", steer = false))
        assertEquals("The seat is not connected", queueActionNotice("not_connected", steer = true))
        assertFalse(queueActionNotice("too_late", steer = true).isNullOrEmpty())
    }
}
