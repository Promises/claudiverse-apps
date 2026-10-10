package dev.claudiverse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.claudiverse.core.FleetTask
import dev.claudiverse.core.relativeTime

/** Task states as the server names them; "active" is the ones still in play. */
private val ACTIVE = listOf("RUNNING", "READY", "DRAFT")
private val FILTERS = listOf("active", "RUNNING", "READY", "DRAFT", "DONE", "SUPERSEDED")

/**
 * Fleet tasks, filtered by state on the server: the full list runs to
 * thousands of rows with bodies, too much to pull to a phone to filter here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(load: suspend (state: String) -> List<FleetTask>) {
    var filter by rememberSaveable { mutableStateOf("active") }
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    val current = filter
    val vm = viewModel(key = "tasks-$current") {
        val states = if (current == "active") ACTIVE else listOf(current)
        // Finished states only grow; poll the live ones.
        RemoteViewModel(pollMs = if (current == "active") 30_000 else null) {
            states.flatMap { load(it) }.sortedWith(compareBy<FleetTask> { ACTIVE.indexOf(it.state) }.thenByDescending { it.id })
        }
    }
    val remote by vm.remote.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FILTERS.forEach { f ->
                val n = remote.data?.size?.takeIf { filter == f }
                FilterChip(filter == f, { filter = f }, label = { Text(if (n != null) "$f ($n)" else f) })
            }
        }
        RemoteContent(remote, vm::refresh) { shown ->
            PullToRefreshBox(isRefreshing = remote.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { t ->
                        TaskRow(t, expanded == t.id) { expanded = if (expanded == t.id) null else t.id }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: FleetTask, open: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(t.state, stateColor(t.state))
            Text("#${t.id}", style = MaterialTheme.typography.labelMedium)
            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 6 else 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val meta = listOfNotNull(
            t.fleet, t.lane, t.claimedBy?.let { "→ $it" }, t.priority?.let { "p$it" }, relativeTime(t.insertedAt).ifEmpty { null },
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.labelSmall)
        if (open) t.body?.let { MarkdownText(it, Modifier.padding(top = 6.dp)) }
    }
}

private fun stateColor(state: String) = when (state) {
    "RUNNING" -> StatusColors.busy
    "READY" -> StatusColors.info
    "DONE" -> StatusColors.good
    "DRAFT" -> StatusColors.muted
    else -> StatusColors.muted
}
