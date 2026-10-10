package dev.claudiverse.app.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import dev.claudiverse.core.Markdown
import dev.claudiverse.core.MdBlock
import dev.claudiverse.core.tableTabSeparated
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import dev.claudiverse.core.parseServerTime
import dev.claudiverse.core.linkEnd

/** A fetched value, the last error, and whether a fetch is running. Data survives a failed refresh. */
data class Remote<T>(val data: T? = null, val error: String? = null, val loading: Boolean = false)

/**
 * Loads one thing from the server, and re-polls every [pollMs] while something
 * is collecting it: a screen in the back stack, or the app in the background,
 * costs no requests. A failed refresh keeps the last good data and shows the
 * error beside it.
 */
class RemoteViewModel<T>(private val pollMs: Long?, private val fetch: suspend () -> T) : ViewModel() {
    private val state = MutableStateFlow(Remote<T>())
    val remote: StateFlow<Remote<T>> = state.asStateFlow()

    init {
        viewModelScope.launch {
            state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                if (!watched) return@collectLatest
                if (state.value.data == null || pollMs != null) load()
                while (pollMs != null && isActive) {
                    delay(pollMs)
                    load()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        state.update { it.copy(loading = true) }
        runCatching { fetch() }
            .onSuccess { d -> state.value = Remote(d, null, false) }
            .onFailure { e -> state.update { it.copy(error = e.message ?: e.javaClass.simpleName, loading = false) } }
    }
}

/** The usual frame around a [Remote]: spinner on first load, error with retry, else content. */
@Composable
fun <T> RemoteContent(remote: Remote<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    val data = remote.data
    Column(Modifier.fillMaxSize()) {
        if (remote.error != null) ErrorBanner(remote.error, onRetry)
        when {
            data != null -> content(data)
            remote.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = StatusColors.onStatus,
        modifier = modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// --- time ---

fun localTime(iso: String?): String {
    val t = parseServerTime(iso) ?: return iso.orEmpty()
    return SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(t)
}

// --- markdown, just enough for transcripts and forum posts ---

/**
 * A seat's or a post's Markdown, split into blocks by core's shared parser
 * (the Mac draws the same blocks): code, headings, lists, quotes, rules,
 * GitHub tables and paragraphs. Inside them: **bold**, `code` and tappable
 * http(s) links.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val blocks = remember(text) { Markdown.blocks(text) }
    val styles = InlineStyles(
        code = SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceVariant),
        link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)),
    )
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { i, block ->
                // List items sit close together; everything else gets a paragraph's gap.
                val gap = when {
                    i == 0 -> 0.dp
                    block.isListItem() && blocks[i - 1].isListItem() -> 2.dp
                    else -> 6.dp
                }
                Box(Modifier.padding(top = gap)) { MarkdownBlock(block, styles, style) }
            }
        }
    }
}

private fun MdBlock.isListItem() = this is MdBlock.Bullet || this is MdBlock.Numbered

@Composable
private fun MarkdownBlock(block: MdBlock, styles: InlineStyles, body: TextStyle) {
    when (block) {
        is MdBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
            Text(
                block.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            )
        }
        is MdBlock.Heading -> Text(
            inline(block.text, styles),
            style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        is MdBlock.Bullet -> ListItem("•", block.text, block.indent, styles, body)
        is MdBlock.Numbered -> ListItem("${block.number}.", block.text, block.indent, styles, body)
        // outline, not outlineVariant: in the dark theme outlineVariant is the
        // bubble's own colour, which drew the quote bar and the rule invisibly.
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            Text(inline(block.text, styles), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp))
        }
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline)
        is MdBlock.Table -> MarkdownTable(block, styles)
        is MdBlock.Paragraph -> Text(inline(block.text, styles), style = body)
    }
}

@Composable
private fun ListItem(marker: String, text: String, indent: Int, styles: InlineStyles, body: TextStyle) {
    Row(Modifier.padding(start = (indent * 16).dp)) {
        Text(marker, style = body, modifier = Modifier.padding(end = 6.dp))
        Text(inline(text, styles), style = body)
    }
}

/**
 * A GitHub table, as the Mac draws it: a bold header, a divider under it and
 * thin ones between rows, in a rounded box that scrolls sideways when wide.
 * Laid out by column, each as wide as its widest cell; cells stay on one line
 * so the rows line up across columns.
 */
@Composable
private fun MarkdownTable(table: MdBlock.Table, styles: InlineStyles) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val copier = rememberCopier()
    Column {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, line),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                table.header.forEachIndexed { c, head ->
                    val align = when (table.alignments[c]) {
                        "center" -> TextAlign.Center
                        "right" -> TextAlign.End
                        else -> TextAlign.Start
                    }
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        TableCell(head, align, styles, bold = true)
                        HorizontalDivider(color = line, thickness = 1.dp)
                        table.rows.forEachIndexed { r, row ->
                            if (r > 0) HorizontalDivider(color = line, thickness = 0.5.dp)
                            TableCell(row[c], align, styles, bold = false)
                        }
                    }
                }
            }
        }
        // A selection runs down the columns, so it cannot copy rows: this copies the cells,
        // a row per line and a tab between cells, which a spreadsheet pastes as a grid.
        DisableSelection {
            TextButton(onClick = { copier(tableTabSeparated(table)) }, modifier = Modifier.align(Alignment.End)) {
                Text("Copy table")
            }
        }
    }
}

/** Copies text to the clipboard; Android 13 and later confirm a copy themselves. */
@Composable
internal fun rememberCopier(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) {
        { text ->
            clipboard.setText(AnnotatedString(text))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun TableCell(text: String, align: TextAlign, styles: InlineStyles, bold: Boolean) {
    Text(
        inline(text, styles),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else null,
        textAlign = align,
        softWrap = false,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private val bold = SpanStyle(fontWeight = FontWeight.Bold)

/** Theme-dependent inline styles; inline() is not composable, so they are passed in. */
private class InlineStyles(val code: SpanStyle, val link: TextLinkStyles)

private fun inline(text: String, styles: InlineStyles): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        appendInline(line, styles)
    }
}

private fun AnnotatedString.Builder.appendInline(line: String, styles: InlineStyles) {
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("**", i) && line.indexOf("**", i + 2) > i + 2 -> {
                val end = line.indexOf("**", i + 2)
                // Bold can hold links; code (below) stays literal.
                withStyle(bold) { appendInline(line.substring(i + 2, end), styles) }
                i = end + 2
            }
            line[i] == '`' && line.indexOf('`', i + 1) > i -> {
                val end = line.indexOf('`', i + 1)
                withStyle(styles.code) { append(line.substring(i + 1, end)) }
                i = end + 1
            }
            line.startsWith("https://", i) || line.startsWith("http://", i) -> {
                val end = linkEnd(line, i)
                val url = line.substring(i, end)
                withLink(LinkAnnotation.Url(url, styles.link)) { append(url) }
                i = end
            }
            else -> {
                append(line[i]); i++
            }
        }
    }
}
