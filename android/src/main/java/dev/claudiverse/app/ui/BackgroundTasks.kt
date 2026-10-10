package dev.claudiverse.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudiverse.core.BackgroundTask
import dev.claudiverse.core.elapsedLabel
import kotlinx.coroutines.delay

/**
 * What a seat runs in the background besides its agents: shells, monitors and
 * workflows, as Claude Code's own task list has them. Folded, one line ("2
 * shells running"); unfolded, a row each with its label, how long it has run
 * (ticking while open) and its command, which can be copied. Read-only: the
 * seat cannot be asked to stop one from here.
 */
@Composable
fun BackgroundTasksBar(tasks: List<BackgroundTask>) {
    if (tasks.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    // The clock only runs while the times are on screen.
    val now by produceState(System.currentTimeMillis(), open) {
        while (open) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val copier = rememberCopier()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary)
                Text(summary(tasks), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide" else "Show", modifier = Modifier.size(18.dp))
            }
            if (open) tasks.sortedByDescending { it.isRunning }.forEach { task ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                TaskRow(task, now, onCopy = task.command?.takeIf { it.isNotBlank() }?.let { c -> { copier(c) } })
            }
        }
    }
}

@Composable
private fun TaskRow(task: BackgroundTask, now: Long, onCopy: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon(task.type), contentDescription = task.type, modifier = Modifier.size(18.dp),
            tint = if (task.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(task.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                val time = task.startedAt?.let { elapsedLabel(it, now) }
                val state = task.status.takeUnless { task.isRunning }
                listOfNotNull(state, time).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // The command, unless it is already the label.
            task.command?.takeIf { it.isNotBlank() && it != task.label }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        onCopy?.let {
            IconButton(onClick = it) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy command", modifier = Modifier.size(18.dp)) }
        }
    }
}

/** "2 shells running", "1 monitor running, 1 ended", "3 tasks running". */
private fun summary(tasks: List<BackgroundTask>): String {
    val types = tasks.map { it.type }.distinct()
    fun noun(n: Int) = when (types.singleOrNull()) {
        "shell" -> if (n == 1) "shell" else "shells"
        "monitor" -> if (n == 1) "monitor" else "monitors"
        "workflow" -> if (n == 1) "workflow" else "workflows"
        else -> if (n == 1) "task" else "tasks"
    }
    val running = tasks.count { it.isRunning }
    val ended = tasks.size - running
    return when {
        running == 0 -> "${tasks.size} ${noun(tasks.size)} ended"
        ended == 0 -> "$running ${noun(running)} running"
        else -> "$running ${noun(tasks.size)} running, $ended ended"
    }
}

private fun icon(type: String): ImageVector = when (type) {
    "shell" -> Icons.Filled.Terminal
    "monitor" -> Icons.Filled.MonitorHeart
    "workflow" -> Icons.Filled.AccountTree
    else -> Icons.Filled.Settings
}
