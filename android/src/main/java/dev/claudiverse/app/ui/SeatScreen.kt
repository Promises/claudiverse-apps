package dev.claudiverse.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.core.OutboxRow
import dev.claudiverse.core.answerMap
import dev.claudiverse.core.clipboardText
import dev.claudiverse.core.composeAnswer
import dev.claudiverse.app.seat.SeatUiState
import dev.claudiverse.app.seat.SeatViewModel
import dev.claudiverse.core.Timeline
import dev.claudiverse.core.BackgroundTask
import dev.claudiverse.core.RestartPlan
import dev.claudiverse.core.SeatAgent
import dev.claudiverse.core.TimelineRow
import dev.claudiverse.core.TranscriptImage
import dev.claudiverse.core.TranscriptItem

@Composable
fun SeatScreen(vm: SeatViewModel, showInfo: Boolean, onShowInfo: (Boolean) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }
    // The agent whose thread shows in place of main's; null for main.
    var agentId by rememberSaveable(vm) { mutableStateOf<String?>(null) }
    val agent = agentId?.let { id -> ui.agents.firstOrNull { it.id == id } }
    val thread = ui.thread(agent?.id)
    val count = thread.rows.size + if (thread.streaming.isNotEmpty()) 1 else 0
    // Bubbles the user has tapped open, Messenger style: their exact time and a Copy.
    var revealed by remember(vm) { mutableStateOf(setOf<String>()) }

    // Follow the tail: the screen opens at the latest message and stays there
    // as messages arrive, streamed text grows or the keyboard shrinks the
    // view. Following stops when the user drags the list, and resumes when
    // the list is at the bottom again (scrolled back down, or Latest).
    // Deciding from the layout at the moment a batch arrives
    // instead raced the replay: if the first rows were already laid out, the
    // screen thought the user was reading history and stayed at the top.
    var following by remember(vm) { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    // Every picture in the transcript, in order: the viewer steps through them all.
    // Pasted pictures and those a tool returned, in transcript order; a tool's is captioned with the call.
    val shownImages = remember(thread.items) {
        thread.items.flatMap { item ->
            when (item) {
                is TranscriptItem.UserText -> item.images.map { it to null }
                is TranscriptItem.ToolUse -> item.images.map { it to listOf(item.name, item.summary).filter(String::isNotBlank).joinToString(" · ") }
                else -> emptyList()
            }
        }
    }
    val allImages = remember(shownImages) { shownImages.map { it.first } }
    var viewerAt by remember { mutableStateOf<Int?>(null) }
    viewerAt?.let { at -> ImageViewer(allImages, at, vm.images, captions = shownImages.map { it.second }, onClose = { viewerAt = null }) }
    // Files waiting for the next send, as the Mac's composer holds them.
    var attachments by remember { mutableStateOf(listOf<PickedFile>()) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val known = attachments.map { it.uri }.toSet()
        attachments = attachments + uris.filter { it !in known }.map { pickedFile(context, it) }
    }
    val scope = rememberCoroutineScope()
    // Only a finger stops following: the app's own scrolling passes through
    // states that can still scroll forward, and reading those as "the user
    // scrolled up" switched following off mid-jump.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canForward) ->
            if (!scrolling && !canForward) following = true
        }
    }
    LaunchedEffect(listState) {
        // Read the count from state inside the flow: a `count` captured from
        // the first composition stays 0 forever and the flow never scrolls.
        // A revealed message's details grow the list without adding a row: following, keep them in sight too.
        snapshotFlow {
            val t = ui.thread(agentId)
            listOf(t.rows.size + if (t.streaming.isNotEmpty()) 1 else 0, t.streaming.length, listState.layoutInfo.viewportSize.height, revealed.size)
        }.collect { (n) ->
            if (following && n > 0) listState.scrollToEnd(n)
        }
    }
    // Another thread opens at its latest message, as the seat does.
    LaunchedEffect(agentId) {
        following = true
        if (count > 0) listState.scrollToEnd(count)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        SeatHeader(ui.seat?.status ?: ui.live?.status, ui.seat?.activity, ui.live?.sidecarConnected,
            ui.socketConnected, ui.joined, ui.seat?.model, onInterrupt = vm::interrupt, onSettings = { showSettings = true },
            onInfo = { onShowInfo(true) },
            lifecycle = ui.seat?.let { SeatLifecycle(it.label, it.host, it.canStop, it.canResume, ui.acting) },
            onStop = vm::stop, onResume = vm::resume,
            restartPlan = ui.restartPlan, restartPlanning = ui.restartPlanning, onPlanRestart = vm::planRestart, onRestart = vm::restart)
        if (showInfo) SessionInfoSheet(
            seat = ui.seat, live = ui.live, seatId = vm.seatId, socketConnected = ui.socketConnected,
            joined = ui.joined, effort = ui.effort, loadError = ui.loadError, onDismiss = { onShowInfo(false) },
        )
        if (showSettings) SessionSheet(
            catalog = models,
            runningModel = ui.seat?.model,
            clientVersion = ui.seat?.clientVersion,
            effort = ui.effort,
            mode = ui.live?.permissionMode,
            appliedMode = ui.appliedMode,
            onModel = vm::setModel,
            onEffort = vm::setEffort,
            onMode = vm::setPermissionMode,
            access = ui.seat?.access,
            accessExplicit = ui.seat?.accessExplicit == true,
            notifyGrant = ui.seat?.notifyGrant,
            notifyUntil = ui.seat?.notifyUntil,
            canNotify = ui.seat?.canNotify == true,
            onTools = vm::setTools,
            onNotify = vm::setNotify,
            onDismiss = { showSettings = false },
        )
        ui.joinError?.let { ErrorBanner("Live view: $it") }
        ui.loadError?.let { ErrorBanner(it) }
        if (ui.agents.isNotEmpty()) AgentSwitcher(ui.agents, agent?.id, onSelect = { agentId = it })
        // Subagents are listed as agents above; the rest of what runs in the background is here.
        BackgroundTasksBar(BackgroundTask.shown(ui.backgroundTasks))

        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            items(thread.rows, key = { it.key }) { row ->
                when (row) {
                    is TimelineRow.Divider -> TimeDivider(Timeline.label(row.at))
                    TimelineRow.Resumed -> ResumedDivider()
                    is TimelineRow.Entry -> TranscriptRow(
                        row.item, vm,
                        onImage = { image -> viewerAt = allImages.indexOf(image).coerceAtLeast(0) },
                        time = row.at?.let { Timeline.label(it) },
                        revealed = row.key in revealed,
                        onToggle = { revealed = if (row.key in revealed) revealed - row.key else revealed + row.key },
                        // A thread has no link to itself (a fork's starts with the call that spawned it).
                        agentFor = { toolUseId -> ui.agents.firstOrNull { it.toolUseId == toolUseId && it.id != agent?.id } },
                        onOpenAgent = { agentId = it },
                    )
                }
            }
            if (thread.streaming.isNotEmpty()) item(key = "streaming") {
                Text(thread.streaming, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }
            if (thread.items.isEmpty() && ui.joined) item(key = "empty") {
                Text(if (agent != null) "Nothing from this agent yet." else "Nothing in the replay buffer yet.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!following && count > 0) FilledTonalButton(
            onClick = {
                following = true
                scope.launch { listState.scrollToEnd(count) }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) { Text("↓ Latest") }
        }

        ui.notice?.let { notice ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Selectable: a seat a restart left stopped comes with the command that brings it back.
                    SelectionContainer(Modifier.weight(1f)) { Text(notice, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = vm::dismissNotice) { Text("OK") }
                }
            }
        }
        agent?.let {
            Text("${it.label}: its own thread. A message here goes to main; sending to an agent comes later.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
        OutboxPanel(ui.outbox, onSend = { vm.forceSend(it) }, onSendNow = { vm.forceSend(it, interrupt = true) }, onRemove = vm::unqueue)
        AttachmentChips(attachments, onRemove = { f -> attachments = attachments - f })
        Composer(
            sending = ui.sending || ui.uploading,
            busy = ui.live?.idle == false,
            attached = attachments.size,
            blocked = attachments.any { it.tooBig },
            onSend = { text ->
                // With attachments the text goes along as their note: one upload, one message.
                if (attachments.isEmpty()) vm.send(text)
                else {
                    vm.sendFiles(attachments.map { it.upload(context) }, text)
                    attachments = emptyList()
                }
            },
            onSendNow = vm::sendNow,
            onAttach = { filePicker.launch(arrayOf("*/*")) },
        )
    }
}

/**
 * To the very end of the list. scrollToItem on the last item stops at that
 * item's TOP, so a last message taller than the screen showed its start with
 * the rest cut off; scrolling on until nothing is left reaches its bottom.
 */
private suspend fun LazyListState.scrollToEnd(count: Int) {
    scrollToItem(count - 1)
    // canScrollForward is from the last layout pass, which ran before the
    // jump (and, on open, before the replay's items existed): wait a frame
    // for the list to measure where it now is before asking again.
    repeat(20) {
        withFrameNanos { }
        if (!canScrollForward) return
        scrollBy(100_000f)
    }
}

/** What the header needs to offer Stop or Resume; null until the seat has loaded. */
private class SeatLifecycle(val label: String, val host: String?, val canStop: Boolean, val canResume: Boolean, val acting: Boolean)

@Composable
private fun SeatHeader(
    status: String?, activity: String?, sidecar: Boolean?, socket: Boolean, joined: Boolean, model: String?,
    onInterrupt: () -> Unit,
    onSettings: () -> Unit,
    onInfo: () -> Unit,
    lifecycle: SeatLifecycle?,
    onStop: () -> Unit,
    onResume: () -> Unit,
    restartPlan: RestartPlan?,
    restartPlanning: Boolean,
    onPlanRestart: () -> Unit,
    onRestart: (confirmProtected: Boolean) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    if (confirmRestart && restartPlan != null && restartPlan.choosable) RestartSeatDialog(
        restartPlan,
        onConfirm = { protectedOk -> confirmRestart = false; onRestart(protectedOk) },
        onDismiss = { confirmRestart = false },
    )
    if (confirmStop && lifecycle != null) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("Stop ${lifecycle.label}?") },
        text = { Text("Closes its terminal session on ${lifecycle.host}, ending any turn in progress. Its conversation is kept: resume it from the seat list with Running turned off.") },
        confirmButton = {
            TextButton(onClick = { confirmStop = false; onStop() }, colors = ButtonDefaults.textButtonColors(contentColor = StatusColors.bad)) { Text("Stop") }
        },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Interrupt this seat?") },
        text = { Text("Stops the turn in progress, as Esc does at the terminal.") },
        confirmButton = { TextButton(onClick = { confirm = false; onInterrupt() }) { Text("Interrupt") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
    // On a narrow screen the model name gives way (ellipsised): the pills, the
    // icons, the action button and the menu always stay in view.
    // The menu button carries its own touch padding; without it the action button needs the full margin.
    val end = if (lifecycle?.canStop == true) 4.dp else 12.dp
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = end, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActivityPill(status, activity)
            when {
                !socket -> Pill("offline", StatusColors.bad)
                !joined -> Pill("joining", StatusColors.busy)
                else -> Pill("live", StatusColors.info)
            }
            if (sidecar == false) Pill("no sidecar", StatusColors.bad)
            model?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            IconButton(onClick = onInfo) { Icon(Icons.Filled.Info, contentDescription = "Session info") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Session settings") }
        }
        when {
            lifecycle?.canResume == true -> Button(onClick = onResume, enabled = !lifecycle.acting) { Text("Resume") }
            // A stopped seat has no turn to interrupt.
            status == null || status == "running" ->
                OutlinedButton(onClick = { confirm = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusColors.bad)) {
                    Text("Interrupt")
                }
        }
        if (lifecycle?.canStop == true) Box {
            // Opening the menu asks the server what a restart would do, so the entry can say it.
            IconButton(onClick = { menu = true; onPlanRestart() }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val plan = restartPlan
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                when {
                                    plan == null || restartPlanning -> "Restart seat…"
                                    plan.action == RestartPlan.ADOPT -> "Adopt into a tmux seat…"
                                    plan.action == RestartPlan.RESTART -> "Restart seat" + (plan.targetVersion?.let { " on $it" } ?: "") + "…"
                                    plan.choosable -> "Restart protected seat…"
                                    else -> "Restart seat"
                                },
                            )
                            // Why not now, in the server's words: busy, a question open, already current…
                            if (plan != null && plan.skipped && !plan.choosable && !restartPlanning) plan.reason?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                    enabled = !lifecycle.acting && plan != null && !restartPlanning && plan.choosable,
                    onClick = { menu = false; confirmRestart = true },
                )
                DropdownMenuItem(
                    text = { Text("Stop seat…") },
                    leadingIcon = { Icon(Icons.Filled.StopCircle, contentDescription = null) },
                    enabled = !lifecycle.acting,
                    onClick = { menu = false; confirmStop = true },
                )
            }
        }
    }
}

