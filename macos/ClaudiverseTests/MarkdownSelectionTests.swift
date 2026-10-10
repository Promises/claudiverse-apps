import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// A message's prose is one text, so a selection runs across its paragraphs and list items; code and tables stand apart.
final class MarkdownSelectionTests: XCTestCase {
    private func kinds(_ markdown: String) -> [String] {
        MarkdownText.segments(Markdown.shared.blocks(text: markdown)).map {
            switch $0 {
            case .prose: "prose"
            case .code: "code"
            case .table: "table"
            case .rule: "rule"
            }
        }
    }

    func testParagraphsHeadingsAndListsAreOneText() {
        XCTAssertEqual(kinds("# Title\n\nFirst paragraph.\n\n- one\n- two\n\n> quoted\n\nLast."), ["prose"])
    }

    func testCodeTablesAndRulesSplitTheProse() {
        XCTAssertEqual(kinds("Before.\n\n```\nlet x = 1\n```\n\nAfter.\n\n---\n\n| a | b |\n|---|---|\n| 1 | 2 |"),
                       ["prose", "code", "prose", "rule", "table"])
    }

    func testTheProseKeepsEveryLine() {
        guard case .prose(let text) = MarkdownText.segments(Markdown.shared.blocks(text: "One.\n\n- two\n- three")).first else {
            return XCTFail("no prose")
        }
        let plain = String(text.characters)
        for line in ["One.", "• two", "• three"] { XCTAssertTrue(plain.contains(line), plain) }
    }
}

/// A table copies whole, since its cells can't be selected together.
final class TableCopyTests: XCTestCase {
    func testATableCopiesAsCellsAndAsMarkdown() {
        guard case .table(let t) = MarkdownText.segments(Markdown.shared.blocks(text: "| a | b |\n|---|---:|\n| **1** | 2 |")).first else {
            return XCTFail("no table")
        }
        XCTAssertEqual(tableTabSeparated(table: t), "a\tb\n1\t2")
        XCTAssertEqual(tableMarkdown(table: t), "| a | b |\n| --- | ---: |\n| **1** | 2 |")
    }
}
