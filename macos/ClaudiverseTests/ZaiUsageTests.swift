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

    private func key(_ id: Int64, _ provider: String?, account: String? = nil, priority: Int32? = nil) -> ZaiKey {
        ZaiKey(id: id, label: "lorem-\(id)", level: nil, status: "ok", lastError: nil, checkedAt: nil, windows: [], provider: provider,
               account: account, priority: priority.map { KotlinInt(value: $0) })
    }

    /// One Mistral account's API key and Vibe session are one card, where the first was; the others stay their own.
    func testAMistralAccountIsOneCard() {
        let keys = [key(5, "mistral", account: "ipsum@example.com", priority: 15), key(3, "opencode-go"),
                    key(8, "vibe", account: "ipsum@example.com", priority: 5), key(9, "vibe")]
        XCTAssertEqual(ZaiSection.entries(keys).map(\.id), ["account:ipsum@example.com", "key:3", "key:9"])
        let mistral = ZaiSection.plans(keys).first { $0.name == "Mistral (Vibe Pro)" }
        XCTAssertEqual(mistral?.keys.map(\.id), [5, 8, 9], "an API key and a Vibe session are one plan")
    }

    /// An account's rows say what each credential is and its order; Remove names it.
    func testAnAccountRowSaysWhatItIs() {
        XCTAssertEqual(ZaiAccountCard.detail(key(8, "vibe", account: "a", priority: 5)), "Vibe session · priority 5")
        XCTAssertEqual(ZaiAccountCard.detail(key(5, "mistral", account: "a")), "API key")
        XCTAssertEqual(ZaiKeyActions.removeTitle(key(8, "vibe", account: "a")), "Remove the Vibe session “lorem-8”?")
        XCTAssertEqual(ZaiKeyActions.removeTitle(key(3, "opencode-go")), "Remove the OpenCode Go key “lorem-3”?")
    }

    /// The Pool shows each plan under its own name: the Z.ai Coding Plan first, even with no key (keys are added there).
    func testEachPlanUnderItsOwnName() {
        let plans = ZaiSection.plans([key(1, "opencode-go"), key(2, nil), key(3, "zai"), key(4, "ipsum-plan"), key(5, "opencode-go"), key(6, "mistral")])
        XCTAssertEqual(plans.map(\.name), ["Z.ai Coding Plan", "OpenCode Go", "ipsum-plan", "Mistral (Vibe Pro)"])
        XCTAssertEqual(plans.map { $0.keys.map(\.id) }, [[2, 3], [1, 5], [4], [6]], "a key with no provider is a Z.ai one (an older server)")
        XCTAssertEqual(ZaiSection.plans([key(1, "opencode-go")]).map { $0.keys.count }, [0, 1], "Z.ai stays, empty")
        XCTAssertEqual(ZaiSection.plans([]).map(\.name), ["Z.ai Coding Plan"])
    }
}
