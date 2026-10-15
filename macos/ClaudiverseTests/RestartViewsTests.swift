import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// Restart idle seats: what starts ticked, what the buttons say, how progress reads.
final class RestartViewsTests: XCTestCase {
    private func plan(_ id: Int64, _ action: String, protected: Bool = false, reason: String? = nil,
                      old: String? = "2.1.292", new: String? = "2.1.296") -> RestartPlan {
        RestartPlan(sessionId: id, title: "seat-\(id)", host: "my-mac", fleet: "lorem-fleet", action: action, reason: reason,
                    reasonCode: action == "skip" ? (protected && reason == "protected" ? "protected" : "busy") : nil,
                    clientVersion: old, targetVersion: new, modVersion: nil, targetModVersion: nil, argvNames: [], envNames: [],
                    isProtected: protected)
    }

    private func event(_ id: Int64, _ outcome: String, batch: String = "b1", killed: Bool = false, note: String? = nil,
                       reason: String? = nil, code: String? = nil, origin: String? = nil, host: String? = nil,
                       newSession: Int64? = nil) -> SeatRestartEvent {
        SeatRestartEvent(sessionId: id, title: nil, host: host, outcome: outcome, reason: reason, reasonCode: code, oldVersion: "2.1.292",
                         newVersion: outcome == "restarted" ? "2.1.296" : nil, newSessionId: newSession.map { KotlinLong(value: $0) },
                         killed: killed, note: note, restartId: "r\(id)", batchId: batch, origin: origin)
    }

    /// Ticked to start: everything it would restart or adopt, never a skip, never a protected seat.
    func testProtectedAndSkippedSeatsStartUnticked() {
        let p = [plan(1, "restart"), plan(2, "adopt"), plan(3, "restart", protected: true), plan(4, "skip", reason: "busy")]
        XCTAssertEqual(RestartWording.defaultTicked(p), [1, 2])
        // A protected seat the dry run skips for being protected can still be ticked; a busy one cannot.
        // Core's rule: chosen when skipped only for being protected; a protected seat that is busy cannot be.
        XCTAssertTrue(plan(5, "skip", protected: true, reason: "protected").choosable)
        XCTAssertFalse(plan(4, "skip", reason: "busy").choosable)
        XCTAssertFalse(plan(7, "skip", protected: true, reason: "busy").choosable)
        XCTAssertTrue(plan(1, "restart").choosable)
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

    /// A failure says why, from its reason when it has no note; one that did not register again may be running.
    func testAFailureSaysWhy() {
        let p = plan(1, "restart")
        let unregistered = RestartRow(plan: p, event: event(1, "failed", reason: "It did not register within 90 s.", code: "not_registered"))
        XCTAssertEqual(RestartProgressRow.note(unregistered), "It did not register within 90 s. It may be running anyway: look at its terminal.")
        XCTAssertEqual(RestartProgressRow.note(RestartRow(plan: p, event: event(1, "failed", reason: "The machine refused."))), "The machine refused.")
        XCTAssertEqual(RestartProgressRow.note(RestartRow(plan: p, event: event(1, "failed", note: "killed after 30 s"))), "killed after 30 s")
        XCTAssertNil(RestartProgressRow.note(RestartRow(plan: p, event: event(1, "restarted"))))
        XCTAssertEqual(RestartProgressRow.note(RestartRow(plan: plan(2, "skip", reason: "busy"), event: nil)), "busy")
    }

    /// A seat left stopped says so, shows its reason whole, and offers the command that brings it back.
    func testASeatLeftStoppedOffersItsResumeCommand() {
        let reason = "It did not start again after two tries. Bring it back with: cd ~/projects/x && claude --resume abc"
        let row = RestartRow(plan: plan(1, "restart"), event: event(1, "failed", reason: reason, code: "not_restarted"))
        XCTAssertEqual(RestartWording.status(row), "stopped")
        XCTAssertEqual(RestartProgressRow.note(row), reason)
        XCTAssertEqual(RestartProgressRow.resumeCommand(row), "cd ~/projects/x && claude --resume abc")
        let unconfirmed = RestartRow(plan: plan(1, "restart"), event: event(1, "failed", reason: "No row.", code: "not_registered"))
        XCTAssertEqual(RestartWording.status(unconfirmed), "unconfirmed")
        XCTAssertNil(RestartProgressRow.resumeCommand(unconfirmed))
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
                                     counts: ["restarted": KotlinInt(value: 2), "failed": KotlinInt(value: 1), "skipped": KotlinInt(value: 0)], origin: nil)
        XCTAssertEqual(RestartIdleSheet.summary(done), "Done: 2 restarted, 1 failed")
    }

