import ClaudiverseCore
import SwiftUI

/**
 * Markdown for transcripts and forum posts, drawn from core's blocks (the
 * phone uses the same parser): fenced code, headings, bullet and numbered
 * lists, quotes, rules, tables and paragraphs, with inline **bold**,
 * *italic*, `code` and links. A bare http(s) URL is a link too, ending where
 * core's linkEnd says (not on trailing punctuation or an unopened bracket).
 */
struct MarkdownText: View {
    let text: String
    /// Takes the full width offered; a bubble passes false to hug its text instead.
    var fill = true

    init(_ text: String, fill: Bool = true) {
        self.text = text
        self.fill = fill
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(Self.segments(Markdown.shared.blocks(text: text)).enumerated()), id: \.offset) { _, segment in
                switch segment {
                // Its full height, always: never shortened to make room for a block below it.
                case .prose(let text): Text(text).fixedSize(horizontal: false, vertical: true)
                case .code(let code): CodeBlock(text: code)
                case .table(let table): TableBlock(table: table)
                case .rule: Divider().padding(.vertical, 4)
                }
            }
        }
        .frame(maxWidth: fill ? .infinity : nil, alignment: .leading)
    }

    /// What is drawn: prose as one text, so a selection runs across its paragraphs, headings and list items; code and
    /// tables apart. (A selection cannot cross from one text to the next.)
    enum Segment {
        case prose(AttributedString)
        case code(String)
        case table(MdBlockTable)
        case rule
    }

    static func segments(_ blocks: [MdBlock]) -> [Segment] {
        var out: [Segment] = []
        var prose: AttributedString?
        var lastWasItem = false
        func flush() {
            if let p = prose { out.append(.prose(p)) }
            prose = nil
        }
        func add(_ part: AttributedString, item: Bool = false) {
            if var p = prose {
                // Items of one list sit line under line; other blocks get a little air between them.
                if item && lastWasItem { p += AttributedString("\n") } else { p += Self.gap }
                p += part
                prose = p
            } else {
                prose = part
            }
            lastWasItem = item
        }
        for block in blocks {
            switch onEnum(of: block) {
            case .code(let c): flush(); out.append(.code(c.text))
            case .table(let t): flush(); out.append(.table(t))
            case .rule: flush(); out.append(.rule)
            case .heading(let h):
                var t = inline(h.text)
                t.font = h.level <= 2 ? .title3.weight(.semibold) : .headline
                add(t)
            case .bullet(let b): add(item("•", b.text, Int(b.indent)), item: true)
            case .numbered(let n): add(item("\(n.number).", n.text, Int(n.indent)), item: true)
            case .quote(let q):
                var bar = AttributedString("▎ ")
                bar.foregroundColor = Theme.accent.opacity(0.6)
                var t = inline(q.text)
                t.foregroundColor = .secondary
                add(bar + t)
            case .paragraph(let p): add(inline(p.text))
            }
        }
        flush()
        return out
    }

    /// A blank line, small: the spacing blocks had when each was its own text.
    private static var gap: AttributedString {
        var g = AttributedString("\n\n")
        g.font = .system(size: 4)
        return g
    }

    private static func item(_ marker: String, _ text: String, _ indent: Int) -> AttributedString {
        var m = AttributedString(String(repeating: "    ", count: indent) + marker + " ")
        m.foregroundColor = .secondary
        return m + inline(text)
    }

    /// Inline Markdown, with bare URLs made links first. Falls back to the plain text if it does not parse.
    static func inline(_ line: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: linkify(line), options: options)) ?? AttributedString(line)
    }

    /// `https://x.y/a.` becomes `<https://x.y/a>.`, an autolink; URLs already in a link, an autolink or code are left alone.
    static func linkify(_ line: String) -> String {
        let s = line as NSString
        var out = ""
        var at = 0
        var search = NSRange(location: 0, length: s.length)
        while true {
            let found = [s.range(of: "https://", range: search), s.range(of: "http://", range: search)]
                .filter { $0.location != NSNotFound }.min { $0.location < $1.location }
            guard let start = found else { break }
            let end = Int(ClaudiverseCore.linkEnd(line: line, start: Int32(start.location)))
            let before = start.location > 0 ? s.substring(with: NSRange(location: start.location - 1, length: 1)) : ""
            let ticks = s.substring(to: start.location).filter { $0 == "`" }.count
            if end > start.location, !["(", "<", "["].contains(before), ticks % 2 == 0 {
                out += s.substring(with: NSRange(location: at, length: start.location - at))
                out += "<" + s.substring(with: NSRange(location: start.location, length: end - start.location)) + ">"
                at = end
            }
            let next = max(end, start.location + start.length)
            search = NSRange(location: next, length: s.length - next)
        }
        return out + s.substring(from: at)
    }
}

/**
 * A GitHub-style table: a bold header row, a line under it, the columns
 * aligned as the delimiter row says. Its cells are texts of their own, which
 * one selection cannot span, so the table copies whole: as cells for a
 * spreadsheet, or as Markdown (its menu, or the button showing on hover).
 */
private struct TableBlock: View {
    let table: MdBlockTable
    @State private var hovering = false

    var body: some View {
        ScrollView(.horizontal) {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                GridRow {
                    ForEach(Array(table.header.enumerated()), id: \.offset) { column, cell in
                        Text(MarkdownText.inline(cell)).fontWeight(.semibold)
                            .gridColumnAlignment(alignment(column))
                    }
                }
                Divider().gridCellUnsizedAxes(.horizontal)
                ForEach(Array(table.rows.enumerated()), id: \.offset) { index, row in
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { _, cell in
                            Text(MarkdownText.inline(cell))
                        }
                    }
                    if index < table.rows.count - 1 {
                        Divider().opacity(0.5).gridCellUnsizedAxes(.horizontal)
                    }
                }
            }
            .padding(10)
        }
        .scrollIndicators(.automatic)
        .background(Theme.codeBackground.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(.separator))
        .overlay(alignment: .topTrailing) {
            if hovering {
                Menu {
                    copyItems
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .padding(6)
                .help("Copy the table")
            }
        }
        .onHover { hovering = $0 }
        .contextMenu { copyItems }
    }

    @ViewBuilder
    private var copyItems: some View {
        Button("Copy Table") { TranscriptRows.copy(tableTabSeparated(table: table)) }
        Button("Copy Table as Markdown") { TranscriptRows.copy(tableMarkdown(table: table)) }
    }

    private func alignment(_ column: Int) -> HorizontalAlignment {
        switch column < table.alignments.count ? table.alignments[column] : "" {
        case "center": .center
        case "right": .trailing
        default: .leading
        }
    }
}
