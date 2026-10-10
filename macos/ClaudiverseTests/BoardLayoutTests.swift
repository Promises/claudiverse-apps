import XCTest
@testable import Claudiverse

/// The fleet board's layout rules: rows, widths, and moving tiles between rows.
final class BoardLayoutTests: XCTestCase {
    private let a = BoardLayout.Tile.member("a"), b = BoardLayout.Tile.member("b"), c = BoardLayout.Tile.runner("ipsum-w1"), d = BoardLayout.Tile.runner("ipsum-w2")

    private func board(_ rows: [[BoardLayout.Tile]]) -> BoardLayout {
        BoardLayout(rows: rows.map { BoardLayout.Row(height: 300, cells: $0.map { BoardLayout.Cell(tile: $0) }) })
    }

    private func shape(_ l: BoardLayout) -> [[String]] { l.rows.map { $0.cells.map(\.tile.id) } }

    func testTakingTheLastTileOfARowRemovesTheRow() {
        var l = board([[a], [b, c]])
        l.take(a)
        XCTAssertEqual(shape(l), [[b.id, c.id]])
    }

    func testMoveBeforeATileInAnotherRow() {
        var l = board([[a, b], [c, d]])
        l.move(a, before: d)
        XCTAssertEqual(shape(l), [[b.id], [c.id, a.id, d.id]])
    }

    /// Moving a row's only tile down: its row goes, and the tile lands in the row that was below it.
    func testShiftDownFromARowItEmpties() {
        var l = board([[a], [b], [c]])
        l.shift(a, by: 1)
        XCTAssertEqual(shape(l), [[b.id, a.id], [c.id]])
    }

    func testShiftPastTheEndsMakesNewRows() {
        var l = board([[a, b]])
        l.shift(a, by: -1)
        XCTAssertEqual(shape(l), [[a.id], [b.id]])
        l.shift(b, by: 1)
        XCTAssertEqual(shape(l), [[a.id], [b.id]], "b alone in the last row: moving it down makes a new last row, the same shape")
        l.shift(a, by: 1)
        XCTAssertEqual(shape(l), [[b.id, a.id]])
    }

    func testOwnRowGoesJustBelowAndKeepsTheHeight() {
        var l = board([[a, b, c], [d]])
        l.rows[0].height = 555
        l.ownRow(b)
        XCTAssertEqual(shape(l), [[a.id, c.id], [b.id], [d.id]])
        XCTAssertEqual(l.rows[1].height, 555)
    }

    func testMoveToTheEndOfARowWhoseOwnRowEmpties() {
        var l = board([[a], [b, c]])
        let target = l.rows[1].id
        l.move(a, toEndOf: target)
        XCTAssertEqual(shape(l), [[b.id, c.id, a.id]])
    }

    func testWidthsAreClamped() {
        var l = board([[a, b]])
        l.setWidth(a, 100)
        XCTAssertEqual(l.rows[0].cells[0].width, BoardLayout.widths.upperBound)
        l.setWidth(a, 0)
        XCTAssertEqual(l.rows[0].cells[0].width, BoardLayout.widths.lowerBound)
    }

    func testAppendNeverAddsATileTwice() {
        var l = BoardLayout()
        l.append(a)
        l.append(a)
        XCTAssertEqual(shape(l), [[a.id]])
    }

    /// A board saved before rows existed (tiles, columns, height) is read into rows of that many columns.
    func testAnOldBoardBecomesRows() throws {
        let fleet = "test-\(UUID())"
        let old = #"{"tiles":[{"member":{"_0":"a"}},{"member":{"_0":"b"}},{"runner":{"_0":"ipsum-w1"}}],"columns":2,"height":410}"#
        UserDefaults.standard.set(Data(old.utf8), forKey: "board.\(fleet)")
        defer { UserDefaults.standard.removeObject(forKey: "board.\(fleet)") }
        let l = try XCTUnwrap(BoardLayout.load(fleet))
        XCTAssertEqual(shape(l), [[a.id, b.id], [c.id]])
        XCTAssertEqual(l.rows.map(\.height), [410, 410])
    }
}
