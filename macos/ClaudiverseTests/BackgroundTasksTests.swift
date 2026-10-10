import ClaudiverseCore
@testable import Claudiverse
import XCTest

/// The background bar's folded line counts what runs, by kind.
final class BackgroundTasksTests: XCTestCase {
    private func task(_ id: String, _ type: String, _ status: String = "running") -> ClaudiverseCore.BackgroundTask {
        ClaudiverseCore.BackgroundTask(id: id, type: type, status: status, description: nil, command: "x", startedAt: nil, agentType: nil, name: nil, server: nil, tool: nil)
    }

    func testTheSummaryCountsByKind() {
        XCTAssertEqual(BackgroundTasksBar.summary([task("a", "shell"), task("b", "shell")]), "2 shells running")
        XCTAssertEqual(BackgroundTasksBar.summary([task("a", "shell"), task("b", "monitor")]), "1 shell, 1 monitor running")
        XCTAssertEqual(BackgroundTasksBar.summary([task("a", "shell", "completed")]), "1 shell")
    }
}
