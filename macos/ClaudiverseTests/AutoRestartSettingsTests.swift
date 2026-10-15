import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// Auto-restart settings: the minutes the field takes, the override a fleet's row sends, and what applies to a fleet.
final class AutoRestartSettingsTests: XCTestCase {
    private func fleet(paused: Bool = false, override: AutoRestartOverride?, effective: AutoRestart?) -> Fleet {
        Fleet(fleet: "lorem-fleet", runners: [], seats: [], members: [], paused: paused, autoRestart: override, autoRestartEffective: effective)
    }

    func testMinutesStayInTheServersBounds() {
        XCTAssertEqual(AutoRestartWording.minuteRange, 1...1440)
        XCTAssertEqual(AutoRestartWording.seconds(minutes: 5), 300)
        XCTAssertEqual(AutoRestartWording.seconds(minutes: 0), 60, "below a minute is a minute")
        XCTAssertEqual(AutoRestartWording.seconds(minutes: 5000), 86_400, "above a day is a day")
        XCTAssertEqual(AutoRestartWording.parseMinutes(" 10 "), 10)
        XCTAssertNil(AutoRestartWording.parseMinutes(""))
        XCTAssertNil(AutoRestartWording.parseMinutes("ten"))
        XCTAssertNil(AutoRestartWording.parseMinutes("0"))
        XCTAssertEqual(AutoRestartWording.idle(minutes: 5), "5 min")
        XCTAssertEqual(AutoRestartWording.idle(minutes: 60), "1 h")
        XCTAssertEqual(AutoRestartWording.idle(minutes: 90), "1 h 30 min")
    }

    /// The row sends its override whole: a field left as global is null, and both as global clears it.
    func testAFleetRowSendsItsWholeOverride() {
        let cleared = AutoRestartWording.override(enabled: .inherit, minIdleS: nil)
        XCTAssertTrue(cleared.isEmpty)
        let on = AutoRestartWording.override(enabled: .on, minIdleS: nil)
        XCTAssertEqual(on.enabled?.boolValue, true)
        XCTAssertNil(on.minIdleS)
        let off = AutoRestartWording.override(enabled: .off, minIdleS: 600)
        XCTAssertEqual(off.enabled?.boolValue, false)
        XCTAssertEqual(off.minIdleS?.int32Value, 600)
        XCTAssertEqual(AutoRestartWording.Choice(nil), .inherit)
        XCTAssertEqual(AutoRestartWording.Choice(true), .on)
        XCTAssertEqual(AutoRestartWording.Choice(false), .off)
    }

    func testAFleetSaysWhatApplies() {
        XCTAssertEqual(AutoRestartWording.effective(fleet(override: nil, effective: AutoRestart(enabled: true, minIdleS: 300))),
                       "Now: on, after 5 min idle (as global)")
        XCTAssertEqual(AutoRestartWording.effective(fleet(override: AutoRestartOverride(enabled: KotlinBoolean(value: false), minIdleS: nil),
                                                          effective: AutoRestart(enabled: false, minIdleS: 300))), "Now: off")
        XCTAssertEqual(AutoRestartWording.effective(fleet(paused: true, override: nil, effective: AutoRestart(enabled: true, minIdleS: 300))),
                       "Paused: never auto-restarted")
        XCTAssertEqual(AutoRestartWording.effective(fleet(override: nil, effective: nil)), "The server does not say")
    }
}
