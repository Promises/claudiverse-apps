# Markdown, tables and copy

Seats write Markdown, and forum posts and task bodies are Markdown too.
Claudiverse draws it with a small parser in the shared core
(`Markdown.blocks`) and a SwiftUI view on top (`MarkdownText`). The parser
handles only what seats actually write; everything else stays plain
paragraph text. The phone app uses the same parser, so a table renders the
same on both.

This page covers what is parsed, how it is drawn, what you can select, and
the ways to copy: a table as cells or as Markdown, a whole message, or a
whole conversation.

## How it works

`MarkdownText(text, fill:)` calls `Markdown.shared.blocks(text:)`, which
returns a list of blocks. The Mac then folds that list into segments:

```mermaid
flowchart LR
    T[text] --> P[Markdown.blocks]
    P --> B[MdBlock list]
    B --> S[MarkdownText.segments]
    S --> Pr[prose: one Text]
    S --> C[code: CodeBlock]
    S --> Tb[table: TableBlock]
    S --> R[rule: Divider]
```

### Block rules (core `Markdown.kt`)

The text is split on `\n` and read line by line. A line is tested in this
order.

1. **Fenced code.** A line of three backticks or three tildes, optionally
   followed by a language word (no spaces or backticks in it), starts a code
   block. The block runs until a line that, trimmed, equals the opening
   marker exactly (backticks close backticks, tildes close tildes), or to the
   end of the text if none comes. The content is kept verbatim. The language
   is kept in the block, but the Mac does not use it.
2. **Table.** A line that contains a pipe (and is not just `|`), followed by
   a delimiter row, starts a table. See below.
3. Then, per line:
   - A blank line ends the current paragraph.
   - **Rule.** Three or more of the same character from `-`, `*`, `_`,
     optionally spaced (`---`, `* * *`, `___`). A rule line is tested before
     bullets, so `* * *` is a rule, not a list.
   - **Heading.** One to six `#`, then at least one whitespace character,
     then text. Closing `#` characters are stripped. `#tag` without a space
     is not a heading.
   - **Bullet.** `-`, `*` or `+`, whitespace, text.
   - **Numbered item.** One to nine digits, then `.` or `)`, whitespace,
     text. The number is kept as written.
   - **Quote.** `>` with an optional following space. Consecutive quote
     lines become one quote block, joined by newlines.
   - Anything else is added to the current paragraph; a paragraph's lines
     are joined with `\n`.

Bullets and numbered items carry an indent: leading whitespace with each tab
counted as two spaces, divided by two. List items are single lines; there
are no multi-line items, task-list boxes or setext headings (`===` under a
line). Block quotes do not nest or contain other blocks. HTML, footnotes,
images (`![](...)`) and reference links are not parsed and stay as text.

### Tables

A row is any line that contains a `|` and is not a lone `|`. A table starts
at a row whose next line is a delimiter row. A delimiter row must contain a
`-`, and every cell of it must be optional spaces, optional `:`, one or
more `-`, optional `:`, optional spaces (`---`, `:--`, `:-:`, `--:`).

- Cells are split on pipes that are not escaped (`\|`) and not inside a
  backtick span. Outer pipes are optional (`a | b` and `| a | b |` are both
  rows). Each cell is trimmed; `\|` becomes `|` in the cell text.
- Alignment per column: `:x:` is `center`, `x:` is `right`, `:x` is `left`,
  plain `x` is none.
- The header row sets the table's width. Alignments are padded or cut to
  that width. Body rows are padded with empty cells or cut.
- Body rows continue while each next line is a row (contains a pipe). A
  blank line, or any line without a pipe, ends the table.
- Pipes without a delimiter row are an ordinary paragraph (`x | y` on two
  lines stays text).
- A single-column table needs a pipe in the header line (`| a |`), since a
  row must contain one.

Cell contents are inline Markdown, drawn by the Mac as described next.

### Inline styles (Mac `MarkdownText.swift`)

Paragraph, heading, list, quote and table cell text goes through
`MarkdownText.inline`, which uses Apple's inline Markdown parser
(`AttributedString(markdown:)`, whitespace preserved): **bold**, *italic*,
`code` and `[text](url)` links, plus whatever else that parser accepts. If
the parser throws, the line is drawn as plain text.

