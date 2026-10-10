package dev.claudiverse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.app.profiles.ProfileEditorState
import dev.claudiverse.app.profiles.ProfileEditorViewModel
import dev.claudiverse.app.profiles.ProfilesViewModel
import dev.claudiverse.core.Profile
import dev.claudiverse.core.ProfileFields
import dev.claudiverse.core.ProfileStart
import dev.claudiverse.core.SessionChoices
import dev.claudiverse.core.parseServerTime
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// --- list ---

/** Seat profiles: seats the server starts on a machine, with their status. As the Mac's Profiles section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(vm: ProfilesViewModel, onOpen: (Long) -> Unit, onNew: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            if (profiles.isEmpty()) Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No profiles", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A profile is a seat the server starts on a machine: its folder, fleet, model and MCP servers.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(profiles, key = { it.id }) { p ->
                    ProfileRow(p) { onOpen(p.id) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New profile") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun ProfileRow(p: Profile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).background(statusColor(p.status), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(p.fields.machine, p.fields.fleet, p.fields.role).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(statusLabel(p), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        if (p.restartNeeded) Icon(Icons.Filled.Autorenew, contentDescription = "Edited since its seat started: restart to apply",
            tint = StatusColors.attention, modifier = Modifier.size(20.dp))
    }
}

private fun statusColor(status: String): Color = when (status) {
    "running" -> StatusColors.good
    "starting" -> StatusColors.busy
    // Started on its machine, but the seat never registered: something is up there that needs a look.
    "unregistered" -> StatusColors.attention
    "failed" -> StatusColors.bad
    else -> StatusColors.muted
}

/** "running", "machine offline", "start refused: outside_roots: …" */
private fun statusLabel(p: Profile): String = when (p.status) {
    "offline" -> "machine offline"
    "unregistered" -> "started, not registered"
    "failed" -> p.lastStart?.let { "start ${it.outcome}" + (it.reason?.let { r -> ": $r" } ?: "") } ?: "failed"
    else -> p.status
}

/**
 * The last start as history, with its time: it stays "started" after the seat
 * stops. "Last start: started 16:04:16 (took 7 s)".
 */
private fun lastStartLine(last: ProfileStart): String = buildString {
    append("Last start: ").append(last.outcome)
    val at = parseServerTime(last.at)
    if (at != null) {
        val today = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_YEAR) ==
            Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        append(' ').append(SimpleDateFormat(if (today) "HH:mm:ss" else "d MMM HH:mm:ss", Locale.getDefault()).format(at))
        val asked = parseServerTime(last.requestedAt)
        if (last.outcome != "starting" && asked != null && asked < at) append(" (took ${(at - asked + 500) / 1000} s)")
    }
    parseServerTime(last.stoppedAt)?.let { append(" · stopped ").append(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(it)) }
    last.reason?.let { append(" — ").append(it) }
}

// --- editor ---

/** One profile: its status and controls, then its settings to edit. Also the form for a new one. */
@Composable
fun ProfileEditorScreen(vm: ProfileEditorViewModel, onClose: () -> Unit, onOpenSeat: (Long, String) -> Unit, onCreated: (Long) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.gone) { if (ui.gone) onClose() }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${ui.name}?") },
        text = { Text("The server forgets its settings. Its seats' history stays.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete", color = StatusColors.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.profile?.let { StatusCard(it, ui.busy, vm::control, onOpenSeat) }
            if (ui.conflicts.isNotEmpty()) ConflictCard(ui, vm::takeTheirs)
            SeatSection(ui, vm)
            LaunchSection(ui, vm)
            StartingSection(ui, vm)
            if (!ui.isNew) OutlinedButton(onClick = { confirmDelete = true }, enabled = !ui.busy) { Text("Delete profile…", color = StatusColors.bad) }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                ui.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        // The keyboard would cover the result line under it.
                        onClick = { focus.clearFocus(); vm.save(onCreated) },
                        enabled = !ui.busy && ui.edited && ui.draft.machine.isNotBlank() && ui.draft.folder.isNotBlank() &&
                            (!ui.isNew || ui.name.isNotBlank()),
                    ) { Text(if (ui.isNew) "Create" else "Save") }
                    if (!ui.isNew) OutlinedButton(onClick = vm::revert, enabled = ui.edited && !ui.busy) { Text("Revert") }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(p: Profile, busy: Boolean, onControl: (String) -> Unit, onOpenSeat: (Long, String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(statusColor(p.status), CircleShape))
                Text(statusLabel(p), style = MaterialTheme.typography.titleSmall)
            }
            p.lastStart?.let { Text(lastStartLine(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (p.restartNeeded) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Autorenew, contentDescription = null, tint = StatusColors.attention, modifier = Modifier.size(18.dp))
                Text("Edited since its seat started: restart to apply.", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val seat = p.seat
                // An unregistered seat is up on its machine without a row: it can only be stopped.
                if (p.canStop && !p.isRunning) {
                    OutlinedButton(onClick = { onControl("stop") }, enabled = !busy) { Text("Stop", color = StatusColors.bad) }
                } else if (p.isRunning) {
                    if (seat != null) OutlinedButton(onClick = { onOpenSeat(seat, p.name) }) { Text("Open seat") }
                    OutlinedButton(onClick = { onControl("restart") }, enabled = !busy) { Text("Restart") }
                    OutlinedButton(onClick = { onControl("stop") }, enabled = !busy) { Text("Stop", color = StatusColors.bad) }
                } else Button(onClick = { onControl("start") }, enabled = !busy && p.status != "starting") { Text("Start") }
            }
        }
    }
}

