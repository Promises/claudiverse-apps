package dev.claudiverse.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {

    /** The table from the transcript that rendered as raw pipes on both apps. */
    @Test
    fun aGithubTableBecomesATable() {
        val text = """
            I tested it on this seat:

            | Step | Gap to the bottom |
            |---|---|
            | Opening the seat | -17 (at the bottom) |
            | New rows arrive | -17 (followed) |

            It's installed.
        """.trimIndent()
        val blocks = Markdown.blocks(text)
        assertEquals(3, blocks.size)
        val table = blocks[1] as MdBlock.Table
        assertEquals(listOf("Step", "Gap to the bottom"), table.header)
        assertEquals(listOf(listOf("Opening the seat", "-17 (at the bottom)"), listOf("New rows arrive", "-17 (followed)")), table.rows)
        assertEquals(MdBlock.Paragraph("It's installed."), blocks[2])
    }

    @Test
    fun alignmentsAndRaggedRowsAreKeptToTheHeadersWidth() {
        val table = Markdown.blocks("a | b | c\n:-- | :-: | --:\n1 | 2\n1 | 2 | 3 | 4").single() as MdBlock.Table
        assertEquals(listOf("left", "center", "right"), table.alignments)
        assertEquals(listOf(listOf("1", "2", ""), listOf("1", "2", "3")), table.rows)
    }

    /** A pipe inside `code` or escaped is cell text, not a column break. */
    @Test
    fun pipesInCodeOrEscapedStayInTheCell() {
        assertEquals(listOf("`a|b`", "c|d"), Markdown.cells("| `a|b` | c\\|d |"))
    }

    /** Without a delimiter row, lines with pipes are just text. */
    @Test
    fun pipesWithoutADelimiterRowAreAParagraph() {
        assertEquals(listOf(MdBlock.Paragraph("x | y\nz | w")), Markdown.blocks("x | y\nz | w"))
    }

    @Test
    fun codeFencesKeepTheirContentVerbatim() {
        val blocks = Markdown.blocks("before\n```kotlin\nval a = 1 | 2\n| not | a | table |\n```\nafter")
        assertEquals(MdBlock.Code("kotlin", "val a = 1 | 2\n| not | a | table |"), blocks[1])
        assertEquals(3, blocks.size)
    }

    @Test
    fun listsQuotesHeadingsAndRules() {
        val blocks = Markdown.blocks("# Title\n- one\n  - nested\n2. two\n> quoted\n> more\n---\nend")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Bullet("one", 0),
                MdBlock.Bullet("nested", 1),
                MdBlock.Numbered("2", "two", 0),
                MdBlock.Quote("quoted\nmore"),
                MdBlock.Rule,
                MdBlock.Paragraph("end"),
            ),
            blocks,
        )
    }

    /** A table copies as cells for a spreadsheet, or as Markdown with its alignments. */
    @Test
    fun aTableCopiesAsCellsOrMarkdown() {
        val table = Markdown.blocks("| Name | Size |\n|:---|---:|\n| **core** | `12` |\n| [mac](http://x) | 3 |").single() as MdBlock.Table
        assertEquals("Name\tSize\ncore\t12\nmac\t3", tableTabSeparated(table))
        assertEquals("| Name | Size |\n| :--- | ---: |\n| **core** | `12` |\n| [mac](http://x) | 3 |", tableMarkdown(table))
        assertEquals("a b_c *d", plainInline("*a* b_c *d"))
    }
}
