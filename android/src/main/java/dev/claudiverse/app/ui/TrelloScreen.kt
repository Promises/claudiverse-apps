package dev.claudiverse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.app.trello.TrelloViewModel
import dev.claudiverse.core.TrelloGrant
import dev.claudiverse.core.TrelloWriteRequest
import dev.claudiverse.core.relativeTime
import kotlinx.coroutines.flow.StateFlow

/**
 * Trello write access: seats' requests waiting for the operator (approve or
 * deny), the recent decisions, and the seats that may write now (revoke).
 * An approval lets that seat create, move and comment on that one board
 * until revoked; the server tells the seat either way.
 */
@Composable
fun TrelloScreen(vm: TrelloViewModel) {
    val requests by vm.requests.collectAsStateWithLifecycle()
    val grants by vm.grants.collectAsStateWithLifecycle()
    val supported by vm.supported.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var revoking by remember { mutableStateOf<TrelloGrant?>(null) }
    revoking?.let { g ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke ${g.seatTitle}'s write access?") },
            text = { Text("${g.seatTitle} can no longer create, move or comment on ${g.boardLabel}. The seat is told.") },
            confirmButton = {
                TextButton(onClick = { revoking = null; vm.revoke(g.id) }) { Text("Revoke", color = StatusColors.bad) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
    val (open, decided) = requests.partition { it.isOpen }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        notice?.let { n -> item { ErrorBanner(n, onRetry = null) } }
        if (!supported) {
            item { Text("This server has no Trello write access.", style = MaterialTheme.typography.bodyMedium) }
            return@LazyColumn
        }
        item { Section("Waiting for you") }
        if (open.isEmpty()) item { Text("No seat is asking.", style = MaterialTheme.typography.bodySmall, color = muted) }
        items(open, key = { "open:${it.id}" }) { r ->
            TrelloRequestCard(r, TrelloViewModel.requestKey(r.id) in busy, onApprove = { vm.approve(r.id) }, onDeny = { vm.deny(r.id) })
        }
        item { Section("May write now") }
        if (grants.isEmpty()) item { Text("No seat has write access.", style = MaterialTheme.typography.bodySmall, color = muted) }
        items(grants, key = { "grant:${it.id}" }) { g ->
            GrantRow(g, TrelloViewModel.grantKey(g.id) in busy, onRevoke = { revoking = g })
        }
        if (decided.isNotEmpty()) {
            item { Section("Decided") }
            items(decided, key = { "decided:${it.id}" }) { r -> TrelloRequestCard(r, busy = false, onApprove = {}, onDeny = {}) }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleSmall)

/**
 * One request: "<seat> asks for write access to <board>", why, the board's
 * link, and Approve and Deny while it is open; once decided, its outcome.
 */
@Composable
fun TrelloRequestCard(r: TrelloWriteRequest, busy: Boolean, onApprove: () -> Unit, onDeny: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.prompt, style = MaterialTheme.typography.titleMedium)
            r.reason?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
            r.boardUrl?.let { url ->
                Text(
                    url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { runCatching { uri.openUri(url) } },
                )
            }
            if (r.isOpen) {
                Text("Asked ${relativeTime(r.updatedAt ?: r.insertedAt)}. Approving lets it create, move and comment on this board until revoked.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
                    OutlinedButton(onClick = onDeny, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusColors.bad)) {
                        Text("Deny")
                    }
                }
            } else {
                val outcome = when (r.status) {
                    "approved" -> "Approved"
                    "denied" -> "Denied"
                    else -> r.status.replaceFirstChar { it.uppercase() }
                }
                Text("$outcome ${relativeTime(r.decidedAt ?: r.updatedAt)}", style = MaterialTheme.typography.bodySmall,
                    color = if (r.status == "approved") StatusColors.good else muted)
            }
        }
    }
}

@Composable
private fun GrantRow(g: TrelloGrant, busy: Boolean, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${g.seatTitle} → ${g.boardLabel}", style = MaterialTheme.typography.bodyMedium)
            Text("Granted ${relativeTime(g.grantedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRevoke, enabled = !busy) { Text("Revoke", color = StatusColors.bad) }
    }
}

/**
 * Above the seat list while seats ask for Trello write access: the newest
 * request, and how many more; tapping it opens Trello write access.
 */
@Composable
fun TrelloAskingBanner(open: StateFlow<List<TrelloWriteRequest>>, onOpen: () -> Unit) {
    val asking by open.collectAsStateWithLifecycle()
    val first = asking.firstOrNull() ?: return
    Card(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(first.prompt, style = MaterialTheme.typography.titleSmall)
            val more = asking.size - 1
            Text(if (more > 0) "and $more more · tap to review" else "Tap to review", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
