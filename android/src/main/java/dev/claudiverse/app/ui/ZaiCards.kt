package dev.claudiverse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.claudiverse.core.ZaiKey
import dev.claudiverse.core.relativeTime
import dev.claudiverse.core.zaiWindowLabel
import dev.claudiverse.core.zaiWindowLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the Pool screen may do with Z.ai keys; null actions for a sign-in that may not change them. */
class ZaiActions(
    val add: (suspend (label: String, key: String) -> Unit)?,
    val check: (suspend (id: Long) -> Unit)?,
    val remove: (suspend (id: Long) -> Unit)?,
)

/**
 * A Z.ai Coding Plan key, as the web's Accounts page shows it: its plan, each
 * quota window (credits used of the limit, a bar, and when it resets, or
 * "spent until" then when used up), and why the last check failed if it did.
 * The server reads it every 5 minutes; the key itself never reaches the app.
 */
@Composable
fun ZaiCard(k: ZaiKey, actions: ZaiActions, onChanged: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    fun act(what: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            val problem = try {
                block(); null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "$what: ${e.message}"
            }
            busy = false
            onChanged(problem)
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove the Z.ai key \"${k.label}\"?") },
        text = { Text("Its seats keep running; only the quota display goes.") },
        confirmButton = {
            TextButton(onClick = { confirmRemove = false; actions.remove?.let { r -> act("Not removed") { r(k.id) } } }) {
                Text("Remove", color = StatusColors.bad)
            }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(k.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                k.level?.let { Pill("$it plan", MaterialTheme.colorScheme.primary) }
                Pill(k.status, if (k.ok) StatusColors.good else StatusColors.bad)
            }
            k.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.bad) }
            if (k.windows.isEmpty()) Text("No quota read yet.", style = MaterialTheme.typography.bodySmall)
            k.windows.forEach { w ->
                Column {
                    Row {
                        Text(zaiWindowLabel(w), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text(w.percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelSmall)
                    }
                    w.percent?.let { pct ->
                        LinearProgressIndicator(
                            progress = { (pct / 100f).coerceIn(0f, 1f) },
                            color = when {
                                w.spent -> StatusColors.bad
                                pct >= 80 -> StatusColors.busy
                                else -> StatusColors.good
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(zaiWindowLine(w), style = MaterialTheme.typography.bodySmall,
                        color = if (w.spent) StatusColors.bad else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Checked ${relativeTime(k.checkedAt).ifEmpty { "never" }}", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f))
                actions.check?.let { c -> TextButton(onClick = { act("Not checked") { c(k.id) } }, enabled = !busy) { Text("Check now") } }
                if (actions.remove != null) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text("Remove…") }
            }
        }
    }
}

/**
 * Add a Z.ai key: a label and the key, which the server checks with Z.ai
 * first and then keeps encrypted. The key is masked while typed and never
 * shown again.
 */
@Composable
fun AddZaiKeyDialog(add: suspend (String, String) -> Unit, onDone: (String?) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Add a Z.ai key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    key, { key = it.trim() }, label = { Text("Coding Plan API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("The server checks the key with Z.ai, then keeps it encrypted; it never comes back to the app.",
                    style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.bad) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    sending = true
                    problem = null
                    scope.launch {
                        try {
                            add(label.trim(), key)
                            key = ""
                            onDone("Z.ai key \"${label.trim()}\" added")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The server's sentence ("Z.ai refused the key (401)"); never the key itself.
                            problem = "Not added: ${e.message}"
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = !sending && label.isNotBlank() && key.isNotBlank(),
            ) { Text(if (sending) "Checking…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") } },
    )
}