**Bare URLs.** Before parsing, `linkify` turns each bare `http://` or
`https://` URL into an autolink (`<url>`). Where the URL ends is decided by
core's `linkEnd`:

- it stops at whitespace or at any of `<`, `>`, `"`, `'`, `` ` ``;
- then trailing `.`, `,`, `;`, `:`, `!`, `?` are dropped;
- then a trailing `)` or `]` is dropped if the URL contains more of that
  closing bracket than of its opening one. `(see https://x.y/a)` links
  `https://x.y/a`; `https://en.wikipedia.org/wiki/Foo_(bar)` keeps its
  bracket.

A URL is left alone when the character just before it is `(`, `<` or `[`
(so it is already inside a Markdown link or autolink), or when an odd number
of backticks precede it on the line (it is inside code).

**Links open** as SwiftUI opens a link in a `Text`. The app installs no
`openURL` handler of its own for Markdown text, so a click goes to the
system's default handling for the URL.

### What each block looks like

| Block | Drawn as |
|---|---|
| Paragraph | Inline text. |
| Heading | Levels 1 and 2: `title3`, semibold. Levels 3 to 6: `headline`. |
| Bullet | Four spaces of indent per level, then a grey `•`, a space, the text. |
| Numbered | The indent, then the grey number and a dot (`2.`), a space, the text. |
| Quote | An accent-coloured bar `▎ `, then the text in the secondary colour. |
| Rule | A `Divider` with a little vertical padding. |
| Code | A `CodeBlock`: monospaced `callout` text, scrolling sideways (no wrapping), at most 360 points high, on the code background. |
| Table | A `TableBlock` (below). |

### Prose is one text; code, tables and rules stand apart

`segments` merges paragraphs, headings, list items and quotes into a single
`Text` (a "prose" segment). Items of one list sit on consecutive lines; other
blocks are separated by a small gap. A code block, a table or a rule ends the
prose segment, and later prose starts a new one.

### Selection

`MarkdownText` does not turn selection on. The containers do, with
`.textSelection(.enabled)`: the transcript's scroll content, the forum
thread view, and a task's body. Within that:

- **Can be selected together:** all prose of one message: its paragraphs,
  headings, list items and quotes, in one drag.
- **Cannot be selected together:** prose with a code block, a table or a
  rule between (each is its own text); a table's cells (each cell is its own
  `Text`); anything across two messages. A selection stops at the segment or
  message edge.

For text that has to cross those edges, use the copy routes below, in
particular "Show as Text".

## Configuration

Nothing is persisted or configurable. The one parameter is `fill`.

| Parameter | Default | Meaning |
|---|---|---|
| `fill` (`MarkdownText(_:fill:)`) | `true` | `true`: the view takes the full width offered (`maxWidth: .infinity`, left aligned). `false`: it hugs its text. |

Chat bubbles in the transcript (your messages, Claude's replies, and the
streaming reply) pass `fill: false`, so short messages make short bubbles.
Forum post bodies and task bodies use the default.

## Interface

### Copying a table

A table is drawn as a bold header row, a line under it, and body rows with
fainter lines between them, inside a rounded box that scrolls sideways when
the table is wider than the view. Column alignment follows the delimiter row:
`center` and `right` set the column's alignment in the grid (it is set from
the header cell); `left` or none leave it leading.

Two ways to copy, both whole-table (cells cannot be selected together):

- **Right-click the table**: a menu with "Copy Table" and "Copy Table as
  Markdown".
- **Hover the table**: a small copy icon (tooltip "Copy the table") appears
  at the top right. It opens a menu with the same two items.

"Copy Table" puts `tableTabSeparated` on the pasteboard, for pasting into a
spreadsheet. "Copy Table as Markdown" puts `tableMarkdown` on the pasteboard.

`tableTabSeparated`: the header and then each row, one per line (`\n`),
cells joined by a tab. Inline Markdown is removed from each cell
(`plainInline`): a link `[text](url)` becomes `text`; `**b**` and `__b__`
become `b`; `*i*` and `_i_` become `i` (only when the marks wrap a word and
are not inside a word, so `b_c` is unchanged); backticks are removed. A tab
inside a cell becomes a space. Alignment is not kept.

`tableMarkdown`: a header line `| a | b |`, a delimiter line, then the rows,
each as `| ` + cells joined by ` | ` + ` |`. Cell text is kept as parsed
(inline marks stay). The delimiter cells are `:---` for left, `:---:` for
center, `---:` for right, `---` for none.

