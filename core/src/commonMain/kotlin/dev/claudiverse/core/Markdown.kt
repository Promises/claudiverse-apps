package dev.claudiverse.core

/** One block of a Markdown text, as the apps draw transcripts and forum posts. Inline styles are left to each app. */
sealed interface MdBlock {
    data class Code(val language: String?, val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    /** A list item; [indent] counts nesting levels (two spaces or a tab each). */
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Numbered(val number: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
    /** [alignments] has one entry per column: "left", "center", "right", or "" for none given. */
    data class Table(val header: List<String>, val alignments: List<String>, val rows: List<List<String>>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

/**
 * Just enough Markdown for what seats write: fenced code, headings, bullet
 * and numbered lists, quotes, rules, GitHub-style tables and paragraphs.
 * Anything else stays paragraph text. Both apps draw these blocks, so a table
 * a seat writes renders the same on the phone and the Mac.
 */
object Markdown {
    private val fence = Regex("""^\s*(```|~~~)\s*([^`\s]*)\s*$""")
    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
    private val quote = Regex("""^\s*>\s?(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val delimiterCell = Regex("""^\s*:?-+:?\s*$""")

    fun blocks(text: String): List<MdBlock> {
        val lines = text.split('\n')
        val out = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += MdBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val open = fence.matchEntire(line)
            if (open != null) {
                flush()
                val marker = open.groupValues[1]
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim() != marker) body += lines[i++]
                out += MdBlock.Code(open.groupValues[2].ifEmpty { null }, body.joinToString("\n"))
                i++ // the closing fence (or the end of the text)
                continue
            }
            if (isTableRow(line) && i + 1 < lines.size && isDelimiterRow(lines[i + 1])) {
                flush()
                val header = cells(line)
                val alignments = cells(lines[i + 1]).map(::alignment)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && isTableRow(lines[i])) rows += cells(lines[i++])
                val width = header.size
                out += MdBlock.Table(
                    header,
                    (alignments + List(width) { "" }).take(width),
                    rows.map { (it + List(width) { "" }).take(width) },
                )
                continue
            }
            when {
                line.isBlank() -> flush()
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                heading.matchEntire(line) != null -> {
                    flush()
                    val m = heading.matchEntire(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                bullet.matchEntire(line) != null -> {
                    flush()
                    val m = bullet.matchEntire(line)!!
                    out += MdBlock.Bullet(m.groupValues[2], indent(m.groupValues[1]))
                }
                numbered.matchEntire(line) != null -> {
                    flush()
                    val m = numbered.matchEntire(line)!!
                    out += MdBlock.Numbered(m.groupValues[2], m.groupValues[3], indent(m.groupValues[1]))
                }
                quote.matchEntire(line) != null -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size) {
                        val q = quote.matchEntire(lines[i]) ?: break
                        quoted += q.groupValues[1]
                        i++
                    }
                    out += MdBlock.Quote(quoted.joinToString("\n"))
                    continue
                }
                else -> paragraph += line
            }
            i++
        }
        flush()
        return out
    }

    private fun indent(lead: String): Int = lead.replace("\t", "  ").length / 2

    /** A row has a pipe outside code spans; one bar alone is text, not a table. */
    private fun isTableRow(line: String): Boolean = line.trim().let { it.contains('|') && it != "|" }

    private fun isDelimiterRow(line: String): Boolean {
        if (!line.contains('-')) return false
        val c = cells(line)
        return c.isNotEmpty() && c.all { delimiterCell.matches(it) }
    }

    /** The cells of a row: split on unescaped pipes outside `code`, the outer pipes optional. */
    internal fun cells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith('|')) s = s.substring(1)
        if (s.endsWith('|') && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var inCode = false
        var j = 0
        while (j < s.length) {
            val ch = s[j]
            when {
                ch == '\\' && j + 1 < s.length && s[j + 1] == '|' -> { cell.append('|'); j++ }
                ch == '`' -> { inCode = !inCode; cell.append(ch) }
                ch == '|' && !inCode -> { out += cell.toString().trim(); cell.clear() }
                else -> cell.append(ch)
            }
            j++
        }
        out += cell.toString().trim()
        return out
    }

    private fun alignment(cell: String): String {
        val c = cell.trim()
        return when {
            c.startsWith(':') && c.endsWith(':') -> "center"
            c.endsWith(':') -> "right"
            c.startsWith(':') -> "left"
            else -> ""
        }
    }
}

/**
 * A table to paste into a spreadsheet: one line per row, cells apart by tabs,
 * the header first, inline Markdown left out (`**b**` is b, a link is its text).
 */
fun tableTabSeparated(table: MdBlock.Table): String =
    (listOf(table.header) + table.rows).joinToString("\n") { row ->
        row.joinToString("\t") { plainInline(it).replace('\t', ' ') }
    }

/** A table as Markdown again, its column alignments kept. */
fun tableMarkdown(table: MdBlock.Table): String {
    fun line(cells: List<String>) = cells.joinToString(" | ", "| ", " |")
    val rule = table.header.indices.map { i ->
        when (table.alignments.getOrNull(i)) {
            "left" -> ":---"
            "center" -> ":---:"
            "right" -> "---:"
            else -> "---"
        }
    }
    return (listOf(line(table.header), line(rule)) + table.rows.map(::line)).joinToString("\n")
}

/** Inline Markdown as plain text: emphasis and code marks gone, a link as its text. */
internal fun plainInline(text: String): String =
    text.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")
        .replace(Regex("""(?<![\w*])[*_](\S(?:.*?\S)?)[*_](?![\w*])"""), "$1")
        .replace("`", "")
