package dev.claudiverse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.app.spawn.RestartIdleState
import dev.claudiverse.app.spawn.RestartIdleViewModel
import dev.claudiverse.core.RestartPlan

/**
 * Restart idle seats onto their machine's current Claude Code: choose the
 * machine (or all), only outdated seats or every idle one, and which fleets
 * (paused ones left out until ticked in). The plan shows first, each seat with
 * what would happen to it; then a confirmation with the count; then each
 * seat's progress as the server works through them one at a time.
 */
@Composable
fun RestartIdleScreen(vm: RestartIdleViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    if (confirming) ConfirmRestart(ui, onConfirm = { confirming = false; vm.start() }, onDismiss = { confirming = false })

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.batchId == null) {
                item { Options(ui, vm) }
                item { HorizontalDivider() }
                when {
                    ui.planning -> item { CircularProgressIndicator(Modifier.size(24.dp)) }
                    ui.plan.isEmpty() -> item {
                        Text(ui.problem ?: "No idle seats to restart with these choices.", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> items(ui.plan, key = { it.sessionId }) { p ->
                        PlanRow(p, selected = p.sessionId in ui.selected, onToggle = { vm.toggleSeat(p.sessionId) })
                    }
                }
            } else {
                item { BatchSummary(ui) }
                items(ui.rows, key = { it.plan.sessionId }) { r -> ProgressRow(r.plan, r.status, r.event?.reason ?: r.event?.note) }
            }
        }
        if (ui.batchId == null) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.problem?.takeIf { ui.plan.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = { confirming = true },
                    enabled = ui.selected.isNotEmpty() && !ui.planning && !ui.starting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ui.starting) "Starting…" else "Restart ${ui.selected.size} seat${if (ui.selected.size == 1) "" else "s"}…") }
            }
        }
    }
}

@Composable
private fun Options(ui: RestartIdleState, vm: RestartIdleViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Machine", style = MaterialTheme.typography.labelMedium, color = muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All machines", ui.host == null) { vm.pickHost(null) }
            ui.hosts.filter { it.online }.forEach { h ->
                Chip(h.name + (h.spawn?.version?.let { " · $it" } ?: ""), ui.host == h.name) { vm.pickHost(h.name) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only outdated seats", style = MaterialTheme.typography.bodyMedium)
                Text("Off: every idle seat, already current ones too", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Switch(ui.onlyOutdated, vm::setOnlyOutdated)
        }
        if (ui.fleets.isNotEmpty()) {
            Text("Fleets", style = MaterialTheme.typography.labelMedium, color = muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.fleets.forEach { f ->
                    Chip(f.fleet + if (f.paused) " (paused)" else "", f.fleet !in ui.excludedFleets) { vm.toggleFleet(f.fleet) }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null)
}

/** One seat in the plan: ticked to restart; a skip says why, a protected seat carries a lock and starts unticked. */
@Composable
private fun PlanRow(p: RestartPlan, selected: Boolean, onToggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable(enabled = !p.skipped, onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, { onToggle() }, enabled = !p.skipped)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.isProtected) Icon(Icons.Filled.Lock, contentDescription = "Protected", modifier = Modifier.size(16.dp), tint = StatusColors.bad)
                Text(p.title, style = MaterialTheme.typography.titleSmall,
                    color = if (p.skipped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                Pill(actionLabel(p.action), if (p.skipped) StatusColors.muted else if (p.action == RestartPlan.ADOPT) StatusColors.info else StatusColors.good)
            }
            Text(
                listOfNotNull(p.fleet, p.host, versions(p)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            if (p.skipped) p.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
    }
}

private fun actionLabel(action: String) = when (action) {
    RestartPlan.RESTART -> "restart"
    RestartPlan.ADOPT -> "adopt"
    else -> "skip"
}

private fun versions(p: RestartPlan): String? = when {
    p.clientVersion != null && p.targetVersion != null && p.clientVersion != p.targetVersion -> "${p.clientVersion} → ${p.targetVersion}"
    else -> p.clientVersion ?: p.targetVersion
}

@Composable
private fun ConfirmRestart(ui: RestartIdleState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val n = ui.selected.size
    val protectedCount = ui.protectedSelected
    var protectedOk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restart $n seat${if (n == 1) "" else "s"}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "One at a time, each idle seat is stopped and started again on its machine's current Claude Code, resuming its " +
                        "conversation. A seat outside tmux is adopted into a tmux seat. A seat that is working when its turn comes is skipped.",
                )
                if (protectedCount > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(protectedOk, { protectedOk = it })
                    Text("$protectedCount of them ${if (protectedCount == 1) "is" else "are"} protected. Restart anyway.",
                        color = StatusColors.bad, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = protectedCount == 0 || protectedOk) { Text("Restart") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BatchSummary(ui: RestartIdleState) {
    val b = ui.batch
    val done = b?.done == true
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!done) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(if (done) "Done" else "Restarting, one at a time…", style = MaterialTheme.typography.titleMedium)
        }
        b?.counts?.filterValues { it > 0 }?.takeIf { it.isNotEmpty() }?.let { counts ->
            Text(counts.entries.joinToString(" · ") { (k, v) -> "$v $k" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A seat in a running batch: waiting its turn, started, then how it ended, with why when it did not. */
@Composable
private fun ProgressRow(p: RestartPlan, status: String, detail: String?) {
    val (label, color) = when (status) {
        "restarted", "adopted" -> status to StatusColors.good
        "failed" -> status to StatusColors.bad
        "started" -> "restarting" to StatusColors.busy
        "skipped" -> status to StatusColors.muted
        else -> "waiting" to StatusColors.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(label, color)
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
