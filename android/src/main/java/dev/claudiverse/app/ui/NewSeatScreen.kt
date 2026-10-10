package dev.claudiverse.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.core.Host
import dev.claudiverse.core.FolderTab
import dev.claudiverse.core.crumbs
import dev.claudiverse.core.NewSeatState
import dev.claudiverse.app.spawn.NewSeatViewModel
import dev.claudiverse.core.relativeTime
import dev.claudiverse.core.crumbs

private val MODES = listOf("default", "acceptEdits", "plan", "auto")

/**
 * New seat (design: Android Theme.dc.html, "Spawn a seat"): machine, folder
 * (Recent or Browse; the folder you are in is the one used), title, mode and
 * an optional first prompt, then "Spawn on <host>".
 */
@Composable
fun NewSeatScreen(vm: NewSeatViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text("New seat", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Machines(ui, vm::pickHost)
            if (ui.host != null) Folder(ui, vm)
            Details(ui, vm)
        }
        SpawnBar(ui) { vm.spawn(onAccepted = onClose) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun Machines(ui: NewSeatState, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Machine")
        val hostsError = ui.hostsError
        when {
            ui.hostsLoading -> CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp))
            hostsError != null -> Text(hostsError, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            ui.hosts.isEmpty() -> Text("No machine has a host agent yet.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp))
            else -> Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { ui.hosts.forEach { MachineCard(it, it.name == ui.host) { onPick(it.name) } } }
        }
    }
}

/** A machine; offline ones are shown but cannot be picked. */
@Composable
private fun MachineCard(host: Host, selected: Boolean, onClick: () -> Unit) {
    val fg = if (host.online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(140.dp).clickable(enabled = host.online, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Computer, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Text(host.name, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(if (host.online) StatusColors.good else MaterialTheme.colorScheme.outline, CircleShape))
                Text(
                    if (host.online) "online · ${host.seats} seat${if (host.seats == 1) "" else "s"}" else "offline",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Folder(ui: NewSeatState, vm: NewSeatViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Folder")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            FolderTab.entries.forEachIndexed { i, tab ->
                SegmentedButton(
                    selected = ui.tab == tab,
                    onClick = { vm.setTab(tab) },
                    shape = SegmentedButtonDefaults.itemShape(i, FolderTab.entries.size),
                    icon = { Icon(if (tab == FolderTab.Recent) Icons.Filled.History else Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                ) { Text(tab.name) }
            }
        }
        when (ui.tab) {
            FolderTab.Recent -> Recents(ui, vm::pickRecent)
            FolderTab.Browse -> Browse(ui, vm::browse)
        }
    }
}

@Composable
private fun Recents(ui: NewSeatState, onPick: (Int) -> Unit) {
    if (ui.recents.isEmpty()) {
        Text("No recent folders on ${ui.host}: seats report their folder once restarted on the new runtime. Use Browse.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        return
    }
    Column {
        ui.recents.forEachIndexed { i, r ->
            val allowed = ui.allowed(r.path)
            val picked = allowed && i == ui.recentIndex
            Row(
                Modifier.fillMaxWidth().clickable(enabled = allowed) { onPick(i) }.alpha(if (allowed) 1f else 0.45f)
                    .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(if (picked) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
                    tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(r.path.trimEnd('/').substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
                    Text(r.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!allowed) Text("Not in ${ui.host}'s allowed folders", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(
                        r.seatsRunning.takeIf { it > 0 }?.let { "$it seat${if (it == 1) "" else "s"}" },
                        relativeTime(r.lastUsedAt).ifEmpty { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Breadcrumb, Up and the subfolders. The folder you are in is the one a spawn uses. */
@Composable
private fun Browse(ui: NewSeatState, onOpen: (String) -> Unit) {
    val l = ui.listing
    Column {
        if (l != null && l.path.isNotEmpty()) Crumbs(l.path, ui.roots, onOpen)
        ui.listingError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (l == null) return@Column
        // At a root the parent is the roots list (""); at the roots list there is no Up.
        if (l.path.isNotEmpty()) FolderRow(Icons.Filled.ArrowUpward, "Up", chevron = false) { onOpen(l.parent ?: "") }
        // The roots list holds full paths; a folder's listing holds names.
        l.dirs.forEach { d -> FolderRow(Icons.Filled.Folder, d, chevron = true) { onOpen(if (l.path.isEmpty()) d else join(l.path, d)) } }
        if (l.dirs.isEmpty()) Text(
            if (l.path.isEmpty()) "No allowed folders on ${ui.host}. Only the machine can add one: cv-host-agent add-root <folder>"
            else "No subfolders",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        if (l.truncated) Text("Only the first ${l.dirs.size} folders are shown.", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun Crumbs(path: String, roots: List<String>, onOpen: (String) -> Unit) {
    val crumbs = crumbs(path, roots)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, c ->
            val last = i == crumbs.lastIndex
            Text(c.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clickable(enabled = !last) { onOpen(c.path) }.padding(horizontal = 2.dp, vertical = 4.dp))
            if (!last) Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, chevron: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (chevron) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (chevron) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

@Composable
private fun Details(ui: NewSeatState, vm: NewSeatViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(ui.title, vm::setTitle, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permission mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODES.forEach { m ->
                    FilterChip(m == ui.mode, { vm.setMode(m) }, label = { Text(m) },
                        leadingIcon = if (m == ui.mode) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null)
                }
            }
        }
        OutlinedTextField(ui.prompt, vm::setPrompt, label = { Text("First prompt (optional)") },
            placeholder = { Text("What should this seat start on?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SpawnBar(ui: NewSeatState, onSpawn: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.spawnError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(ui.path ?: "Pick a folder", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Button(onClick = onSpawn, enabled = ui.canSpawn, modifier = Modifier.fillMaxWidth()) {
                if (ui.spawning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Spawn on ${ui.host ?: "…"}")
            }
        }
    }
}
