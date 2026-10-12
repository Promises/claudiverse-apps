package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a spawn asks a machine for: only what its agent offers, and nothing for a choice not made. */
class SpawnExtrasTest {
    private fun host(spawn: String?) = Host.from(
        Json.parseToJsonElement(
            """{"name":"ipsum-box","online":true,"seats":0,"roots":[],"last_seen_at":"2026-10-12T06:00:00Z"${spawn?.let { ""","spawn":$it""" } ?: ""}}""",
        ).jsonObject,
    )

    private val current = host("""{"options":["model","pooled"],"pool_only":false,"pooled":true}""")

    @Test
    fun aHostCarriesItsSpawnChoicesAndLastSeen() {
        val s = current.spawn!!
        assertEquals(true, s.offersModel && s.offersPool)
        assertEquals("2026-10-12T06:00:00Z", current.lastSeenAt)
        assertNull(host(null).spawn, "an older agent reports none")
    }

    @Test
    fun whatIsChosenIsSentToAnAgentThatOffersIt() {
        assertEquals(SpawnExtras("haiku", false), spawnExtras(current, "haiku", false))
    }

    @Test
    fun nothingIsSentForAChoiceNotMade() {
        assertEquals(SpawnExtras(null, null), spawnExtras(current, null, null))
        assertEquals(SpawnExtras(null, null), spawnExtras(current, "", null))
    }

    @Test
    fun anOlderAgentIsAskedForNeither() {
        assertEquals(SpawnExtras(null, null), spawnExtras(host(null), "opus", true))
        assertEquals(SpawnExtras(null, null), spawnExtras(host("""{"options":[],"pool_only":false,"pooled":false}"""), "opus", true))
    }

    @Test
    fun aPoolOnlyMachineIsNotAskedAboutThePool() {
        val poolOnly = host("""{"options":["model","pooled"],"pool_only":true,"pooled":true}""")
        assertEquals(SpawnExtras("sonnet", null), spawnExtras(poolOnly, "sonnet", false))
    }
}
