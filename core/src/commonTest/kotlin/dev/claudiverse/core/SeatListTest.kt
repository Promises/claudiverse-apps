package dev.claudiverse.core

import kotlin.test.assertEquals
import kotlin.test.Test

/** The design's sample fleet (Android Theme.dc.html, Seats v2): 8 seats, 4 machines, 2 fleets. */
class SeatListTest {

    private fun seat(id: Long, label: String, host: String, origin: String, fleet: String?, busy: Boolean) = Seat(
        id = id, title = label, displayTitle = null, status = "running", activity = if (busy) "busy" else "idle",
        type = "sdk", model = null, lastActivityAt = null, host = host, fleet = fleet, origin = origin,
    )

    private val fleet = listOf(
        seat(1, "orchestrator", "atlas", "manual", null, busy = true),
        seat(2, "kb-lead", "atlas", "manual", "kb-rewrite", busy = true),
        seat(3, "kb-w1", "hetzner-02", "runner", "kb-rewrite", busy = true),
        seat(4, "kb-w2", "hetzner-02", "runner", "kb-rewrite", busy = false),
        seat(5, "kb-w3", "hetzner-03", "runner", "kb-rewrite", busy = false),
        seat(6, "android-ui", "macbook", "spawn", "android", busy = false),
        seat(7, "android-api", "macbook", "spawn", "android", busy = true),
        seat(8, "ipsum-review", "atlas", "manual", null, busy = false),
    )

    @Test
    fun defaultsHideRunnersAndGroupFleetsWithNoFleetLast() {
        val list = seatList(fleet, SeatFilters())
        assertEquals(8, list.total)
        assertEquals(5, list.shown)
        assertEquals(3, list.busy)
        assertEquals(3, list.runnersHidden)
        assertEquals(listOf("android", "kb-rewrite", null), list.groups.map { it.fleet })
        assertEquals(listOf(2, 1, 2), list.groups.map { it.seats.size })
        assertEquals(listOf(1, 1, 1), list.groups.map { it.busy })
    }

    @Test
    fun showingRunnersBringsThemBackIntoTheirFleet() {
        val list = seatList(fleet, SeatFilters(hideRunner = false))
        assertEquals(8, list.shown)
        assertEquals(0, list.runnersHidden)
        assertEquals(4, list.groups.single { it.fleet == "kb-rewrite" }.seats.size)
    }

    @Test
    fun machineMenuCountsVisibleAndHiddenRunnersPerHost() {
        val hosts = seatList(fleet, SeatFilters()).hosts.associate { it.host to (it.visible to it.hiddenRunners) }
        assertEquals(5 to 3, hosts[null])
        assertEquals(3 to 0, hosts["atlas"])
        assertEquals(0 to 2, hosts["hetzner-02"])
        assertEquals(0 to 1, hosts["hetzner-03"])
        assertEquals(2 to 0, hosts["macbook"])
    }

    /** The design's empty state: a runner-only machine shows nothing and says how many are hidden. */
    @Test
    fun runnerOnlyMachineIsEmptyWithItsRunnersCounted() {
        val list = seatList(fleet, SeatFilters(host = "hetzner-02"))
        assertEquals(0, list.shown)
        assertEquals(2, list.runnersHidden)
        assertEquals(emptyList<SeatGroup>(), list.groups)
    }

    @Test
    fun ungroupedIsOneListOfEverythingShown() {
        val list = seatList(fleet, SeatFilters(groupByFleet = false))
        assertEquals(1, list.groups.size)
        assertEquals(5, list.groups[0].seats.size)
    }

    /** Until the server reports hosts there is no machine menu at all. */
    @Test
    fun noHostsMeansNoMachineMenu() {
        val bare = fleet.map { it.copy(host = null, fleet = null, origin = null) }
        val list = seatList(bare, SeatFilters())
        assertEquals(emptyList<HostCount>(), list.hosts)
        assertEquals(8, list.shown)
    }
}

/** A seat's name in lists: the operator's for a profile's seat, Claude Code's own title only for one started by hand. */
class SeatLabelTest {
    private fun row(extra: String) = Seat.from(
        kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":1,"title":"standalone-bob","display_title":"An old auto-title","status":"running"$extra}""",
        ) as kotlinx.serialization.json.JsonObject,
    )

    @Test
    fun aProfilesSeatIsCalledByItsTitle() {
        assertEquals("standalone-bob", row(""","profile_id":14""").label)
        assertEquals(14L, row(""","profile_id":14""").profileId)
        assertEquals("standalone-bob", row(""","origin":"profile"""").label)
    }

    @Test
    fun aSeatStartedByHandKeepsClaudeCodesTitle() {
        assertEquals("An old auto-title", row(""","profile_id":null""").label)
        assertEquals("standalone-bob", Seat.from(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":1,"title":"standalone-bob","display_title":" ","status":"running"}""") as kotlinx.serialization.json.JsonObject).label)
    }
}
