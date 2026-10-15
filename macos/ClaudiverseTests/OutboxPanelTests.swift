import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// The Outbox panel: which rows can be acted on, and how each kind looks.
final class OutboxPanelTests: XCTestCase {
    private func row(_ kind: OutboxRow.Kind, held: Int64? = nil, interrupting: Bool = false) -> OutboxRow {
        OutboxRow(kind: kind, key: "\(kind)", text: "hi", cancellable: held != nil,
                  label: held != nil ? OutboxRow.companion.HELD : OutboxRow.companion.IN_QUEUE,
                  heldId: held.map { KotlinLong(value: $0) }, interrupting: interrupting)
    }

    func testOnlyHeldRowsCanBeActedOn() {
        XCTAssertNotNil(row(.held, held: 7).heldId)
        XCTAssertNil(row(.queued).heldId)
        XCTAssertFalse(row(.queued).cancellable)
    }

    func testEachKindHasItsLook() {
        XCTAssertEqual(OutboxPanel.icon(row(.queued, interrupting: true)), "bolt.fill")
        XCTAssertEqual(OutboxPanel.icon(row(.sent)), "paperplane")
        XCTAssertEqual(OutboxPanel.icon(row(.held, held: 1)), "clock")
        XCTAssertEqual(OutboxPanel.color(row(.queued, interrupting: true)), Theme.attention)
        XCTAssertEqual(OutboxPanel.color(row(.held, held: 1)), Theme.busy)
    }
}
