import XCTest
@testable import Claudiverse

/// A fleet board's tabs: the board from before tabs becomes "Main", names stay unique, and the last tab stays.
final class BoardTabsTests: XCTestCase {
    private let a = BoardLayout.Tile.member("a"), w1 = BoardLayout.Tile.runner("ipsum-w1")

    private func layout(_ tiles: [BoardLayout.Tile]) -> BoardLayout {
        BoardLayout(rows: [BoardLayout.Row(height: 300, cells: tiles.map { BoardLayout.Cell(tile: $0) })])
    }

    /// The tiles row by row: layouts built separately have their own row and cell ids.
    private func shape(_ l: BoardLayout) -> [[String]] { l.rows.map { $0.cells.map(\.tile.id) } }

    /// Nothing saved with tabs yet: the fleet's one board is the "Main" tab, so nothing is lost.
    func testTheBoardFromBeforeTabsBecomesMain() {
        let tabs = BoardTabs.from(saved: nil, single: layout([a]))
        XCTAssertEqual(tabs.tabs.map(\.name), ["Main"])
        XCTAssertEqual(shape(tabs.tabs[0].layout), [[a.id]])
        XCTAssertEqual(BoardTabs.from(saved: nil, single: nil).tabs.map(\.name), ["Main"])
        // Saved tabs win over the old board, and an unreadable save falls back to it.
        var saved = BoardTabs(tabs: [.init(name: "Main", layout: layout([a])), .init(name: "Runners", layout: layout([w1]))])
        let data = try! JSONEncoder().encode(saved)
        XCTAssertEqual(BoardTabs.from(saved: data, single: layout([w1])), saved)
        XCTAssertEqual(shape(BoardTabs.from(saved: Data("junk".utf8), single: layout([w1])).tabs[0].layout), [[w1.id]])
        saved.tabs = []
        XCTAssertEqual(BoardTabs(tabs: saved.tabs).tabs.map(\.name), ["Main"], "never without a tab")
    }

    func testNewTabsGetUniqueNames() {
        var tabs = BoardTabs()
        tabs.add("  M1 ")
        tabs.add("m1")
        tabs.add("")
        tabs.add("")
        XCTAssertEqual(tabs.tabs.map(\.name), ["Main", "M1", "m1 2", "Tab 2", "Tab 3"])
        XCTAssertTrue(tabs.tabs.allSatisfy { $0.layout.rows.isEmpty }, "a new tab starts empty")
    }

    func testRenameIgnoresEmptyAndTakenNames() {
        var tabs = BoardTabs()
        let xps = tabs.add("XPS13")
        tabs.rename(xps, "  ")
        tabs.rename(xps, "main")
        XCTAssertEqual(tabs.tabs.map(\.name), ["Main", "XPS13"])
        tabs.rename(xps, "XPS15")
        XCTAssertEqual(tabs.tabs.map(\.name), ["Main", "XPS15"])
    }

    /// Deleting shows the tab before it; the last tab cannot go.
    func testRemoveKeepsATab() {
        var tabs = BoardTabs()
        let main = tabs.tabs[0].id
        let m1 = tabs.add("M1")
        let runners = tabs.add("Runners")
        XCTAssertEqual(tabs.remove(runners), m1)
        XCTAssertEqual(tabs.remove(main), m1, "the first one gone: the new first is shown")
        XCTAssertEqual(tabs.remove(m1), m1, "the last tab stays")
        XCTAssertEqual(tabs.tabs.map(\.name), ["M1"])
    }

    /// Each tab keeps its own layout; a tile can be on several.
    func testEachTabHasItsOwnLayout() {
        var tabs = BoardTabs()
        let main = tabs.tabs[0].id
        let m1 = tabs.add("M1")
        tabs.setLayout(main, layout([a, w1]))
        tabs.setLayout(m1, layout([a]))
        XCTAssertEqual(shape(tabs.layout(main)), [[a.id, w1.id]])
        XCTAssertEqual(shape(tabs.layout(m1)), [[a.id]])
        XCTAssertEqual(shape(tabs.layout(UUID())), [[a.id, w1.id]], "an unknown tab shows the first")
        tabs.move(m1, by: -5)
        XCTAssertEqual(tabs.tabs.map(\.name), ["M1", "Main"])
    }

    private final class MemoryStore: BoardStore {
        var values: [String: Data] = [:]
        func data(forKey key: String) -> Data? { values[key] }
        func set(_ value: Any?, forKey key: String) { values[key] = value as? Data }
    }

    /// A fleet with no tabs saved reads the same tabs every time: its "Main" is saved on the first read. A new
    /// "Main" on each read made a board that reloads on every change of the defaults loop at 100% CPU.
    func testAnUnsavedBoardReadsTheSameEveryTime() {
        let store = MemoryStore()
        let fleet = "lorem-fleet-\(UUID().uuidString)"
        let first = BoardTabs.load(fleet, store: store)
        XCTAssertNotNil(store.values["board3.\(fleet)"], "saved on the first read")
        XCTAssertEqual(BoardTabs.load(fleet, store: store), first)
        // An unreadable save is replaced the same way, then reads the same.
        store.values["board3.\(fleet)"] = Data("junk".utf8)
        let replaced = BoardTabs.load(fleet, store: store)
        XCTAssertEqual(BoardTabs.load(fleet, store: store), replaced)
    }

    /// A tab's colour is kept; tabs saved before colours read as uncoloured.
    func testATabKeepsItsColour() throws {
        var tabs = BoardTabs()
        let wt = tabs.add("worktree-b")
        tabs.setColor(wt, .teal)
        let back = BoardTabs.from(saved: try JSONEncoder().encode(tabs), single: nil)
        XCTAssertEqual(back.tabs.map(\.color), [nil, .teal])
        tabs.setColor(wt, nil)
        XCTAssertNil(tabs.tabs[1].color)
        let old = #"{"tabs":[{"id":"7D6C1D5E-3A43-4C55-9C26-0E8B5E2E1D11","name":"Main","layout":{"rows":[],"columnGap":12,"rowGap":12}}]}"#
        let read = BoardTabs.from(saved: Data(old.utf8), single: nil)
        XCTAssertEqual(read.tabs.map(\.name), ["Main"], "a save from before colours still reads")
        XCTAssertNil(read.tabs[0].color)
    }
}
