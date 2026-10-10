package dev.claudiverse.core

/** What the seat list is filtered and grouped by. */
data class SeatFilters(
    val text: String = "",
    val runningOnly: Boolean = true,
    /** Seats cv-runner started are ephemeral workers: hidden unless asked for. */
    val hideRunner: Boolean = true,
    /** One machine, or null for all. */
    val host: String? = null,
    val groupByFleet: Boolean = true,
)

data class SeatGroup(val fleet: String?, val seats: List<Seat>) {
    val busy: Int get() = seats.count { it.activity == "busy" }
}

/** One machine in the machine menu: seats shown with the current filters, and runner seats hidden from it. */
data class HostCount(val host: String?, val visible: Int, val hiddenRunners: Int)

data class SeatList(
    val groups: List<SeatGroup>,
    val total: Int,
    val shown: Int,
    val busy: Int,
    /** Runner seats the runner filter hides (on the selected machine). */
    val runnersHidden: Int,
    /** "All machines" first, then each known host. Empty when no seat reports a host. */
    val hosts: List<HostCount>,
)

/**
 * The seat list as the screen shows it, kept apart from the UI so it can be
 * tested. Order inside a group: running first, then most recent activity.
 * Fleets are named groups in name order; seats with no fleet come last.
 */
fun seatList(all: List<Seat>, f: SeatFilters): SeatList {
    val seats = withoutSuperseded(all)
    fun base(s: Seat) =
        (!f.runningOnly || s.status == "running") &&
            (f.text.isBlank() || s.label.contains(f.text, true) || s.title.contains(f.text, true))

    val onHost = seats.filter { base(it) && (f.host == null || it.host == f.host) }
    val shown = onHost.filter { !f.hideRunner || !it.isRunner }
        .sortedWith(compareBy<Seat> { it.status != "running" }.thenByDescending { parseServerTime(it.lastActivityAt) ?: 0 })

    val groups = if (f.groupByFleet) {
        shown.groupBy { it.fleet }.entries
            .sortedWith(compareBy({ it.key == null }, { it.key }))
            .map { SeatGroup(it.key, it.value) }
    } else listOf(SeatGroup(null, shown))

    val knownHosts = seats.mapNotNull { it.host }.distinct().sorted()
    val hosts = if (knownHosts.isEmpty()) emptyList() else (listOf<String?>(null) + knownHosts).map { h ->
        val pool = seats.filter { base(it) && (h == null || it.host == h) }
        val visible = pool.count { !f.hideRunner || !it.isRunner }
        HostCount(h, visible, pool.size - visible)
    }

    return SeatList(
        groups = groups.filter { it.seats.isNotEmpty() },
        total = seats.size,
        shown = shown.size,
        busy = shown.count { it.activity == "busy" },
        runnersHidden = if (f.hideRunner) onHost.count { it.isRunner } else 0,
        hosts = hosts,
    )
}

/**
 * Drops stopped rows whose conversation a newer row continues. A resumed seat
 * registers as a new row and the old one stays stopped: listing both shows the
 * same conversation twice, and resuming the old one would only be refused.
 */
fun withoutSuperseded(seats: List<Seat>): List<Seat> {
    val newest = seats.filter { it.conversation != null }.groupBy { it.conversation }.mapValues { (_, rows) -> rows.maxOf { it.id } }
    return seats.filter { s -> s.status == "running" || s.conversation == null || newest[s.conversation] == s.id }
}
