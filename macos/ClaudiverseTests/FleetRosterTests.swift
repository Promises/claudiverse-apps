import ClaudiverseCore
import XCTest
@testable import Claudiverse

/// A fleet's board may show seats of other fleets too: a guest tile is a member tile found among all seats.
final class FleetRosterTests: XCTestCase {
    private func seat(_ id: Int64, _ title: String, fleet: String?, origin: String? = "manual", status: String = "running") -> Seat {
        Seat(id: id, title: title, displayTitle: nil, status: status, activity: nil, type: nil, model: nil, lastActivityAt: nil,
             host: nil, fleet: fleet, origin: origin, canNotify: false, workingDirectory: nil, insertedAt: nil, mode: nil,
             claudeSessionId: nil, clientVersion: nil, conversationId: nil, access: nil, accessExplicit: false, notifyGrant: nil,
             notifyUntil: nil, profileId: nil, isProtected: false, backgroundRunning: 0)
    }

    private var seats: [Seat] {
        [seat(1, "lorem-orchestrator", fleet: "lorem"),
         seat(2, "ipsum-watcher", fleet: "ipsum"),
         seat(3, "ipsum-w1-t42", fleet: "ipsum", origin: "runner"),
         seat(4, "dolor-old", fleet: "dolor", status: "stopped"),
         seat(5, "loose-seat", fleet: nil),
         seat(6, "ipsum-watcher", fleet: "ipsum")]
    }

    func testOtherFleetsOfferTheirRunningSeatsButNoRunners() {
        let roster = FleetRoster(fleet: "lorem", seats: seats, live: nil)
        XCTAssertEqual(roster.memberTitles, ["lorem-orchestrator"])
        XCTAssertEqual(roster.otherFleets.map(\.fleet), ["ipsum", FleetRoster.noFleet], "no fleet last; a stopped seat's fleet not offered")
        XCTAssertEqual(roster.otherFleets.map { $0.seats.map(\.title) }, [["ipsum-watcher"], ["loose-seat"]], "one row per title, no runner")
    }

    /// A guest's tile finds its seat (the newest row of its title) and says whose it is; the fleet's own say nothing.
    func testAGuestTileFindsItsSeat() throws {
        let roster = FleetRoster(fleet: "lorem", seats: seats, live: nil)
        let guest = try XCTUnwrap(roster.member("ipsum-watcher"))
        XCTAssertEqual(guest.id, 6)
        XCTAssertEqual(roster.guestCaption(guest), "fleet ipsum")
        XCTAssertEqual(roster.member("dolor-old")?.id, 4, "a stopped guest already on the board still shows")
        XCTAssertNil(roster.guestCaption(try XCTUnwrap(roster.member("lorem-orchestrator"))))
        XCTAssertNil(roster.member("ipsum-w1-t42"), "a runner's seat is a runner space, never a member tile")
        XCTAssertNil(roster.member("lorem-nobody"))
    }
}