@Composable
private fun ConflictCard(ui: ProfileEditorState, onTakeTheirs: (String) -> Unit) {
    val theirs = ui.theirs ?: return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Changed elsewhere too", style = MaterialTheme.typography.titleSmall)
            Text(
                "Someone saved this profile after you opened it. Their changes are in and yours are kept; these fields both of you changed show yours.",
                style = MaterialTheme.typography.bodySmall,
            )
            ui.conflicts.forEach { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(fieldLabel(field), style = MaterialTheme.typography.labelLarge)
                        Text("theirs: ${fieldValue(theirs, field)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onTakeTheirs(field) }) { Text("Take theirs") }
                }
            }
        }
    }
}

@Composable
private fun SeatSection(ui: ProfileEditorState, vm: ProfileEditorViewModel) {
    val d = ui.draft
    Section("Seat") {
        if (ui.isNew) {
            OutlinedTextField(ui.name, vm::setName, label = { Text("Name") }, placeholder = { Text("my-seat") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = "name" in ui.errors)
            Errors(ui, "name")
        } else Text(ui.name, style = MaterialTheme.typography.titleMedium)
        PickerField(
            "Machine", d.machine.ifEmpty { "Choose…" },
            options = ui.hosts.map { it.name to (it.name + if (it.online) "" else " (offline)") },
        ) { m -> vm.edit("machine") { it.copy(machine = m) } }
        if (d.machine.isNotEmpty() && ui.hosts.isNotEmpty() && ui.host == null) Hint("${d.machine} has no host agent")
        Errors(ui, "machine")
        OutlinedTextField(d.folder, { f -> vm.edit("folder") { it.copy(folder = f) } }, label = { Text("Folder") },
            placeholder = { Text("/Users/…/project") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = "folder" in ui.errors)
        ui.host?.roots?.takeIf { it.isNotEmpty() }?.let { roots ->
            Hint("The seat's folder must be inside one of ${d.machine}'s allowed folders:")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                roots.forEach { r -> FilterChip(selected = d.folder == r, onClick = { vm.edit("folder") { it.copy(folder = r) } }, label = { Text(r) }) }
            }
        }
        Errors(ui, "folder")
        OutlinedTextField(d.fleet.orEmpty(), { f -> vm.edit("fleet") { it.copy(fleet = f.ifBlank { null }) } }, label = { Text("Fleet") },
            placeholder = { Text("none") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = "fleet" in ui.errors)
        Errors(ui, "fleet")
        Chips("Role", ProfileFields.ROLES.map { it to it }, d.role) { r -> vm.edit("role") { it.copy(role = r) } }
        SwitchRow("May page the operator", d.canNotify) { v -> vm.edit("can_notify") { it.copy(canNotify = v) } }
    }
}

@Composable
private fun LaunchSection(ui: ProfileEditorState, vm: ProfileEditorViewModel) {
    val d = ui.draft
    val host = ui.host
    Section("Launch") {
        MachineChoice("Runtime", d.runtime, host?.runtimes?.map { r -> r.name to (r.name + (r.version?.let { " $it" } ?: "")) }.orEmpty(), "runtimes", ui) { v ->
            vm.edit("runtime") { it.copy(runtime = v) }
        }
        Errors(ui, "runtime")
        OutlinedTextField(d.model.orEmpty(), { m -> vm.edit("model") { it.copy(model = m.ifBlank { null }) } }, label = { Text("Model") },
            placeholder = { Text("Claude Code's default") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = "model" in ui.errors)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = d.model == null, onClick = { vm.edit("model") { it.copy(model = null) } }, label = { Text("Default") })
            SessionChoices.models.forEach { m ->
                FilterChip(selected = d.model == m.id, onClick = { vm.edit("model") { it.copy(model = m.id) } }, label = { Text(m.label) })
            }
        }
        Errors(ui, "model")
        Chips("Effort", listOf("" to "Default") + ProfileFields.EFFORTS.map { it to it }, d.effort.orEmpty()) { e ->
            vm.edit("effort") { it.copy(effort = e.ifEmpty { null }) }
        }
        MachineChoice(
            "Permission mode", d.permissionMode,
            host?.modes?.map { id -> id to (SessionChoices.modes.firstOrNull { it.id == id }?.label ?: id) }.orEmpty(), "permission modes", ui,
        ) { v -> vm.edit("permission_mode") { it.copy(permissionMode = v) } }
        Errors(ui, "permission_mode")
        McpChoice(ui, vm)
    }
}

@Composable
private fun StartingSection(ui: ProfileEditorState, vm: ProfileEditorViewModel) {
    val d = ui.draft
    val kind = if (d.resume == "latest" || d.resume == "new") d.resume else "chosen"
    Section("Starting") {
        Chips("Conversation", listOf("latest" to "Continue the latest", "new" to "Start new", "chosen" to "A chosen one"), kind) { k ->
            vm.edit("resume") { it.copy(resume = if (k == "chosen") (if (kind == "chosen") it.resume else "") else k) }
        }
        if (kind == "chosen") OutlinedTextField(d.resume, { r -> vm.edit("resume") { it.copy(resume = r) } }, label = { Text("Conversation id") },
            placeholder = { Text("a Claude Code session id") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = "resume" in ui.errors)
        Errors(ui, "resume")
        SwitchRow("Start when its machine comes online", d.autostart) { v -> vm.edit("autostart") { it.copy(autostart = v) } }
        SwitchRow("Pooled login (proxy failover)", d.pooled) { v -> vm.edit("pooled") { it.copy(pooled = v) } }
        SwitchRow("Debug log", d.debug) { v -> vm.edit("debug") { it.copy(debug = v) } }
    }
}

@Composable
private fun McpChoice(ui: ProfileEditorState, vm: ProfileEditorViewModel) {
    val catalog = ui.host?.mcp.orEmpty()
    val unknown = ui.draft.mcp.filter { it !in catalog }
    Text("MCP servers", style = MaterialTheme.typography.labelLarge)
    catalog.forEach { name ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = name in ui.draft.mcp, onCheckedChange = { on ->
                vm.edit("mcp") { it.copy(mcp = if (on) it.mcp + name else it.mcp - name) }
            })
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
    }
    unknown.forEach { name ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$name: not in this machine's catalog", style = MaterialTheme.typography.bodySmall, color = StatusColors.attention, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.edit("mcp") { it.copy(mcp = it.mcp - name) } }) { Text("Remove") }
        }
    }
    if (catalog.isEmpty() && unknown.isEmpty()) Hint(if (ui.draft.machine.isEmpty()) "Choose a machine first." else "This machine's agent lists no MCP catalog yet.")
    Errors(ui, "mcp")
}

