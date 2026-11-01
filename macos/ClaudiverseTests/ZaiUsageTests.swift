import XCTest
@testable import Claudiverse

/// A Z.ai window's bar: green, then yellow from 75%, red from 90%, as the Anthropic accounts'.
final class ZaiUsageTests: XCTestCase {
    func testTheBarWarmsAsTheWindowFills() {
        XCTAssertEqual(ZaiCard.tint(percent: 0), Theme.good)
        XCTAssertEqual(ZaiCard.tint(percent: 75), Theme.busy)
        XCTAssertEqual(ZaiCard.tint(percent: 100), Theme.bad)
    }
}
