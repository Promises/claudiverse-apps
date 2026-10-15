import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// The Outbox panel, drawn as the old queued list: which rows can be cancelled, and what Send now says it does.
final class OutboxPanelTests: XCTestCase {
    private func row(_ kind: OutboxRow.Kind, held: Int64? = nil, interrupting: Bool = false) -> OutboxRow {
        OutboxRow(kind: kind, key: "\(kind)", text: "hi", cancellable: held != nil,
                  label: held != nil ? OutboxRow.companion.HELD : OutboxRow.companion.IN_QUEUE,
                  heldId: held.map { KotlinLong(value: $0) }, interrupting: interrupting)
    }

    /// The trash can is there on every row, but only a held one can be cancelled.
    func testOnlyHeldRowsCanBeCancelled() {
        XCTAssertTrue(OutboxPanel.canCancel(row(.held, held: 7)))
        XCTAssertFalse(OutboxPanel.canCancel(row(.queued)))
        XCTAssertFalse(OutboxPanel.canCancel(row(.sent)))
        XCTAssertFalse(OutboxPanel.canCancel(row(.queued, interrupting: true)))
    }

    /// Send now delivers a held one interrupting the turn; on any other it interrupts, so Claude runs its queue.
    func testSendNowSaysWhatItDoes() {
        XCTAssertEqual(OutboxPanel.sendNowHelp(row(.held, held: 1)), "Deliver it now, interrupting the running turn")
        XCTAssertEqual(OutboxPanel.sendNowHelp(row(.queued)), "Interrupt the running turn: Claude then runs its queue, this one in turn")
    }
}
