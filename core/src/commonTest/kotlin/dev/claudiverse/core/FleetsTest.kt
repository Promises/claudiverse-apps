package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FleetsTest {

    private fun runner(slot: String, status: String, seat: String? = null) = FleetRunner(slot, "ipsum-fleet", status, seat = seat)

    /** The shape Failover documented for GET /api/fleets, absent optionals included. */
    @Test
    fun aFleetReadsWithOptionalFieldsAbsent() {
        val o = Json.parseToJsonElement(
            """{"fleet":"ipsum-fleet","runners":[{"slot":"ipsum-w5","fleet":"ipsum-fleet","status":"busy","host":"my-mac.local","channel":"ipsum-tasks",
               "task_source":"forum","model":"claude-opus-5-5[1m]","seat":"ipsum-w5-t30450","task":30450,"since":"2026-10-05T06:40:00.000Z"},
               {"slot":"ipsum-w6","fleet":"ipsum-fleet","status":"idle"}],
               "seats":[{"id":203771,"title":"ipsum-admin","origin":"manual","host":"my-mac.local"}]}""",
        ).jsonObject
        val f = Fleet.from(o)
        assertEquals(listOf("ipsum-w5", "ipsum-w6"), f.runners.map { it.slot })
        assertEquals(30450L, f.runners[0].task)
        assertNull(f.runners[1].seat)
        assertNull(f.runners[1].lane)
        assertEquals("ipsum-admin", f.seats.single().title)
    }

    @Test
    fun aRunnerEventReplacesItsSlotAddsANewOneAndStoppedRemovesIt() {
        val start = listOf(Fleet("ipsum-fleet", listOf(runner("ipsum-w5", "idle"), runner("ipsum-w6", "idle")), emptyList()))
        val busy = Fleets.applyRunner(start, runner("ipsum-w5", "busy", seat = "ipsum-w5-t1"))
        assertEquals(listOf("busy", "idle"), busy.single().runners.map { it.status })
        val added = Fleets.applyRunner(busy, runner("ipsum-w7", "idle"))
        assertEquals(listOf("ipsum-w5", "ipsum-w6", "ipsum-w7"), added.single().runners.map { it.slot })
        val stopped = Fleets.applyRunner(added, runner("ipsum-w6", "stopped"))
        assertEquals(listOf("ipsum-w5", "ipsum-w7"), stopped.single().runners.map { it.slot })
    }

    @Test
    fun aRunnerOfAnUnknownFleetStartsThatFleet() {
        val out = Fleets.applyRunner(emptyList(), FleetRunner("lorem-w1", "lorem-fleet", "idle"))
        assertEquals("lorem-fleet", out.single().fleet)
        assertEquals(emptyList(), Fleets.applyRunner(emptyList(), FleetRunner("lorem-w1", "lorem-fleet", "stopped")))
    }
}