    /// The server's auto-restart says so, on the seat and in Recent restarts; a person's restart reads as before.
    func testAnAutoRestartSaysSo() {
        XCTAssertEqual(RestartWording.autoOutcome(event(1, "restarted", origin: "auto")), "Auto-restarted on 2.1.296")
        XCTAssertEqual(RestartWording.autoOutcome(event(1, "skipped", reason: "it is mid-turn", origin: "auto")), "Auto-restart skipped: it is mid-turn")
        XCTAssertEqual(RestartWording.autoOutcome(event(1, "failed", reason: "spawn refused", origin: "auto")), "Auto-restart failed: spawn refused")
        XCTAssertEqual(RestartWording.outcome(event(1, "restarted", killed: true)), "Restarted on 2.1.296 (its old process had to be killed)")
        XCTAssertEqual(RestartWording.outcome(event(1, "failed", reason: "tmux new-session -s x", code: "not_restarted")),
                       "Stopped and not started again: tmux new-session -s x")
    }

    /// A seat's latest finished auto-restart: under its old row or the one it registered, never a person's or an unfinished one.
    func testASeatShowsItsLatestAutoRestart() {
        let events = [event(1, "restarted", origin: "auto"), event(1, "started", origin: "auto"), event(2, "restarted"),
                      event(3, "skipped", reason: "busy", origin: "auto", newSession: nil), event(4, "restarted", origin: "auto", newSession: 9)]
        XCTAssertEqual(RestartWording.latestAuto(events, seat: 1)?.outcome, "restarted")
        XCTAssertNil(RestartWording.latestAuto(events, seat: 2), "a person's restart is not an auto-restart")
        XCTAssertEqual(RestartWording.latestAuto(events, seat: 9)?.sessionId, 4, "found under the row it registered")
        XCTAssertNil(RestartWording.latestAuto(events, seat: 5))
    }

    /// Recent restarts: finished ones only, newest first, this machine's when the sheet is for one.
    func testRecentRestartsAreFinishedNewestFirst() {
        let events = [event(1, "restarted", host: "my-mac"), event(2, "started", host: "my-mac"),
                      event(3, "failed", host: "ipsum-box"), event(4, "skipped", origin: "auto", host: "my-mac")]
        XCTAssertEqual(RestartWording.recent(events, host: nil).map(\.sessionId), [4, 3, 1])
        XCTAssertEqual(RestartWording.recent(events, host: "my-mac").map(\.sessionId), [4, 1])
        XCTAssertEqual(RestartWording.recent(events, host: nil, limit: 1).map(\.sessionId), [4])
    }

    /// A row shows Claude Code's number, whichever client_version form the seat reports.
    func testTheVersionDropsTheMod() {
        XCTAssertEqual(RestartWording.claudeCode("2.1.300 (Claude Code) [claudiverse-mod 0.12.1]"), "2.1.300")
        XCTAssertEqual(RestartWording.claudeCode("2.1.296 (Claude Code) [claudiverse-mod]"), "2.1.296")
        XCTAssertEqual(RestartWording.claudeCode("2.1.292"), "2.1.292")
        XCTAssertNil(RestartWording.claudeCode(nil))
    }
}