/** Restart or adopt this seat? What it does, from the server's plan: the versions, and what the restart carries over. */
@Composable
private fun RestartSeatDialog(plan: RestartPlan, onConfirm: (confirmProtected: Boolean) -> Unit, onDismiss: () -> Unit) {
    val adopt = plan.action == RestartPlan.ADOPT
    // A protected seat takes a second, deliberate yes.
    var protectedOk by remember(plan.sessionId) { mutableStateOf(false) }
    val protectedSeat = plan.needsProtectedConfirm
    val versions = listOfNotNull(plan.clientVersion?.let { "from $it" }, plan.targetVersion?.let { "to $it" }).joinToString(" ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (adopt) "Adopt ${plan.title}?" else "Restart ${plan.title}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (adopt) "It runs outside tmux. It is stopped and started again as a tmux seat on ${plan.host ?: "its machine"}, " +
                        "on the machine's current Claude Code, resuming its conversation. Its old terminal window is left showing the exited claude."
                    else "Its Claude Code is ended and started again in its tmux window on ${plan.host ?: "its machine"}, resuming its conversation.",
                )
                if (versions.isNotEmpty()) Text("Claude Code $versions", style = MaterialTheme.typography.bodySmall)
                val carried = plan.argvNames + plan.envNames
                if (carried.isNotEmpty()) Text("Carries over: ${carried.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
                if (protectedSeat) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(protectedOk, { protectedOk = it })
                    Text("${plan.title} is protected. Restart it anyway.", style = MaterialTheme.typography.bodyMedium, color = StatusColors.bad)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(protectedSeat && protectedOk) }, enabled = !protectedSeat || protectedOk) {
                Text(if (adopt) "Adopt" else "Restart")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The seat's Outbox: Claude's own queue on the seat (it runs these in this
 * order, and they cannot be taken back), messages just sent that the seat has
 * not listed yet, and those held in this app until the seat can take them,
 * which can be sent (into Claude's queue), sent now (interrupting) or cancelled.
 */
@Composable
private fun OutboxPanel(rows: List<OutboxRow>, onSend: (Long) -> Unit, onSendNow: (Long) -> Unit, onRemove: (Long) -> Unit) {
    if (rows.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Outbox", style = MaterialTheme.typography.labelSmall)
            rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (r.interrupting) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                            Text(r.label, style = MaterialTheme.typography.labelSmall,
                                color = if (r.kind == OutboxRow.Kind.HELD) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    r.heldId?.let { id ->
                        TextButton(onClick = { onSend(id) }) { Text("Send") }
                        IconButton(onClick = { onSendNow(id) }) {
                            Icon(Icons.Filled.FastForward, contentDescription = "Send now: interrupt and run it at once")
                        }
                        IconButton(onClick = { onRemove(id) }) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    sending: Boolean, busy: Boolean, attached: Int, blocked: Boolean, onSend: (String) -> Unit, onSendNow: (String) -> Unit,
    onAttach: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onAttach, enabled = !sending) { Icon(Icons.Filled.Add, contentDescription = "Attach files") }
        OutlinedTextField(
            text, { text = it },
            placeholder = {
                Text(when {
                    attached > 0 -> "A note to go with ${if (attached == 1) "the file" else "the $attached files"} (optional)"
                    busy -> "Seat working — Send queues it, Send now interrupts"
                    else -> "Message the seat"
                })
            },
            modifier = Modifier.weight(1f), maxLines = 6,
        )
        if (sending) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
        else {
            // Send now interrupts the running turn: only while the seat works, and not for files (they go as a note).
            if (busy && attached == 0) IconButton(onClick = { onSendNow(text); text = "" }, enabled = text.isNotBlank()) {
                Icon(Icons.Filled.FastForward, contentDescription = "Send now: interrupt and run it at once")
            }
            IconButton(onClick = { onSend(text); text = "" }, enabled = !blocked && (text.isNotBlank() || attached > 0)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun TranscriptRow(
    item: TranscriptItem, vm: SeatViewModel, onImage: (TranscriptImage) -> Unit,
    time: String?, revealed: Boolean, onToggle: () -> Unit,
    agentFor: (toolUseId: String) -> SeatAgent?, onOpenAgent: (agentId: String) -> Unit,
) {
    // The whole message as written (a reply's Markdown, a tool call with its input and result).
    val copier = rememberCopier()
    val onCopy = clipboardText(item)?.let { text -> { copier(text) } }
    when (item) {
        is TranscriptItem.UserText -> ChatBubble(fromClaude = false, label = if (item.injected) "injected" else null,
            time = time, revealed = revealed, onClick = onToggle, onCopy = onCopy) {
            // A message can be pictures alone: the builder drops "[Image #N]" when the images are there.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.text.isNotBlank()) MarkdownText(item.text)
                if (item.images.isNotEmpty()) ImageThumbs(item.images, vm.images, onImage)
            }
        }
        is TranscriptItem.AssistantText -> ChatBubble(fromClaude = true, time = time, revealed = revealed, onClick = onToggle, onCopy = onCopy) {
            MarkdownText(item.text)
        }
        is TranscriptItem.Thinking -> ThinkingBubble(item.text, time, revealed, onToggle, onCopy)
        is TranscriptItem.ToolUse -> ToolRow(item, agentFor(item.toolUseId), onOpenAgent, vm, onImage, time, onCopy)
        is TranscriptItem.Question -> QuestionCard(item, onAnswer = { text, answers -> vm.answer(item.toolUseId, text, answers) },
            onDecline = { vm.decline(item.toolUseId) })
        is TranscriptItem.Permission -> PermissionCard(item, onDecide = { vm.permission(item.requestId, it) })
        is TranscriptItem.Event -> Collapsible(item.label, item.summary, time, onCopy) { Mono(item.text) }
        is TranscriptItem.TurnEnd -> Text("— ${item.text} —", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth())
        is TranscriptItem.Note -> Text(item.text, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline)
    }
}

/** The centred time line between messages, as Messenger draws it. */
@Composable
private fun TimeDivider(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
    )
}

/**
 * Under a tapped message, on its side: its time, when it has one, and a Copy
 * for the whole message. Below rather than above, so a long message's
 * details stay in sight (above, they scrolled out of view and the latest
 * message seemed not to respond).
 */
@Composable
private fun MessageDetails(time: String?, onCopy: (() -> Unit)?, start: Boolean = true) {
    if (time == null && onCopy == null) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, if (start) Alignment.Start else Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        onCopy?.let { TextButton(onClick = it) { Text("Copy") } }
    }
}

/** Where a resumed seat took over: above it, the conversation from its earlier rows. */
@Composable
private fun ResumedDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "RESUMED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A chat bubble: Claude on the left, the user (or text injected into the seat)
 * on the right. At most 88% of the row, so the side it sits on reads at a glance;
 * the corner nearest the speaker is squared off. Tapping it shows its time and a Copy under it.
 */
@Composable
private fun ChatBubble(
    fromClaude: Boolean,
    label: String? = null,
    time: String? = null,
    revealed: Boolean = false,
    onClick: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val shape = if (fromClaude) RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
        else RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
        Surface(
            color = if (fromClaude) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
            shape = shape,
            modifier = Modifier
                .align(if (fromClaude) Alignment.CenterStart else Alignment.CenterEnd)
                .widthIn(max = maxWidth * 0.88f)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                label?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                content()
            }
        }
    }
    // Tapping the bubble shows its exact time under it, and a Copy for the whole message.
    if (revealed) MessageDetails(time, onCopy, start = fromClaude)
}

/**
 * Claude's thinking, as the Mac shows it: a Claude-side bubble a step quieter
 * than its replies (a paler fill, a dashed edge), "Thinking" over the text in
 * secondary italic. Open; long thinking shows its start with Show more.
 */
@Composable
private fun ThinkingBubble(raw: String, time: String?, revealed: Boolean, onClick: (() -> Unit)?, onCopy: (() -> Unit)?) {
    val (text, preview) = thinkingText(raw)
    if (text.isEmpty()) return
    var expanded by rememberSaveable(raw) { mutableStateOf(false) }
    val long = preview != null
    val shown = if (!long || expanded) text else preview!!
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val edge = secondary.copy(alpha = 0.35f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = maxWidth * 0.88f)
                .clip(shape)
                .background(if (dark) Color(0xFF232129) else Color(0xFFF5F2F7))
                .drawBehind {
                    drawOutline(
                        shape.createOutline(size, layoutDirection, this), edge,
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.Psychology, contentDescription = null, tint = secondary, modifier = Modifier.size(14.dp))
                Text("Thinking", style = MaterialTheme.typography.labelSmall, color = secondary)
            }
            MarkdownText(shown, style = MaterialTheme.typography.bodyMedium.copy(color = secondary, fontStyle = FontStyle.Italic))
            if (long) Text(
                if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
            )
        }
    }
    if (revealed) MessageDetails(time, onCopy)
}

