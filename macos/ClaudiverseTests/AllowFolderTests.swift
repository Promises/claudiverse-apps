import XCTest
@testable import Claudiverse

/// The breadcrumb's levels, and when a seat's folder is already allowed.
final class AllowFolderTests: XCTestCase {
    private let path = "/Users/me/Documents/projects/standalone-bob/server/host"

    func testEachSegmentSelectsItsLevel() {
        XCTAssertEqual(AllowFolderView.segments(path), ["Users", "me", "Documents", "projects", "standalone-bob", "server", "host"])
        XCTAssertEqual(AllowFolderView.prefix(path, 3), "/Users/me/Documents")
        XCTAssertEqual(AllowFolderView.prefix(path, 7), path)
    }

    /// Inside a root, or the root itself; a sibling that merely shares the name's start is not.
    func testInsideARootIsByWholeSegments() {
        let roots = ["/Users/me/Documents/projects"]
        XCTAssertTrue(AdoptSheet.inside(path, roots))
        XCTAssertTrue(AdoptSheet.inside("/Users/me/Documents/projects", roots))
        XCTAssertFalse(AdoptSheet.inside("/Users/me/Documents/projects-old/x", roots))
        XCTAssertFalse(AdoptSheet.inside("/Users/me/Documents/archive", roots))
    }
}
