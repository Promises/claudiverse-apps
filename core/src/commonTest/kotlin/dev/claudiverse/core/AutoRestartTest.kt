package dev.claudiverse.core

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
        val h = Host.from(json("""{"name":"ipsum-box","runtimes":[{"name":"stock+mod","version":"2.1.300","mod_version":"0.12.1"},{"name":"stock","version":"2.1.300"}],"spawn":{"options":[],"version":"2.1.300","mod_version":"0.12.1"}}"""))
        assertEquals(listOf("2.1.300 · mod 0.12.1", "2.1.300"), h.runtimes.map { it.versionLabel })
        assertEquals("0.12.1", h.spawn?.modVersion)
        assertNull(HostRuntime("x", null).versionLabel)
    }
}
