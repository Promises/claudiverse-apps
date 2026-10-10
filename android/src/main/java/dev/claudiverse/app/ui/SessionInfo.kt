package dev.claudiverse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.claudiverse.core.Seat
import dev.claudiverse.core.SeatLive
import dev.claudiverse.core.relativeTime

/**
 * Everything the server knows about a seat, opened from the seat's title or
 * its info button. A value the seat has not reported reads "not reported"
 * rather than being left out, so a missing field is visible as missing.
 * Tapping a value copies it (paths and ids are what one wants to paste).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    seat: Seat?,
    live: SeatLive?,
    seatId: Long,
    socketConnected: Boolean,
    joined: Boolean,
    effort: String?,
    loadError: String?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }
    val rows: List<Pair<String, String?>> = listOf(
        // A profile's seat is called by its title; its display title can be a stale auto-title.
        "Title" to seat?.label,
        "Seat" to seat?.title,
        "Working directory" to seat?.workingDirectory,
        "Machine" to seat?.host,
        "Fleet" to seat?.fleet,
        "Started by" to seat?.origin,
        "Can notify you" to seat?.let { if (it.canNotify) "yes" else "no" },
        "Status" to listOfNotNull(seat?.status ?: live?.status, seat?.activity).joinToString(" · ").ifEmpty { null },
        "Model" to seat?.model,
        "Effort" to effort,
        "Permission mode" to live?.permissionMode,
        "Started" to seat?.insertedAt?.let { "${localTime(it)} (${relativeTime(it)})" },
        "Last activity" to seat?.lastActivityAt?.let { "${localTime(it)} (${relativeTime(it)})" },
        "Live view" to when {
            !socketConnected -> "offline"
            joined -> "live"
            else -> "joining"
        },
        "Sidecar" to live?.sidecarConnected?.let { if (it) "connected" else "not connected" },
        "Type" to listOfNotNull(seat?.type, seat?.mode).joinToString(" · ").ifEmpty { null },
        "Claude Code" to seat?.clientVersion,
        "Claude session" to seat?.claudeSessionId,
        "Server session" to seatId.toString(),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Session info", style = MaterialTheme.typography.titleMedium)
            Text(copied?.let { "Copied $it" } ?: "Tap a value to copy it.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            // Not loaded is not "not reported": say which it is, and why.
            if (seat == null) Text(
                loadError?.let { "Couldn't load the seat's details: $it" } ?: "Loading the seat's details…",
                style = MaterialTheme.typography.bodySmall,
                color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            rows.forEach { (label, value) ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = value != null) {
                            clipboard.setText(AnnotatedString(value!!))
                            copied = label.lowercase()
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        value ?: when {
                            seat == null -> "—"
                            // Effort is read from the seat's replies, so a fresh seat has none yet.
                            label == "Effort" -> "after its next reply"
                            else -> "not reported"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (label in MONO) FontFamily.Monospace else null,
                        color = if (value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Values that are paths or ids read better in monospace. */
private val MONO = setOf("Working directory", "Claude session", "Server session", "Seat")
