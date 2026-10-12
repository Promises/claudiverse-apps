import ClaudiverseCore
import XCTest
@testable import Claudiverse

/// Pooled login is the default, and never on the patched runtime, which fails over itself.
final class ProfileDraftTests: XCTestCase {
    func testANewProfileIsPooled() {
        XCTAssertTrue(ProfileDraft(ProfileDraft.new(machine: "my-mac")).fields.pooled)
    }

    /// A new profile starts in auto mode, as a new seat does (NewSeatState's default, in core).
    func testANewProfileIsInAutoMode() {
        XCTAssertEqual(ProfileDraft(ProfileDraft.new(machine: "my-mac")).fields.permissionMode, "auto")
    }

    func testAPatchedProfileSavesAndShowsUnpooled() {
        var draft = ProfileDraft(ProfileDraft.new(machine: "my-mac"))
        draft.runtime = "patched"
        XCTAssertFalse(draft.showsPooled)
        XCTAssertFalse(draft.fields.pooled, "never saved pooled on the patched runtime")
        draft.runtime = "stock+mod"
        XCTAssertTrue(draft.fields.pooled, "back on stock+mod, the operator's choice holds again")
    }
}
