import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// Restart idle seats: what starts ticked, what the buttons say, how progress reads.
final class RestartViewsTests: XCTestCase {
    private func plan(_ id: Int64, _ action: String, protected: Bool = false, reason: String? = nil,
                      old: String? = "2.1.292", new: String? = "2.1.296") -> RestartPlan {
        RestartPlan(sessionId: id, title: "seat-\(id)", host: "my-mac", fleet: "lorem-fleet", action: action, reason: reason,
                    reasonCode: nil, clientVersion: old, targetVersion: new, argvNames: [], envNames: [], isProtected: protected)
    }

    private func event(_ id: Int64, _ outcome: String, batch: String = "b1", killed: Bool = false, note: String? = nil) -> SeatRestartEvent {
        SeatRestartEvent(sessionId: id, title: nil, host: nil, outcome: outcome, reason: nil, reasonCode: nil, oldVersion: "2.1.292",
                         newVersion: outcome == "restarted" ? "2.1.296" : nil, newSessionId: nil, killed: killed, note: note,
                         restartId: "r\(id)", batchId: batch)
    }

    /// Ticked to start: everything it would restart or adopt, never a skip, never a protected seat.
    func testProtectedAndSkippedSeatsStartUnticked() {
        let p = [plan(1, "restart"), plan(2, "adopt"), plan(3, "restart", protected: true), plan(4, "skip", reason: "busy")]
        XCTAssertEqual(RestartWording.defaultTicked(p), [1, 2])
        // A protected seat the dry run skips for being protected can still be ticked; a busy one cannot.
        XCTAssertTrue(RestartWording.tickable(plan(5, "skip", protected: true, reason: "protected")))
        XCTAssertFalse(RestartWording.tickable(plan(4, "skip", reason: "busy")))
        XCTAssertTrue(RestartWording.tickable(plan(1, "restart")))
        XCTAssertEqual(RestartWording.confirm(2), "Restart 2 seats")
        XCTAssertEqual(RestartWording.confirm(1), "Restart 1 seat")
    }

    func testTheSeatsButtonSaysWhatItWillDo() {
        XCTAssertEqual(RestartSeatSheet.button(plan(1, "restart")), "Restart (resume on 2.1.296)")
        XCTAssertEqual(RestartSeatSheet.button(plan(1, "restart", new: nil)), "Restart")
        XCTAssertEqual(RestartSeatSheet.button(plan(1, "adopt")), "Adopt into a managed seat")
        XCTAssertEqual(RestartSeatSheet.describe(plan(1, "skip", reason: "it is mid-turn")), "Not now: it is mid-turn.")
        // A protected seat skipped only for that can still be restarted, on a second confirmation.
        let guarded = plan(6, "skip", protected: true, reason: "protected")
        XCTAssertEqual(RestartSeatSheet.button(guarded), "Restart (resume on 2.1.296)")
        XCTAssertTrue(RestartSeatSheet.describe(guarded).hasPrefix("It is protected"))
    }

    func testVersionsReadOldToNew() {
        XCTAssertEqual(RestartWording.versions("2.1.292", "2.1.296"), "2.1.292 → 2.1.296")
        XCTAssertEqual(RestartWording.versions("2.1.296", "2.1.296"), "2.1.296")
        XCTAssertEqual(RestartWording.versions(nil, "2.1.296"), "→ 2.1.296")
        XCTAssertNil(RestartWording.versions(nil, nil))
    }

    /// Progress: plan order, the latest outcome per seat in this batch, failures and kills in red.
    func testProgressFollowsTheBatchsEvents() {
        let p = [plan(1, "restart"), plan(2, "adopt"), plan(3, "restart")]
        let events = [event(1, "started"), event(1, "restarted"), event(2, "started"), event(3, "failed", batch: "other")]
        let rows = foldRestart(plan: p, events: events, batchId: "b1")
        XCTAssertEqual(rows.map { RestartWording.status($0) }, ["restarted", "restarting…", "waiting"])
        XCTAssertEqual(RestartProgressRow.color("failed"), Theme.bad)
        XCTAssertEqual(RestartProgressRow.icon("restarted"), "checkmark.circle.fill")
        let done = RestartBatchEvent(batchId: "b1", host: nil, state: "done", total: 3,
                                     counts: ["restarted": KotlinInt(value: 2), "failed": KotlinInt(value: 1), "skipped": KotlinInt(value: 0)])
        XCTAssertEqual(RestartIdleSheet.summary(done), "Done: 2 restarted, 1 failed")
    }
}