private const val THINKING_LINES = 6
private const val THINKING_CHARS = 600

/**
 * Thinking as the bubble shows it: trimmed (it often ends in blank lines),
 * and, when long (over [THINKING_LINES] lines or [THINKING_CHARS] characters),
 * a preview of its start ending in "…"; null when it is short enough to show whole.
 */
internal fun thinkingText(raw: String): Pair<String, String?> {
    val text = raw.trim()
    val lines = text.lines()
    if (lines.size <= THINKING_LINES && text.length <= THINKING_CHARS) return text to null
    val head = lines.take(THINKING_LINES).joinToString("\n")
    return text to (if (head.length > THINKING_CHARS) head.take(THINKING_CHARS).trimEnd() else head) + "…"
}

@Composable
private fun Collapsible(
    label: String, summary: String, time: String? = null, onCopy: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp)) {
            Text((if (open) "▾ " else "▸ ") + label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text("  $summary", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) {
            content()
            MessageDetails(time, onCopy)
        }
    }
}

/**
 * A tool call. Pictures it returned (a Read of a PNG, say) show under it,
 * outside the collapsed details; under an Agent call that spawned a subagent,
 * a link to that subagent's own thread.
 */
@Composable
private fun ToolRow(
    item: TranscriptItem.ToolUse, spawned: SeatAgent?, onOpenAgent: (String) -> Unit,
    vm: SeatViewModel, onImage: (TranscriptImage) -> Unit, time: String?, onCopy: (() -> Unit)?,
) {
    val label = item.name + when {
        item.result == null -> " …"
        item.resultIsError -> " ✗"
        else -> ""
    }
    Column {
        Collapsible(label, item.summary, time, onCopy) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Mono(item.input)
                // An image-only result has no text: "" reads as none.
                item.result?.takeIf { it.isNotEmpty() }?.let {
                    Text(if (item.resultIsError) "error" else "result", style = MaterialTheme.typography.labelSmall)
                    Mono(it, limit = 6_000)
                }
            }
        }
        if (item.images.isNotEmpty()) {
            Box(Modifier.padding(start = 12.dp, top = 4.dp)) { ImageThumbs(item.images, vm.images, onImage) }
        }
        spawned?.let { TextButton(onClick = { onOpenAgent(it.id) }) { Text("Open ${it.type}'s thread") } }
    }
}

