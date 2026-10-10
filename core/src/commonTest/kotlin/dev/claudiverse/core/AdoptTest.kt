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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Adopting a seat started by hand: the preview, what blocks it, and the adopt call. */
class AdoptTest {
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private val process = """{"pid":87920,"proc_start":"Wed Oct  7 10:07:17 2026","tmux":"ipsum-admin:@199.%199","status":"idle",
        "version":"2.1.286","matched_by":"session_id","session_id":"d7dba841-a246-4186-9c74-da1d8534d610"}"""

    @Test
    fun aPreviewWithAStoppedProfileOfTheSeatsNameAdoptsIntoIt() {
        val p = AdoptPreview.from(json("""{"process":$process,"blocked":null,"suggested":null,
            "existing_profile":{"id":10,"name":"ipsum-admin","revision":1,"machine":"my-mac.local","folder":"/x","status":"stopped",
            "runtime":"patched","resume":"d7dba841-a246-4186-9c74-da1d8534d610"}}"""))
        assertEquals(87920, p.process?.pid)
        assertEquals("ipsum-admin:@199.%199", p.process?.tmux)
        assertEquals(10, p.existing?.id)
        assertTrue(p.canAdopt)
    }

    /** A profile of that name already running, or anything the server blocks, cannot be adopted. */
    @Test
    fun aRunningProfileOrABlockStopsIt() {
        val running = AdoptPreview.from(json("""{"process":$process,"existing_profile":{"id":10,"name":"ipsum-admin","revision":1,"machine":"m","folder":"/x","status":"running"}}"""))
        assertFalse(running.canAdopt)
        val blocked = AdoptPreview.from(json("""{"process":null,"blocked":"no Claude Code process for this seat on my-mac.local"}"""))
        assertFalse(blocked.canAdopt)
        assertEquals("no Claude Code process for this seat on my-mac.local", blocked.blocked)
        assertNull(blocked.folder)
        // Outside the allowed folders: the process's folder comes along, for a seat whose row has none.
        val outside = AdoptPreview.from(json("""{"process":null,"blocked":"/Users/me/x is outside this machine's allowed folders","folder":"/Users/me/x"}"""))
        assertEquals("/Users/me/x", outside.folder)
    }

    @Test
    fun aMatchByTmuxNameIsMarkedAndABareTerminalHasNoTmux() {
        val p = AdoptProcess.from(json("""{"pid":1,"proc_start":"x","tmux":null,"status":"busy","matched_by":"tmux_title"}"""))
        assertTrue(p.byTmuxName)
        assertTrue(p.isBusy)
        assertNull(p.tmux)
    }

    @Test
    fun theRowRulesOutRunnersProfileSeatsAndStoppedOnes() {
        val seat = Seat(id = 1, title = "ipsum-admin", displayTitle = null, status = "running", activity = null, type = null, model = null,
            lastActivityAt = null, host = "my-mac", fleet = "ipsum-fleet", origin = "manual")
        assertNull(adoptBlockedReason(seat))
        assertNotNull(adoptBlockedReason(seat.copy(origin = "runner")))
        assertNotNull(adoptBlockedReason(seat.copy(origin = "profile")))
        assertNotNull(adoptBlockedReason(seat.copy(status = "stopped")))
        assertNull(adoptBlockedReason(seat.copy(host = null)), "no machine recorded: the server is asked, on this Mac")
    }

    /** A seat with no machine recorded is looked for on the app's own; one with a machine stays on it. */
    @Test
    fun anUnknownMachineFallsBackToThisOne() {
        val seat = Seat(id = 1, title = "t", displayTitle = null, status = "running", activity = null, type = null, model = null,
            lastActivityAt = null, host = null, fleet = null, origin = "plain")
        assertEquals("my-mac.local", adoptHost(seat, "my-mac.local"))
        assertEquals("linux-box", adoptHost(seat.copy(host = "linux-box"), "my-mac.local"))
        assertNull(adoptHost(seat, null))
    }

    @Test
    fun adoptSendsTheFieldsTheExactProcessAndTheProfileToUse() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = Api({ ServerAccess("http://server:4010", "tok") }, HttpClient(MockEngine { r ->
            requests += r
            respond("""{"profile":{"id":10,"name":"ipsum-admin","revision":2,"machine":"m","folder":"/x","status":"starting"}}""", HttpStatusCode.Accepted,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val proc = AdoptProcess.from(json(process))
        val profile = api.adopt(203771, ProfileFields(machine = "m", folder = "/x", runtime = "patched"), proc, profileId = 10, force = false)
        assertEquals("starting", profile.status)
        val r = requests.single()
        assertEquals("http://server:4010/api/sessions/203771/adopt", r.url.toString())
        val body = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
        assertEquals("87920", body["pid"]?.jsonPrimitive?.content)
        assertEquals("Wed Oct  7 10:07:17 2026", body["proc_start"]?.jsonPrimitive?.content)
        assertEquals("10", body["profile_id"]?.jsonPrimitive?.content)
        assertEquals("patched", body["runtime"]?.jsonPrimitive?.content)
        assertNull(body["force"], "force only when asked")
        assertNull(body["host"], "a seat with a machine sends none")

        api.adopt(203771, ProfileFields(machine = "m", folder = "/x"), proc, profileId = null, force = false, host = "my-mac.local")
        assertEquals("my-mac.local", Json.parseToJsonElement((requests.last().body as TextContent).text).jsonObject["host"]?.jsonPrimitive?.content)
        api.adoptPreview(5, host = "my-mac.local")
        assertEquals("http://server:4010/api/sessions/5/adopt_preview?host=my-mac.local", requests.last().url.toString())
    }
}
