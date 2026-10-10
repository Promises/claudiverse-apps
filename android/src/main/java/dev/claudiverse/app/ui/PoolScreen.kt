package dev.claudiverse.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.claudiverse.core.ApiException
import dev.claudiverse.core.PoolChange
import dev.claudiverse.core.PoolEvents
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.core.AnthropicAccount
import dev.claudiverse.core.Incidents
import dev.claudiverse.core.Pool
import dev.claudiverse.core.relativeTime
import dev.claudiverse.core.parseServerTime

/** Server health, the Anthropic account pool, and the 5xx incident log. */
data class PoolView(val health: Int?, val pool: Pool, val incidents: Incidents?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoolScreen(vm: RemoteViewModel<PoolView>, events: PoolEvents, makePreferred: suspend (Long) -> Unit) {
    val remote by vm.remote.collectAsStateWithLifecycle()
    // The server announces every change to the order or the account in use: read the pool again then.
    DisposableEffect(events) {
        events.start()
        onDispose { events.stop() }
    }
    val change by events.latest.collectAsStateWithLifecycle()
    LaunchedEffect(change) { if (change != null) vm.refresh() }
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    RemoteContent(remote, vm::refresh) { view ->
        PullToRefreshBox(isRefreshing = remote.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Server", style = MaterialTheme.typography.titleSmall)
                        when (view.health) {
                            200 -> Pill("health 200", StatusColors.good)
                            null -> Pill("health unknown", StatusColors.muted)
                            else -> Pill("health ${view.health}", StatusColors.bad)
                        }
                        view.incidents?.let {
                            Pill("${it.total} incidents", if (it.total == 0) StatusColors.good else StatusColors.bad)
                        }
                    }
                }
                change?.let { c -> item { Text(changeLine(c, view.pool), style = MaterialTheme.typography.bodySmall) } }
                problem?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.bad) } }
                items(view.pool.ordered, key = { it.id }) { a ->
                    AccountCard(a, view.pool) {
                        scope.launch {
                            problem = runCatching { makePreferred(a.id) }.fold(
                                { vm.refresh(); null },
                                { e ->
                                    if ((e as? ApiException)?.httpStatus == 404) "${a.label} not made preferred: the server does not know this account, or cannot set a preferred account yet."
                                    else "${a.label} not made preferred: ${e.message}"
                                },
                            )
                        }
                    }
                }
                item { AssignmentSummary(view.pool) }
                view.incidents?.takeIf { it.latest.isNotEmpty() }?.let { inc ->
                    item { Text("Latest incidents since ${localTime(inc.since)}", style = MaterialTheme.typography.titleSmall) }
                    items(inc.latest.reversed()) {
                        Text(it.summary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** "Preferred account changed to Work by op@example.com · 2m ago", as the Mac words it. */
private fun changeLine(c: PoolChange, pool: Pool): String {
    fun name(id: Long?) = pool.accounts.firstOrNull { it.id == id }?.label
    val what = when (c.change) {
        "preferred" -> "Preferred account changed" + (name(c.preferredId)?.let { " to $it" } ?: "") + (c.by?.let { " by $it" } ?: "")
        "upgrade" -> "Seats moved back to " + (name(c.currentId) ?: "a better account")
        "degrade" -> "Seats moved to " + (name(c.currentId) ?: "a fallback account")
        "cooling" -> "An account cooled down after a rate limit"
        else -> "Pool changed (${c.change})"
    }
    return what + (c.at?.let { " · ${relativeTime(it)}" } ?: "")
}

@Composable
private fun AccountCard(a: AnthropicAccount, pool: Pool, onMakePreferred: () -> Unit) {
    val preferred = a.id == pool.preferredId
    val inUse = pool.inUseId
    var confirming by remember { mutableStateOf(false) }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Make ${a.label} the preferred account?") },
        text = {
            Text("Pooled seats move to ${a.label} on their next request. While it is cooling down or needs re-auth, the pool keeps using the next account and comes back to it when it recovers.")
        },
        confirmButton = { TextButton(onClick = { confirming = false; onMakePreferred() }) { Text("Make preferred") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (preferred) Pill("Preferred", MaterialTheme.colorScheme.primary)
                if (inUse == a.id) Pill("In use", StatusColors.good)
                Pill(a.status.replace('_', ' '), if (a.status == "available") StatusColors.good else StatusColors.bad)
                if (a.tokenValid == false) Pill("token invalid", StatusColors.bad)
            }
            if (preferred && inUse != null && inUse != a.id) pool.accounts.firstOrNull { it.id == inUse }?.let { other ->
                Text("Preferred, but seats use ${other.label} now: the pool comes back here when this account recovers.",
                    style = MaterialTheme.typography.bodySmall)
            }
            a.cooldownUntil?.let {
                Text("Cooling down until ${localTime(it)} (${relativeTime(it)})", style = MaterialTheme.typography.bodySmall)
            }
            // The reason outlives the cooldown on the server; it is history once the account is available.
            a.cooldownReason?.let {
                Text((if (a.cooldownUntil == null) "Last cooldown: " else "Reason: ") + it, style = MaterialTheme.typography.bodySmall)
            }
            a.limits.forEach { l ->
                val pct = l.percent
                Column {
                    Row {
                        Text(l.kind.replace('_', ' '), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text(
                            (pct?.let { "$it%" } ?: "—") + (l.resetsAt?.let { " · resets ${relativeTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (pct != null) LinearProgressIndicator(
                        progress = { pct / 100f },
                        color = when (l.severity) {
                            "critical" -> StatusColors.bad
                            "warning" -> StatusColors.busy
                            else -> StatusColors.good
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usage read ${relativeTime(a.usageCheckedAt)} · priority ${a.priority ?: "—"}", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f))
                if (!preferred) TextButton(onClick = { confirming = true }) { Text("Make preferred…") }
            }
        }
    }
}

/**
 * Which account seats were last handed. These are assignment records, not a
 * live map: a seat stays on its last account until it asks again.
 */
@Composable
private fun AssignmentSummary(pool: Pool) {
    val latest = pool.seats.groupBy { it.title }.mapValues { (_, v) -> v.maxBy { parseServerTime(it.assignedAt) ?: 0 } }.values
    val dayAgo = System.currentTimeMillis() - 24 * 3_600_000
    val recent = latest.filter { (parseServerTime(it.assignedAt) ?: 0) > dayAgo }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last assignment per seat, past 24 h (${recent.size} seats)", style = MaterialTheme.typography.titleSmall)
            recent.groupingBy { it.label ?: "?" }.eachCount().forEach { (label, n) ->
                Text("$label: $n", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