/** A choice the machine's agent lists: only what it offers, plus the current value marked when the machine lacks it. */
@Composable
private fun MachineChoice(
    title: String, value: String, options: List<Pair<String, String>>, what: String, ui: ProfileEditorState, onPick: (String) -> Unit,
) {
    if (options.isEmpty()) {
        PickerField(title, value, emptyList(), onPick)
        Hint(
            if (ui.draft.machine.isEmpty()) "Choose a machine to see its $what."
            else "${ui.draft.machine}'s agent${ui.host?.version?.let { " ($it)" } ?: ""} lists no $what yet; the profile keeps this one. Agent 0.7.0 lists them.",
        )
    } else {
        val all = if (options.any { it.first == value }) options else options + (value to "$value (not on this machine)")
        PickerField(title, all.firstOrNull { it.first == value }?.second ?: value, all, onPick)
    }
}

// --- small form parts ---

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A labelled value that opens a menu of [options] (id to label); with none, it only shows the value. */
@Composable
private fun PickerField(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = options.isNotEmpty()) { open = true }) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (options.isNotEmpty()) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) }) }
        }
    }
}

@Composable
private fun Chips(label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, text) -> FilterChip(selected = selected == id, onClick = { onPick(id) }, label = { Text(text) }) }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Errors(ui: ProfileEditorState, field: String) {
    ui.errors[field]?.takeIf { it.isNotEmpty() }?.let {
        Text(it.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = StatusColors.bad)
    }
}

/** A server field name as the form labels it. */
private fun fieldLabel(field: String): String = mapOf(
    "machine" to "Machine", "folder" to "Folder", "fleet" to "Fleet", "role" to "Role", "can_notify" to "May page the operator",
    "runtime" to "Runtime", "model" to "Model", "effort" to "Effort", "permission_mode" to "Permission mode", "mcp" to "MCP servers",
    "pooled" to "Pooled login", "autostart" to "Autostart", "resume" to "Conversation", "debug" to "Debug log",
)[field] ?: field

private fun fieldValue(f: ProfileFields, field: String): String = when (field) {
    "machine" -> f.machine
    "folder" -> f.folder
    "fleet" -> f.fleet ?: "none"
    "role" -> f.role
    "can_notify" -> if (f.canNotify) "on" else "off"
    "runtime" -> f.runtime
    "model" -> f.model ?: "default"
    "effort" -> f.effort ?: "default"
    "permission_mode" -> f.permissionMode
    "mcp" -> f.mcp.joinToString().ifEmpty { "none" }
    "pooled" -> if (f.pooled) "on" else "off"
    "autostart" -> if (f.autostart) "on" else "off"
    "resume" -> f.resume
    "debug" -> if (f.debug) "on" else "off"
    else -> ""
}
