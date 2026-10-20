import XCTest
@testable import Claudiverse

/// A seat's accept-all switch: the badge to everyone when on, the way to turn it on only to a sign-in that may.
final class AcceptAllSwitchTests: XCTestCase {
    func testOnShowsTheBadgeToEveryone() {
        XCTAssertEqual(AcceptAllSwitch.shows(on: true, canChange: true, compact: false), .badge(turnOff: true))
        XCTAssertEqual(AcceptAllSwitch.shows(on: true, canChange: false, compact: false), .badge(turnOff: false), "no Turn off without app powers")
        XCTAssertEqual(AcceptAllSwitch.shows(on: true, canChange: true, compact: true), .badge(turnOff: false), "a tile shows the badge alone")
    }

    func testOffOffersItOnlyToASignInThatMayChangeIt() {
        XCTAssertEqual(AcceptAllSwitch.shows(on: false, canChange: true, compact: false), .arm)
        XCTAssertEqual(AcceptAllSwitch.shows(on: false, canChange: false, compact: false), .nothing)
        XCTAssertEqual(AcceptAllSwitch.shows(on: false, canChange: true, compact: true), .nothing, "never armed from a tile")
    }

    /// Not read yet, or a server without seat settings: nothing at all.
    func testUnknownShowsNothing() {
        XCTAssertEqual(AcceptAllSwitch.shows(on: nil, canChange: true, compact: false), .nothing)
    }
}
