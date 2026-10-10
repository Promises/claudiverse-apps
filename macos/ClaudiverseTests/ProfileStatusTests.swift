import ClaudiverseCore
import XCTest
@testable import Claudiverse

/// The last start reads as history: its outcome with its time, and how long it took once the server says when it was asked for.
final class ProfileStatusTests: XCTestCase {
    func testAStartWithItsRequestTimeShowsHowLongItTook() {
        let line = ProfileStatus.lastStart(ProfileStart(at: "2026-10-05T14:04:16Z", outcome: "started", reason: nil, seat: "204109", requestedAt: "2026-10-05T14:04:09Z"))
        XCTAssertTrue(line.hasPrefix("Last start: started "), line)
        XCTAssertTrue(line.hasSuffix("(took 7 s)"), line)
    }

    /// A server before requested_at: core falls back to the outcome's time, so no duration is made up.
    func testWithoutARequestTimeThereIsNoDuration() {
        let line = ProfileStatus.lastStart(ProfileStart(at: "2026-10-05T14:04:16Z", outcome: "started", reason: nil, seat: nil, requestedAt: "2026-10-05T14:04:16Z"))
        XCTAssertFalse(line.contains("took"), line)
    }

    /// Server 6a2f344: a stop is recorded on the start it ended, and the history says so.
    func testAStoppedStartSaysWhenItStopped() {
        let start = ProfileStart(at: "2026-10-05T14:04:16Z", outcome: "started", reason: nil, seat: "204109", requestedAt: "2026-10-05T14:04:09Z", stoppedAt: "2026-10-05T14:10:00Z")
        let line = ProfileStatus.lastStart(start)
        XCTAssertTrue(line.contains("(took 7 s) · stopped "), line)
        XCTAssertEqual(ProfileStatus.color("unregistered"), Theme.attention)
    }

    func testAStartStillUnderWayHasNoDurationAndARefusalKeepsItsReason() {
        XCTAssertFalse(ProfileStatus.lastStart(ProfileStart(at: "2026-10-05T14:04:12Z", outcome: "starting", reason: nil, seat: nil, requestedAt: "2026-10-05T14:04:09Z")).contains("took"))
        let refused = ProfileStatus.lastStart(ProfileStart(at: "2026-10-05T14:04:10Z", outcome: "refused", reason: "outside_roots: /etc", seat: nil, requestedAt: "2026-10-05T14:04:09Z"))
        XCTAssertTrue(refused.hasSuffix("(took 1 s) — outside_roots: /etc"), refused)
    }
}