#### Worked example

A seat writes:

```
| Seat | Status | Tasks |
|:-----|:------:|------:|
| `lorem-w1` | **running** | 3 |
| [lorem-w2](http://claudiverse.example:4000) | idle | 12 |
```

"Copy Table as Markdown" produces exactly (the delimiter line is rebuilt, so
the dash counts change):

```
| Seat | Status | Tasks |
| :--- | :---: | ---: |
| `lorem-w1` | **running** | 3 |
| [lorem-w2](http://claudiverse.example:4000) | idle | 12 |
```

"Copy Table" produces exactly this, with a tab character between cells and no
trailing newline:

```
Seat<TAB>Status<TAB>Tasks
lorem-w1<TAB>running<TAB>3
lorem-w2<TAB>idle<TAB>12
```

### Copying a message

Right-click a transcript row and choose "Copy" to put the whole item's text
on the pasteboard as written. For a reply or your own message, that is the
Markdown source, not the rendered text, so pipes, `**` and fences are
included. For a tool call it is `name: summary`, then the input, then the
result. Items with no text have no "Copy". Details: [Transcript](transcript.md).

There is no row click handler, on purpose: on macOS it would take the mouse
from the text and stop it being selected.

### Copying code

A `CodeBlock` has no copy button or menu of its own. Select its text and use
the standard Copy, or use the row's "Copy" to take the whole message
including the fenced code as Markdown source. Tool inputs and results, and a
permission's input, are shown in the same `CodeBlock`.

### Copying across messages: "Show as Text"

Because a selection cannot cross messages, the seat view's toolbar has
"Show as Text" (tooltip "The conversation as one text, to select and copy
across messages"). It opens a sheet titled with the thread's label, showing
the thread as one plain-text document in an `NSTextView` (`SelectableText`:
read-only, selectable). Each item sits under a heading line, items separated
by a blank line. The sheet has "Copy All" and "Done". Because the text is
plain, nothing is rendered: Markdown marks and table pipes show as written.
See [Transcript](transcript.md) for the headings and when the text is built.

## Failure modes and troubleshooting

The Markdown code shows no error messages. Things that look like faults:

| What you see | Cause | What to do |
|---|---|---|
| Table shows as lines with pipes | No delimiter row directly under the header, or the delimiter row has a cell that is not `-`s with optional colons (a stray character in it breaks it). | Add a second line such as `&#124;---&#124;---&#124;`. |
| Table ends early | A blank line or a line with no pipe. | Keep rows contiguous. |
| Cell text has extra or missing columns | The header sets the width; extra cells are cut, short rows are padded. | Match the header's column count. |
| A URL is linked without its trailing `)` or `.` | `linkEnd` drops unopened closing brackets and sentence punctuation. | Use `[text](url)` for a URL that really ends that way. |
| `**bold**` shows with its stars | The marks sit inside a backtick span, or Apple's inline parser rejected the line (it then falls back to plain text). | None needed; copy the source with "Copy". |
| Selection stops at a code block, table or message edge | Each is its own text. | Use "Show as Text", or "Copy Table" / "Copy". |
| Code line is cut off at the right | Code does not wrap. | Scroll sideways inside the block. |

## Related pages

- [Transcript](transcript.md)
- [Seat view](seat-view.md)
- [Forum](forum.md)
- [Tasks](tasks.md)
- [Images](images.md)

## Source

- `core/src/commonMain/kotlin/dev/claudiverse/core/Markdown.kt`
- `core/src/commonMain/kotlin/dev/claudiverse/core/Links.kt`
- `core/src/commonMain/kotlin/dev/claudiverse/core/Transcript.kt`
- `macos/Claudiverse/MarkdownText.swift`
- `macos/Claudiverse/TranscriptView.swift`
- `macos/Claudiverse/SeatDetailView.swift`
- `macos/Claudiverse/ForumViews.swift`
- `macos/Claudiverse/TasksAndPool.swift`
- `core/src/commonTest/kotlin/dev/claudiverse/core/MarkdownTest.kt`
- `core/src/commonTest/kotlin/dev/claudiverse/core/LinkEndTest.kt`
- `macos/ClaudiverseTests/MarkdownSelectionTests.swift`
