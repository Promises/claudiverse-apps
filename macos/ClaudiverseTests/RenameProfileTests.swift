@testable import Claudiverse
import XCTest

/// The Rename button is on only for a name the server accepts (1-64 of A-Z a-z 0-9 . _ -).
final class RenameProfileTests: XCTestCase {
    func testTheServersNameRule() {
        XCTAssertTrue(RenameProfileSheet.validName("ipsum-admin.2_b"))
        XCTAssertTrue(RenameProfileSheet.validName(String(repeating: "a", count: 64)))
        XCTAssertFalse(RenameProfileSheet.validName(""))
        XCTAssertFalse(RenameProfileSheet.validName(String(repeating: "a", count: 65)))
        XCTAssertFalse(RenameProfileSheet.validName("pe ceo"))
        XCTAssertFalse(RenameProfileSheet.validName("pe/ceo"))
    }
}
