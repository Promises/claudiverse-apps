import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// The Outbox panel, drawn as the old queued list: which rows can be cancelled, and what Send now says it does.
final class OutboxPanelTests: XCTestCase {
    private func row(_ kind: OutboxRow.Kind, held: Int64? = nil, deliverAt: Int64? = nil, interrupting: Bool = false) -> OutboxRow {
        OutboxRow(kind: kind, key: "\(kind)", text: "hi", cancellable: held != nil || kind == .serverHeld,
                  label: held != nil ? OutboxRow.companion.HELD : OutboxRow.companion.IN_QUEUE,
                  heldId: held.map { KotlinLong(value: $0) }, interrupting: interrupting,
                  messageId: kind == .serverHeld ? "m1" : nil, deliverAtMs: deliverAt.map { KotlinLong(value: $0) })
    }

    /// The trash is on every row: a message held here always cancels, one the server holds until its 15 s are up,
    /// one in Claude's queue (or sent to it) never.
    func testWhatCanBeCancelled() {
        XCTAssertTrue(OutboxPanel.canCancel(row(.held, held: 7), nowMs: 0))
        XCTAssertTrue(OutboxPanel.canCancel(row(.serverHeld, deliverAt: 15_000), nowMs: 14_999))
        XCTAssertFalse(OutboxPanel.canCancel(row(.serverHeld, deliverAt: 15_000), nowMs: 15_000), "greyed once its 15 s are up")
        XCTAssertFalse(OutboxPanel.canCancel(row(.queued), nowMs: 0))
        XCTAssertFalse(OutboxPanel.canCancel(row(.sent), nowMs: 0))
    }

    /// Send now delivers a held one interrupting the turn; on any other it interrupts, so Claude runs its queue.
    func testSendNowSaysWhatItDoes() {
        XCTAssertEqual(OutboxPanel.sendNowHelp(row(.held, held: 1)), "Deliver it now, interrupting the running turn")
        XCTAssertEqual(OutboxPanel.sendNowHelp(row(.serverHeld, deliverAt: 1)), "Deliver it now, interrupting the running turn")
        XCTAssertEqual(OutboxPanel.sendNowHelp(row(.queued)), "Interrupt the running turn: Claude then runs its queue, this one in turn")
    }
}
