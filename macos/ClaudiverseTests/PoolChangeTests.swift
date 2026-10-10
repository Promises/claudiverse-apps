import ClaudiverseCore
import XCTest
@testable import Claudiverse

/// The Pool tab's line for the newest "pool" event: what changed, to which account, and who changed it.
final class PoolChangeTests: XCTestCase {
    private let pool = Pool(
        accounts: [
            AnthropicAccount(id: 4, label: "Work", priority: 5, status: "available", cooldownUntil: nil, cooldownReason: nil, tokenValid: nil, usageCheckedAt: nil, limits: [], inUse: nil),
            AnthropicAccount(id: 3, label: "Personal", priority: 10, status: "available", cooldownUntil: nil, cooldownReason: nil, tokenValid: nil, usageCheckedAt: nil, limits: [], inUse: nil),
        ],
        seats: [], currentId: 3
    )

    func testAManualChangeNamesTheAccountAndWhoChoseIt() {
        let line = PoolView.describe(PoolChange(change: "preferred", by: "op@example.com", at: nil, currentId: 4, preferredId: 4), pool)
        XCTAssertEqual(line, "Preferred account changed to Work by op@example.com")
    }

    func testAFallbackNamesWhereSeatsWentAndAnUnknownAccountStillReads() {
        XCTAssertEqual(PoolView.describe(PoolChange(change: "degrade", by: nil, at: nil, currentId: 3, preferredId: 4), pool), "Seats moved to Personal")
        XCTAssertEqual(PoolView.describe(PoolChange(change: "upgrade", by: nil, at: nil, currentId: 99, preferredId: 4), pool), "Seats moved back to a better account")
    }
}
