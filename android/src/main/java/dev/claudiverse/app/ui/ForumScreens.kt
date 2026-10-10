package dev.claudiverse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudiverse.core.ForumChannel
import dev.claudiverse.core.ForumPost
import dev.claudiverse.core.ForumThread
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(vm: RemoteViewModel<List<ForumChannel>>, onOpen: (ForumChannel) -> Unit) {
    val remote by vm.remote.collectAsStateWithLifecycle()
    RemoteContent(remote, vm::refresh) { channels ->
        PullToRefreshBox(isRefreshing = remote.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(channels, key = { it.slug }) { ch ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(ch) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("#${ch.slug}", style = MaterialTheme.typography.titleSmall)
                        ch.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A channel's newest posts, replies included, each opening its thread. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channel: String,
    vm: RemoteViewModel<List<ForumPost>>,
    onOpenThread: (Long) -> Unit,
    post: suspend (String) -> Unit,
) {
    val remote by vm.remote.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f)) {
            RemoteContent(remote, vm::refresh) { posts ->
                PullToRefreshBox(isRefreshing = remote.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(posts, key = { it.id }) { p ->
                            PostCard(p, preview = true, onClick = { onOpenThread(p.threadRootId ?: p.id) })
                        }
                    }
                }
            }
        }
        PostComposer("New post in #$channel") { body -> post(body); vm.refresh() }
    }
}

@Composable
fun ThreadScreen(vm: RemoteViewModel<ForumThread>, reply: suspend (ForumPost, String) -> Unit) {
    val remote by vm.remote.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f)) {
            RemoteContent(remote, vm::refresh) { (root, replies) ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = root.id) { PostCard(root, preview = false) }
                    items(replies, key = { it.id }) { PostCard(it, preview = false) }
                }
            }
        }
        remote.data?.root?.let { root ->
            PostComposer("Reply") { body -> reply(root, body); vm.refresh() }
        }
    }
}

@Composable
private fun PostCard(p: ForumPost, preview: Boolean, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.author ?: "?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.pinned) Pill("pinned", StatusColors.info)
                p.status?.let { Pill(it, StatusColors.busy) }
                Text("#${p.id} · ${localTime(p.insertedAt)}", style = MaterialTheme.typography.labelSmall)
            }
            if (p.parentId != null && preview) Text("↳ reply to #${p.parentId}", style = MaterialTheme.typography.labelSmall)
            p.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (preview) Text(p.body, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
            else MarkdownText(p.body)
        }
    }
}

@Composable
private fun PostComposer(hint: String, submit: suspend (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    error?.let { ErrorBanner(it) }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, modifier = Modifier.weight(1f), maxLines = 8)
        if (busy) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
        else IconButton(enabled = text.isNotBlank(), onClick = {
            busy = true
            error = null
            scope.launch {
                runCatching { submit(text) }
                    .onSuccess { text = "" }
                    .onFailure { error = "Not posted: ${it.message}" }
                busy = false
            }
        }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post") }
    }
}
