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

/** Auto-restart's events, and the seat and runtime versions that come with mod 0.12.1. */
class AutoRestartTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun anAutoRestartSaysSo() {
        val e = SeatRestartEvent.from(json("""{"session_id":1,"outcome":"restarted","new_version":"2.1.300","origin":"auto","batch_id":"b9"}"""))
        assertTrue(e.isAuto)
        assertFalse(SeatRestartEvent.from(json("""{"session_id":1,"outcome":"restarted"}""")).isAuto)
        assertTrue(RestartBatchEvent.from(json("""{"batch_id":"b9","state":"started","origin":"auto"}""")).isAuto)
        assertFalse(RestartBatchEvent.from(json("""{"batch_id":"b9","state":"started"}""")).isAuto)
    }

    @Test
    fun bothClientVersionFormsRead() {
        assertEquals(ClientVersion("2.1.300", modded = true, mod = "0.12.1"), ClientVersion.parse("2.1.300 (Claude Code) [claudiverse-mod 0.12.1]"))
        assertEquals(ClientVersion("2.1.300", modded = true, mod = null), ClientVersion.parse("2.1.300 (Claude Code) [claudiverse-mod]"))
        assertEquals(ClientVersion("2.1.296", modded = false, mod = null), ClientVersion.parse("2.1.296 (Claude Code)"))
        assertEquals(ClientVersion(null, modded = false, mod = null), ClientVersion.parse(null))
        assertEquals("2.1.300 · mod 0.12.1", ClientVersion.parse("2.1.300 (Claude Code) [claudiverse-mod 0.12.1]").label)
        assertEquals("2.1.300 · mod", ClientVersion.parse("2.1.300 (Claude Code) [claudiverse-mod]").label)
    }

    /** A model's since compares the Claude Code number alone, whatever follows it. */
    @Test
    fun aModelIsKnownByEitherForm() {
        val haiku = SessionChoices.models.single { it.id == "claude-haiku-5-5" }
        assertTrue(haiku.knownBy("2.1.300 (Claude Code) [claudiverse-mod 0.12.1]"))
        assertTrue(haiku.knownBy("2.1.300 (Claude Code) [claudiverse-mod]"))
        assertFalse(haiku.knownBy("2.1.291 (Claude Code) [claudiverse-mod 0.12.1]"), "0.12.1 must not be read as part of the version")
    }

    @Test
    fun aRuntimeCarriesItsMod() {
        val h = Host.from(json("""{"name":"ipsum-box","runtimes":[{"name":"stock+mod","version":"2.1.300","mod_version":"0.12.1"},{"name":"stock","version":"2.1.300"}],"spawn":{"options":[],"runtime":"stock+mod","version":"2.1.300"}}"""))
        assertEquals(listOf("2.1.300 · mod 0.12.1", "2.1.300"), h.runtimes.map { it.versionLabel })
        assertEquals("0.12.1", h.spawnRuntime?.modVersion, "a restarted seat gets the spawn runtime's mod")
        assertNull(HostRuntime("x", null).versionLabel)
    }

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(reply: String, status: HttpStatusCode = HttpStatusCode.OK) = Api(
        { ServerAccess("http://server:4010", "tok") },
        HttpClient(MockEngine { r -> requests += r; respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json")) }),
    )

    private fun HttpRequestData.json() = Json.parseToJsonElement((body as TextContent).text).toString()

    @Test
    fun theGlobalSettingIsReadAndOnlyGivenFieldsAreSent() = runTest {
        assertEquals(AutoRestart(false, 300), api("""{"auto_restart":{"enabled":false,"min_idle_s":300}}""").autoRestart())
        assertEquals(AutoRestart(false, 300), api("""{}""").autoRestart(), "nothing stored: off, 300 s")
        assertNull(api("""{"errors":{"detail":"Not Found"}}""", HttpStatusCode.NotFound).autoRestart(), "a server without auto-restart")
        val set = api("""{"auto_restart":{"enabled":true,"min_idle_s":300}}""").setAutoRestart(enabled = true)
        assertEquals(AutoRestart(true, 300), set)
        assertEquals(HttpMethod.Put, requests.last().method)
        assertEquals("http://server:4010/api/settings", requests.last().url.toString())
        assertEquals("""{"auto_restart":{"enabled":true}}""", requests.last().json())
    }

    @Test
    fun aFleetOverrideIsReadAndWrittenWithNullMeaningInherit() = runTest {
        val f = Fleet.from(json("""{"fleet":"ps2","paused":false,"auto_restart":{"enabled":false,"min_idle_s":null},"auto_restart_effective":{"enabled":false,"min_idle_s":300}}"""))
        assertEquals(AutoRestartOverride(false, null), f.autoRestart)
        assertEquals(AutoRestart(false, 300), f.autoRestartEffective)
        val none = Fleet.from(json("""{"fleet":"pe","auto_restart":null}"""))
        assertNull(none.autoRestart)
        assertNull(none.autoRestartEffective, "an older server says nothing")

        val api = api("""{"fleet":"ps2","paused":false,"auto_restart":{"enabled":true,"min_idle_s":600},"auto_restart_effective":{"enabled":true,"min_idle_s":600}}""")
        val (o, eff) = api.setFleetAutoRestart("ps2", AutoRestartOverride(true, 600))
        assertEquals(AutoRestartOverride(true, 600), o)
        assertEquals(AutoRestart(true, 600), eff)
        assertEquals(HttpMethod.Patch, requests.last().method)
        assertEquals("http://server:4010/api/fleets/ps2", requests.last().url.toString())
        assertEquals("""{"auto_restart":{"enabled":true,"min_idle_s":600}}""", requests.last().json())
        api.setFleetAutoRestart("ps2", AutoRestartOverride(enabled = null, minIdleS = 600))
        assertEquals("""{"auto_restart":{"enabled":null,"min_idle_s":600}}""", requests.last().json(), "null inherits")
        api.setFleetAutoRestart("ps2", AutoRestartOverride())
        assertEquals("""{"auto_restart":null}""", requests.last().json(), "an empty override clears it")
    }

    @Test
    fun aRefusedChangeSaysWhy() = runTest {
        val e = kotlin.test.assertFailsWith<ApiException> {
            api("""{"error":"min_idle_s must be a whole number of seconds from 60 to 86400"}""", HttpStatusCode.BadRequest).setAutoRestart(minIdleS = 5)
        }
        assertTrue(e.message!!.contains("60 to 86400"), e.message)
    }

    @Test
    fun aPlanEntryCarriesTheModVersions() {
        val p = RestartPlan.from(json("""{"session_id":1,"title":"s","action":"restart","client_version":"2.1.300","target_version":"2.1.300","mod_version":"0.12.0","target_mod_version":"0.12.1"}"""))
        assertEquals("0.12.0" to "0.12.1", p.modVersion to p.targetModVersion)
    }
}
