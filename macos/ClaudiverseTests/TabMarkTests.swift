import XCTest
@testable import Claudiverse

/// A board tab's mark: its most urgent tile, and the counts behind it.
final class TabMarkTests: XCTestCase {
    func testATabShowsItsMostUrgentTile() {
        XCTAssertEqual(TileState.mark([.idle, .busy, .off]), .busy)
        XCTAssertEqual(TileState.mark([.idle, .asking, .busy]), .asking)
        XCTAssertEqual(TileState.mark([.idle, .idle]), .idle)
        XCTAssertEqual(TileState.mark([.off]), .off)
        XCTAssertNil(TileState.mark([]), "an empty tab has no mark")
    }

    func testTheCountsSayWhatIsOnIt() {
        XCTAssertEqual(TileState.counts([.idle, .busy, .idle, .off, .asking]), "1 asking · 1 busy · 2 idle · 1 not running")
        XCTAssertEqual(TileState.counts([.idle]), "1 idle")
        XCTAssertEqual(TileState.counts([]), "Empty")
    }

    func testASeatTile() {
        XCTAssertEqual(TileState.seat(running: true, busy: true, asking: false), .busy)
        XCTAssertEqual(TileState.seat(running: true, busy: false, asking: false), .idle)
        XCTAssertEqual(TileState.seat(running: false, busy: false, asking: false), .off)
        XCTAssertEqual(TileState.seat(running: true, busy: false, asking: true), .asking, "asking outranks idle")
    }

    /// A busy runner is busy, or asking when its seat asks; idle is idle; halted or missing is off.
    func testARunnerTile() {
        XCTAssertEqual(TileState.runner(status: "busy", seat: nil), .busy)
        XCTAssertEqual(TileState.runner(status: "busy", seat: .idle), .busy, "its seat between turns is still the runner's work")
        XCTAssertEqual(TileState.runner(status: "busy", seat: .asking), .asking)
        XCTAssertEqual(TileState.runner(status: "idle", seat: nil), .idle)
        XCTAssertEqual(TileState.runner(status: "halted", seat: nil), .off)
        XCTAssertEqual(TileState.runner(status: "missing", seat: nil), .off)
    }
}
