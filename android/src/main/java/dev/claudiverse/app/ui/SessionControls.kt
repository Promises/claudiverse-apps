package dev.claudiverse.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.claudiverse.core.ModelChoice
import dev.claudiverse.core.SessionChoices
import dev.claudiverse.core.Upload
import dev.claudiverse.core.parseServerTime
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * A seat's settings. Every value shown is what the seat REPORTS running (the
 * model from its replies, the effort stamped on its latest reply, the mode the
 * server knows), not what was last tapped, so a change made at the terminal
 * shows here too.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSheet(
    runningModel: String?,
    effort: String?,
    mode: String?,
    appliedMode: String?,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onMode: (String) -> Unit,
    access: String?,
    accessExplicit: Boolean,
    notifyGrant: Boolean?,
    notifyUntil: String?,
    canNotify: Boolean,
    onTools: (String?) -> Unit,
    onNotify: (allow: Boolean?, minutes: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Session settings", style = MaterialTheme.typography.titleMedium)
            Text("Changes go to the seat as commands, and queue if it is busy.", style = MaterialTheme.typography.bodySmall)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Model", style = MaterialTheme.typography.labelLarge)
                Text("Running: ${runningModel ?: "unknown"} (a seat reports no 1M suffix, so which window it has is not shown)",
                    style = MaterialTheme.typography.bodySmall)
                SessionChoices.models.forEach { ModelRow(it, runningModel, onModel) }
                // Open by itself when the seat runs an older model, so the running mark is never hidden.
                var older by rememberSaveable { mutableStateOf(SessionChoices.olderModels.any { SessionChoices.isRunning(it, runningModel) }) }
                TextButton(onClick = { older = !older }) { Text(if (older) "▾ Older models" else "▸ Older models") }
                if (older) SessionChoices.olderModels.forEach { ModelRow(it, runningModel, onModel) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SessionChoices.aliases.forEach { (alias, label) -> FilterChip(false, { onModel(alias) }, label = { Text(label) }) }
                }
            }
            Section("Effort", "Running: ${effort ?: "unknown until the next reply"}") {
                SessionChoices.efforts.forEach { level -> FilterChip(level == effort, { onEffort(level) }, label = { Text(level) }) }
            }
            // A mod seat does not report its mode: show what this app last set, or say it is unknown.
            val shownMode = mode ?: appliedMode
            Section(
                "Permission mode",
                when {
                    mode != null -> "Running: $mode"
                    appliedMode != null -> "Set to $appliedMode from this app (this seat does not report its mode)"
                    else -> "Not reported by this seat; pick one to set it"
                },
            ) {
                SessionChoices.modes.forEach { (id, label) -> FilterChip(id == shownMode, { onMode(id) }, label = { Text(label) }) }
            }
            Section("Claudiverse tools", toolsLine(access, accessExplicit)) {
                TOOLS.forEach { (id, label) -> FilterChip(accessExplicit && id == access, { onTools(id) }, label = { Text(label) }) }
                FilterChip(!accessExplicit && access != null, { onTools(null) }, label = { Text("Default") })
            }
            PagingSection(notifyGrant, notifyUntil, canNotify, onNotify)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModelRow(m: ModelChoice, runningModel: String?, onModel: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.label + if (SessionChoices.isRunning(m, runningModel)) "  ●" else "", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp))
        FilterChip(false, { onModel(m.id) }, label = { Text("Standard") })
        if (m.oneM) FilterChip(false, { onModel(m.id + "[1m]") }, label = { Text("1M") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, current: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(current, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chips() }
    }
}

/** A file picked for sending, before the user confirms. */
data class PickedFile(val uri: Uri, val name: String, val size: Long?, val mime: String?)

fun pickedFile(context: Context, uri: Uri): PickedFile {
    var name = "file"
    var size: Long? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0) ?: name
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return PickedFile(uri, name, size, context.contentResolver.getType(uri))
}

/** Streams the picked file into the upload: no copy of it is held in memory. */
fun PickedFile.upload(context: Context): Upload = Upload(name, size, mime) {
    (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open $name")).asSource().buffered()
}

/** The server's upload cap; larger files get 413 after the whole upload, so refuse them before sending. */
private const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

/** Over the server's 25 MiB cap: it would be refused (413) only after the whole upload. */
val PickedFile.tooBig: Boolean get() = (size ?: 0) > MAX_UPLOAD_BYTES

/**
 * Files waiting for the next send, above the message box: a thumbnail for a
 * picture (an icon otherwise), name and size, and a remove button. One over
 * the server's limit is marked; the send waits until it is removed.
 */
@Composable
fun AttachmentChips(files: List<PickedFile>, onRemove: (PickedFile) -> Unit) {
    if (files.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        files.forEach { f -> AttachmentChip(f, onRemove = { onRemove(f) }) }
    }
}

@Composable
private fun AttachmentChip(file: PickedFile, onRemove: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(null, file.uri) {
        if (file.mime?.startsWith("image/") == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(file.uri, android.util.Size(96, 96), null) }.getOrNull()
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (file.tooBig) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Row(Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = thumb
            if (t != null) Image(t.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
            else Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(start = 8.dp).widthIn(max = 160.dp)) {
                Text(file.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (file.tooBig) "over the 25 MiB limit" else file.size?.let { readableSize(it) } ?: "size unknown",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (file.tooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove ${file.name}", modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun readableSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}

/** A seat's Claudiverse tools, as the server names them. */
private val TOOLS = listOf("basic" to "Basic", "full" to "Full")

/** How long an On or Off holds before paging goes back to the default; null holds until changed. */
private val PAGING_FOR = listOf<Pair<Int?, String>>(60 to "1 h", 240 to "4 h", 1440 to "1 day", null to "Until changed")

private fun toolsLine(access: String?, explicit: Boolean): String {
    if (access == null) return "Not reported: this server has no access levels yet"
    val what = if (access == "basic") "reports and shared tools" else "orchestrator tools too"
    return "${access.replaceFirstChar { it.uppercase() }}${if (explicit) "" else " (default)"}: $what. Applies within a minute."
}

/**
 * Whether the seat may page the operator: On or Off for a while (or until
 * changed), or Default (its launch flag; plain terminal sessions and runners
 * do not page). The duration is picked first and goes with On or Off.
 */
@Composable
private fun PagingSection(grant: Boolean?, until: String?, canNotify: Boolean, onNotify: (Boolean?, Int?) -> Unit) {
    var minutes by rememberSaveable { mutableStateOf<Int?>(60) }
    val untilText = parseServerTime(until)?.let { " until " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(it) } ?: ""
    val line = when (grant) {
        true -> "On$untilText: it may page you."
        false -> "Off$untilText: it may not page you."
        null -> "Default: it ${if (canNotify) "may" else "may not"} page you."
    }
    Section("May page the operator", line) {
        FilterChip(grant == true, { onNotify(true, minutes) }, label = { Text("On") })
        FilterChip(grant == false, { onNotify(false, minutes) }, label = { Text("Off") })
        FilterChip(grant == null, { onNotify(null, null) }, label = { Text("Default") })
    }
    Section("For", "How long On or Off holds before it goes back to the default") {
        PAGING_FOR.forEach { (m, label) -> FilterChip(minutes == m, { minutes = m }, label = { Text(label) }) }
    }
}
