import ClaudiverseCore
import XCTest
@testable import Claudiverse

/// A Z.ai window's bar: green, then yellow from 75%, red from 90%, as the Anthropic accounts'.
final class ZaiUsageTests: XCTestCase {
    func testTheBarWarmsAsTheWindowFills() {
        XCTAssertEqual(ZaiCard.tint(percent: 0), Theme.good)
        XCTAssertEqual(ZaiCard.tint(percent: 75), Theme.busy)
        XCTAssertEqual(ZaiCard.tint(percent: 100), Theme.bad)
    }

    private func key(_ id: Int64, _ provider: String?) -> ZaiKey {
        ZaiKey(id: id, label: "lorem-\(id)", level: nil, status: "ok", lastError: nil, checkedAt: nil, windows: [], provider: provider)
    }

    /// The Pool shows each plan under its own name: the Z.ai Coding Plan first, even with no key (keys are added there).
    func testEachPlanUnderItsOwnName() {
        let plans = ZaiSection.plans([key(1, "opencode-go"), key(2, nil), key(3, "zai"), key(4, "ipsum-plan"), key(5, "opencode-go")])
        XCTAssertEqual(plans.map(\.name), ["Z.ai Coding Plan", "OpenCode Go", "ipsum-plan"])
        XCTAssertEqual(plans.map { $0.keys.map(\.id) }, [[2, 3], [1, 5], [4]], "a key with no provider is a Z.ai one (an older server)")
        XCTAssertEqual(ZaiSection.plans([key(1, "opencode-go")]).map { $0.keys.count }, [0, 1], "Z.ai stays, empty")
        XCTAssertEqual(ZaiSection.plans([]).map(\.name), ["Z.ai Coding Plan"])
    }
}
