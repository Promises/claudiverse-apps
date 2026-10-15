package dev.claudiverse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.app.spawn.AutoRestartViewModel
import dev.claudiverse.core.AutoRestart
import dev.claudiverse.core.AutoRestartOverride
import dev.claudiverse.core.Fleet

/**
 * Auto-restart: the server restarts, one at a time, seats running an outdated
 * Claude Code or mod once they have been idle long enough. On or off and the
 * idle time for every fleet, then each fleet's own override.
 */
@Composable
fun AutoRestartScreen(vm: AutoRestartViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "The server restarts idle seats that run an outdated Claude Code or mod, one at a time, resuming their " +
                    "conversation. Never a protected, paused, runner or busy seat, and a seat at most once an hour.",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        ui.problem?.let { p -> item { Text(p, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        val g = ui.global
        when {
            ui.loading && g == null -> item { CircularProgressIndicator(Modifier.size(24.dp)) }
            g == null -> if (ui.unsupported) item {
                Text("This server has no auto-restart yet.", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-restart", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(g.enabled, vm::setEnabled, enabled = !ui.saving)
                    }
                }
                item {
                    MinutesField("Idle for at least (minutes)", g.minIdleMinutes, enabled = !ui.saving, inheritHint = null) { m ->
                        m?.let(vm::setMinIdleMinutes)
                    }
                }
                item { HorizontalDivider() }
                item { Text("Fleets", style = MaterialTheme.typography.titleSmall) }
                if (ui.fleets.isEmpty()) item { Text("No fleets.", style = MaterialTheme.typography.bodySmall, color = muted) }
                items(ui.fleets, key = { it.fleet }) { f -> FleetRow(f, g, saving = ui.saving, onSet = { o -> vm.setFleet(f.fleet, o) }) }
            }
        }
    }
}

/** One fleet: inherit, on or off; its own idle minutes or the global ones; and what applies now. */
@Composable
private fun FleetRow(f: Fleet, global: AutoRestart, saving: Boolean, onSet: (AutoRestartOverride) -> Unit) {
    val o = f.autoRestart ?: AutoRestartOverride()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(f.fleet + if (f.paused) " (paused)" else "", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(null to "Inherit", true to "On", false to "Off").forEach { (value, label) ->
                FilterChip(o.enabled == value, { onSet(o.copy(enabled = value)) }, enabled = !saving, label = { Text(label) })
            }
        }
        MinutesField("Its own idle minutes", o.minIdleS?.let { it / 60 }, enabled = !saving, inheritHint = "inherits ${global.minIdleMinutes} min") { m ->
            onSet(o.copy(minIdleS = m?.times(60)))
        }
        Text(effectiveLine(f), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

private fun effectiveLine(f: Fleet): String {
    val e = f.autoRestartEffective ?: return "This server does not say what applies."
    return when {
        f.paused -> "Paused: never auto-restarted while paused."
        e.enabled -> "Now: on, after ${e.minIdleMinutes} min idle."
        else -> "Now: off."
    }
}

/**
 * Whole minutes, saved when typing is done (the keyboard's Done, or leaving
 * the field). With [inheritHint], an empty field means inherit (null).
 */
@Composable
private fun MinutesField(label: String, minutes: Int?, enabled: Boolean, inheritHint: String?, onSet: (Int?) -> Unit) {
    var text by remember(minutes) { mutableStateOf(minutes?.toString().orEmpty()) }
    var error by remember(minutes) { mutableStateOf<String?>(null) }
    fun commit() {
        val t = text.trim()
        val m = t.toIntOrNull()
        when {
            t.isEmpty() && inheritHint != null -> if (minutes != null) onSet(null)
            m == null || m * 60 < AutoRestart.MIN_IDLE_S || m * 60 > AutoRestart.MAX_IDLE_S ->
                error = "Whole minutes from ${AutoRestart.MIN_IDLE_S / 60} to ${AutoRestart.MAX_IDLE_S / 60}"
            m != minutes -> { error = null; onSet(m) }
        }
    }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        text, { text = it.filter(Char::isDigit); error = null },
        label = { Text(label) },
        singleLine = true, enabled = enabled, isError = error != null,
        // A placeholder only shows while the field has focus: say what empty means below it instead.
        supportingText = (error ?: inheritHint?.takeIf { text.isEmpty() }?.let { "Empty: $it" })?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.width(260.dp).onFocusChanged { s -> if (focused && !s.isFocused) commit(); focused = s.isFocused },
    )
}