/**
 * Main and the seat's agents, as Claude Code's own agent switcher lists them:
 * the selected one's thread shows. Each agent with its type ("fork" for a
 * fork), what it was asked to do, and a dot for running, waiting, idle or ended.
 */
@Composable
private fun AgentSwitcher(agents: List<SeatAgent>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AgentChip("main", null, StatusColors.good, selected == null) { onSelect(null) }
        agents.forEach { a ->
            AgentChip(a.label, if (a.fork) "fork" else a.type, agentColor(a), selected == a.id) { onSelect(a.id) }
        }
    }
}

@Composable
private fun AgentChip(label: String, badge: String?, dot: Color, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = { Box(Modifier.size(8.dp).background(dot, CircleShape)) },
        label = {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                badge?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            }
        },
    )
}

private fun agentColor(a: SeatAgent): Color = when {
    a.isBusy -> StatusColors.busy
    a.status == "failed" || a.status == "killed" -> StatusColors.bad
    a.status == "completed" -> StatusColors.muted
    else -> StatusColors.good
}

/** What the transcript shows: main's, or an agent's own thread, which has no streamed text. */
private class ShownThread(val items: List<TranscriptItem>, val rows: List<TimelineRow>, val streaming: String)

private fun SeatUiState.thread(agentId: String?): ShownThread =
    if (agentId == null || agents.none { it.id == agentId }) ShownThread(items, rows, streaming)
    else agentThreads[agentId].let { ShownThread(it?.items.orEmpty(), it?.rows.orEmpty(), "") }

