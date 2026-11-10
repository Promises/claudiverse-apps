package dev.claudiverse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.claudiverse.app.machines.MachineState
import dev.claudiverse.app.machines.MachinesViewModel
import dev.claudiverse.app.machines.rebootActions
import dev.claudiverse.core.Host
import dev.claudiverse.core.HostReboot
import dev.claudiverse.core.RebootBrief
import dev.claudiverse.core.RebootWording
import dev.claudiverse.core.relativeTime

/**
 * The machines seats run on: online or not, their host agent and seats, and,
 * where the agent has it (0.23+), their safe reboot: Prepare reboot before
 * rebooting one, Reopen seats after one that was not prepared.
 */
@Composable
fun MachinesScreen(vm: MachinesViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<Pair<String, RebootBrief>?>(null) }
    confirming?.let { (host, brief) ->
        ReopenConfirm(host, brief, onConfirm = { confirming = null; vm.reopen(host) }, onDismiss = { confirming = null })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.problem?.let { p -> item { ErrorBanner(p, onRetry = vm::reload) } }
        if (ui.loading && ui.hosts.isEmpty()) item { CircularProgressIndicator(Modifier.size(24.dp)) }
        items(ui.hosts, key = { it.name }) { h ->
            // From the list, read again on every "host_reboot": never an older event's copy.
            MachineCard(h, h.reboot, ui.machines[h.name] ?: MachineState(),
                onPrepare = { vm.prepare(h.name) }, onReopen = { brief -> confirming = h.name to brief })
        }
    }
}

@Composable
private fun MachineCard(h: Host, reboot: HostReboot?, m: MachineState, onPrepare: () -> Unit, onReopen: (RebootBrief) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val actions = rebootActions(h.online, reboot)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (h.online) Pill("online", StatusColors.good) else Pill("offline", StatusColors.bad)
            }
            Text(
                listOfNotNull(RebootWording.plural(h.seats, "seat"), h.version?.let { "host agent $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            // An older agent (reboot null) or an offline machine: no safe reboot, nothing shown for it.
            if (reboot == null || !h.online) return@Column
            reboot.prepared?.let { p -> Text(RebootWording.prepared(h.name, p, relativeTime(p.takenAt)), style = MaterialTheme.typography.bodyMedium) }
            m.summary?.let { s -> RebootWording.preparedNotes(s).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) } }
            if (actions.reopening || m.pendingReopen != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Reopening…", style = MaterialTheme.typography.bodySmall)
            }
            m.reopened?.let { d ->
                if (!d.ok || d.result == null) Text(RebootWording.failed(d), style = MaterialTheme.typography.bodySmall, color = StatusColors.bad)
                else {
                    Text(RebootWording.reopened(d.result!!), style = MaterialTheme.typography.bodyMedium)
                    d.result!!.outcomes.filter { it.outcome != "started" || it.busy }.forEach {
                        Text(RebootWording.outcome(it), style = MaterialTheme.typography.bodySmall,
                            color = if (it.outcome == "failed") StatusColors.bad else muted)
                    }
                }
            }
            reboot.lastReopen?.takeIf { m.reopened == null }?.let { r ->
                Text(RebootWording.lastReopen(r, relativeTime(r.reopenedAt)), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            m.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (actions.canPrepare) OutlinedButton(onClick = onPrepare, enabled = !m.preparing && m.pendingReopen == null) {
                    Text(if (m.preparing) "Preparing…" else "Prepare reboot")
                }
                actions.reopen?.let { brief ->
                    OutlinedButton(onClick = { onReopen(brief) }, enabled = m.pendingReopen == null) { Text(RebootWording.reopenButton(brief)) }
                }
            }
        }
    }
}

/** Reopen what an unprepared reboot left: the machine's list, each seat named, the mid-turn ones marked. */
@Composable
private fun ReopenConfirm(host: String, brief: RebootBrief, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reopen seats on $host?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(RebootWording.reopenConfirm(host, brief, relativeTime(brief.checkedAt ?: brief.takenAt)))
                brief.seats.forEach { s -> Text("• ${s.title}" + if (s.busy) " (mid-turn)" else "", style = MaterialTheme.typography.bodySmall) }
                brief.runners.forEach { r -> Text("• runner $r", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(RebootWording.reopenAction(brief)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
