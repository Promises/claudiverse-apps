package dev.claudiverse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.core.HumanRequest
import dev.claudiverse.core.Seat
import dev.claudiverse.core.QueuedMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.claudiverse.core.seatList
import dev.claudiverse.core.relativeTime
import dev.claudiverse.core.SeatGroup
import dev.claudiverse.core.SeatFilters
import dev.claudiverse.core.HostCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeatsScreen(
    vm: RemoteViewModel<List<Seat>>,
    /** Off by default: stopped seats are fetched only while the Running filter is off. */
    includeStopped: MutableStateFlow<Boolean>,
    queued: StateFlow<List<QueuedMessage>>,
    needsYou: StateFlow<Map<Long, HumanRequest>>,
    onOpen: (Seat) -> Unit,
) {
    val asking by needsYou.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val queuedBySeat by queued.collectAsStateWithLifecycle()
    val queuedCount = queuedBySeat.groupingBy { it.seatId }.eachCount()
    var text by rememberSaveable { mutableStateOf("") }
    val runningOnly = !includeStopped.collectAsStateWithLifecycle().value
    var hideRunner by rememberSaveable { mutableStateOf(true) }
    var host by rememberSaveable { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf(true) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    // Stay at the top while the user is there. The list re-sorts on every
    // refresh (active seats rise), and LazyColumn keeps the old top row in
    // view, which left the screen scrolled past the busy seats. Only a drag
    // unpins it; reaching the top again re-pins.
    //
    // Both survive a trip into a seat and back: the pin is saved with the
    // list's position, and "at the top" is read from that position, not from
    // canScrollBackward, which reads false until the list has laid out again
    // and so re-pinned and jumped to the top on every return.
    val listState = rememberLazyListState()
    var pinnedTop by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) pinnedTop = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
        }.collect { (scrolling, atTop) ->
            if (!scrolling && atTop) pinnedTop = true
        }
    }

    RemoteContent(remote, vm::refresh) { seats ->
        val filters = SeatFilters(text, runningOnly, hideRunner, host, group)
        val list = seatList(seats, filters)
        // Chips that differ from the defaults: flagged on the button while the chips are hidden.
        val changed = filters.copy(text = "") != SeatFilters()
        val order = list.groups.flatMap { g -> g.seats.map { it.id } }
        LaunchedEffect(order) { if (pinnedTop) listState.scrollToItem(0) }
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                text, { text = it }, singleLine = true, placeholder = { Text("Filter") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        BadgedBox(badge = { if (changed && !showFilters) Badge() }) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = if (showFilters) "Hide filters" else "Show filters",
                                tint = if (showFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (showFilters) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToggleChip("Running", runningOnly, offIcon = null) {
                    includeStopped.value = runningOnly
                    vm.refresh()
                }
                ToggleChip("Hide runner", hideRunner, offIcon = Icons.Filled.SmartToy) { hideRunner = !hideRunner }
                if (list.hosts.isNotEmpty()) MachineChip(list.hosts, host) { host = it }
                ToggleChip("Group by fleet", group, offIcon = Icons.Filled.Workspaces) { group = !group }
            }
            Text(
                buildString {
                    append("${list.shown} of ${list.total} · ${list.busy} busy")
                    if (list.runnersHidden > 0) append(" · ${list.runnersHidden} runner hidden")
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
            )
            PullToRefreshBox(isRefreshing = remote.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                if (list.shown == 0) {
                    EmptySeats(host, list.runnersHidden, onShowRunners = { hideRunner = false })
                } else LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    // A single unnamed group needs no header: there is nothing to group yet.
                    val headers = group && !(list.groups.size == 1 && list.groups[0].fleet == null)
                    list.groups.forEach { g ->
                        val key = g.fleet ?: ""
                        val isCollapsed = key in collapsed
                        if (headers) item(key = "fleet:$key") {
                            FleetHeader(g, isCollapsed) { collapsed = if (isCollapsed) collapsed - key else collapsed + key }
                        }
                        if (!isCollapsed) items(g.seats, key = { it.id }) { seat ->
                            SeatRow(seat, queuedCount[seat.id] ?: 0, asking[seat.id], onClick = { onOpen(seat) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

/** A filter chip with a check when on, as the design draws them; [offIcon] shows when off. */
@Composable
private fun ToggleChip(label: String, on: Boolean, offIcon: ImageVector?, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = (if (on) Icons.Filled.Check else offIcon)?.let { icon ->
            { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
        },
    )
}

@Composable
private fun MachineChip(hosts: List<HostCount>, selected: String?, onPick: (String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(selected ?: "All machines") },
            leadingIcon = { Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            hosts.forEach { h ->
                val dim = h.visible == 0
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.host ?: "All machines", modifier = Modifier.weight(1f),
                                color = if (dim) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                            Text(
                                if (h.hiddenRunners > 0) "${h.visible} (+${h.hiddenRunners} runner)" else "${h.visible}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    },
                    leadingIcon = {
                        Box(Modifier.size(20.dp)) {
                            if (selected == h.host) Icon(Icons.Filled.Check, contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = { onPick(h.host); open = false },
                )
            }
        }
    }
}

@Composable
private fun FleetHeader(g: SeatGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(width = 0.5.dp, color = line)
            .clickable(onClick = onToggle)
            .padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, contentDescription = if (collapsed) "Expand" else "Collapse",
            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Filled.Workspaces, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(g.fleet ?: "No fleet", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("${g.seats.size} seat${if (g.seats.size == 1) "" else "s"} · ${g.busy} busy",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptySeats(host: String?, runnersHidden: Int, onShowRunners: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.SmartToy, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text(
            "No seats on ${host ?: "any machine"}" + if (runnersHidden > 0) " — $runnersHidden runner seats hidden" else "",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        if (runnersHidden > 0) Button(onClick = onShowRunners) { Text("Show runner seats") }
    }
}

@Composable
private fun SeatRow(seat: Seat, queued: Int, request: HumanRequest?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityPill(seat.status, seat.activity)
            if (request != null) Pill("needs you", StatusColors.attention)
            if (seat.isRunner) OutlinedPill("runner")
            if (queued > 0) Pill("$queued queued", StatusColors.info)
            // Shells, monitors, subagents and workflows it runs in the background, as the server counts them.
            if (seat.backgroundRunning > 0) OutlinedPill("⚙ ${seat.backgroundRunning}")
            // Name and bell share the free width: the name takes all of it but
            // the bell's, and the bell sits right after the name.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(seat.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                // Only opted-in seats can page the operator: mark them so it is clear who can.
                if (seat.canNotify) Icon(Icons.Filled.NotificationsActive, contentDescription = "Can notify you",
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            Text(relativeTime(seat.lastActivityAt), style = MaterialTheme.typography.labelSmall)
        }
        request?.let {
            Text(it.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val sub = listOfNotNull(seat.title.takeIf { it != seat.label }, seat.model, seat.type).joinToString(" · ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // The machine leads the second line, in Frost, ahead of the model.
            seat.host?.let { h ->
                Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(h, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
            }
            val rest = if (seat.host != null && sub.isNotEmpty()) "· $sub" else sub
            if (rest.isNotEmpty()) Text(rest, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OutlinedPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
fun ActivityPill(status: String?, activity: String?) {
    when {
        status != null && status != "running" -> Pill(status, StatusColors.muted)
        activity == "busy" -> Pill("busy", StatusColors.busy)
        activity == "idle" -> Pill("idle", StatusColors.good)
        // Neither known yet: the seat's details have not loaded (the first
        // fetch can hang while the VPN comes up), which is not an unknown state.
        activity == null && status == null -> Pill("loading", StatusColors.muted)
        else -> Pill(activity ?: status ?: "?", StatusColors.muted)
    }
}