@Composable
private fun Mono(text: String, limit: Int = 4_000) {
    var all by remember { mutableStateOf(false) }
    val shown = if (all || text.length <= limit) text else text.take(limit)
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.padding(8.dp)) {
            SelectionContainer {
                Text(shown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            if (shown.length < text.length) TextButton(onClick = { all = true }) {
                Text("Show all ${text.length} characters")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(item: TranscriptItem.Question, onAnswer: (String, Map<String, String>) -> Unit, onDecline: () -> Unit) {
    val picked = remember(item.toolUseId) { mutableStateMapOf<Int, List<String>>() }
    var other by rememberSaveable(item.toolUseId) { mutableStateOf("") }
    var sent by rememberSaveable(item.toolUseId) { mutableStateOf(false) }
    // Only an unanswered question gets live controls: a transcript is mostly history.
    val live = item.answer == null && !sent

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Question", style = MaterialTheme.typography.labelMedium)
            item.questions.forEachIndexed { qi, q ->
                q.header?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text(q.question, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    q.options.forEach { (label, description) ->
                        val chosen = picked[qi].orEmpty().contains(label)
                        FilterChip(
                            selected = chosen,
                            enabled = live,
                            onClick = {
                                val current = picked[qi].orEmpty()
                                picked[qi] = when {
                                    !q.multiSelect -> listOf(label)
                                    chosen -> current - label
                                    else -> current + label
                                }
                            },
                            label = { Text(label) },
                        )
                        if (chosen && description != null) Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (live) {
                OutlinedTextField(other, { other = it }, placeholder = { Text("Or answer in your own words") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val choices = item.questions.indices.map { picked[it].orEmpty() }
                    val composed = other.ifBlank { composeAnswer(item.questions, choices) }
                    // Picked choices also go structured, one per question; free text goes as text only.
                    val answers = if (other.isBlank()) answerMap(item.questions, choices) else emptyMap()
                    Button(onClick = { onAnswer(composed, answers); sent = true }, enabled = composed.isNotBlank()) { Text("Answer") }
                    OutlinedButton(onClick = { onDecline(); sent = true }) { Text("Decline") }
                }
            } else {
                Text(item.answer?.let { "Answered: $it" } ?: "Sent", style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PermissionCard(item: TranscriptItem.Permission, onDecide: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(
        containerColor = if (item.pending) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    )) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Permission: ${item.toolName}", style = MaterialTheme.typography.titleSmall)
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (item.input.isNotBlank()) Collapsible("input", item.input.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()) {
                Mono(item.input)
            }
            if (item.pending) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecide(true) }) { Text("Allow") }
                OutlinedButton(onClick = { onDecide(false) }) { Text("Deny") }
            } else Text("Resolved", style = MaterialTheme.typography.labelSmall)
        }
    }
}
