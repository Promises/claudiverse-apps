import XCTest
@testable import Claudiverse

/// Unsent messages stay per seat until sent or cleared, across views and restarts.
@MainActor
final class DraftsTests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() {
        suite = "DraftsTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    func testADraftIsKeptPerSeatAndSurvivesARestart() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("draft-\(UUID().uuidString).png")
        try Data([1]).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }

        let drafts = Drafts(defaults: defaults)
        drafts.setText("half a thought", for: 1)
        drafts.setAttachments([file], for: 1)
        drafts.setText("other seat", for: 2)
        XCTAssertEqual(drafts.text(1), "half a thought", "switching to seat 2 does not touch seat 1")

        let reopened = Drafts(defaults: defaults)
        XCTAssertEqual(reopened.text(1), "half a thought")
        XCTAssertEqual(reopened.attachments(1).map(\.path), [file.path])
        XCTAssertEqual(reopened.text(2), "other seat")
    }

    func testSendingClearsOnlyThatSeat() {
        let drafts = Drafts(defaults: defaults)
        drafts.setText("a", for: 1)
        drafts.setText("b", for: 2)
        drafts.clear(1)
        XCTAssertEqual(Drafts(defaults: defaults).text(1), "")
        XCTAssertEqual(Drafts(defaults: defaults).text(2), "b")
    }

    /// A file deleted since the draft was saved would fail the send: it is dropped on load.
    func testAnAttachmentWhoseFileIsGoneIsDropped() {
        let drafts = Drafts(defaults: defaults)
        drafts.setText("see the screenshot", for: 1)
        drafts.setAttachments([URL(fileURLWithPath: "/nonexistent/\(UUID().uuidString).png")], for: 1)
        let reopened = Drafts(defaults: defaults)
        XCTAssertEqual(reopened.attachments(1), [])
        XCTAssertEqual(reopened.text(1), "see the screenshot")
    }
}
