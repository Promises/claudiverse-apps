package dev.claudiverse.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the app offers Stop and Resume: only where the server could act on it. */
class SeatLifecycleTest {
    private val resumable = Seat(
        id = 204016, title = "spawn-e2e-test", displayTitle = null, status = "stopped", activity = null, type = "sidecar",
        model = null, lastActivityAt = null, host = "my-mac.local", fleet = null, origin = "spawn",
        workingDirectory = "/Users/me/Documents/projects/claudiverse-forum-dev", claudeSessionId = "5b0c7f9e-0000-4000-8000-000000000000",
    )

    @Test
    fun aStoppedSeatWithMachineAndConversationCanResume() {
        assertTrue(resumable.canResume)
        assertFalse(resumable.canStop)
        // A resumed seat's conversation is in conversation_id: its claude_session_id is a placeholder.
        assertTrue(resumable.copy(claudeSessionId = null, conversationId = "c0ffee00-0000-4000-8000-000000000000").canResume)
    }

    @Test
    fun aStoppedSeatWithoutMachineOrConversationCannot() {
        // The server answers 422 (nothing to resume from) or cannot reach a machine.
        assertFalse(resumable.copy(host = null).canResume)
        assertFalse(resumable.copy(claudeSessionId = null, conversationId = null).canResume)
    }

    @Test
    fun aRunningSeatCanStopOnlyWhenItReportsItsMachine() {
        val running = resumable.copy(status = "running")
        assertTrue(running.canStop)
        assertFalse(running.canResume)
        // 422 on the server: it cannot know which machine to ask.
        assertFalse(running.copy(host = null).canStop)
    }
}
