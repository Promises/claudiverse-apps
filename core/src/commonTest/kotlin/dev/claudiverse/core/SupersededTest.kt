package dev.claudiverse.core

import kotlin.test.assertEquals
import kotlin.test.Test

/** A resume registers a new row; the stopped rows it continues drop out of the list. */
class SupersededTest {
    private fun seat(id: Long, status: String, session: String?, conversation: String? = null) = Seat(
        id = id, title = "resume-e2e-test", displayTitle = null, status = status, activity = null, type = "sidecar",
        model = null, lastActivityAt = null, host = "my-mac", fleet = null, origin = "spawn",
        claudeSessionId = session, conversationId = conversation,
    )

    @Test
    fun aStoppedRowWhoseConversationRunsOnIsHidden() {
        val original = seat(204019, "stopped", "a5839ebf")
        // The resumed seat reports a placeholder session id; the server stores the real conversation.
        val resumed = seat(204030, "running", "placeholder-1", conversation = "a5839ebf")
        assertEquals(listOf(resumed), withoutSuperseded(listOf(original, resumed)))
    }

    @Test
    fun aChainOfResumesKeepsOnlyTheNewest() {
        val first = seat(1, "stopped", "conv")
        val second = seat(2, "stopped", "placeholder-1", conversation = "conv")
        val third = seat(3, "stopped", "placeholder-2", conversation = "conv")
        assertEquals(listOf(third), withoutSuperseded(listOf(first, second, third)))
    }

    @Test
    fun unrelatedAndRunningRowsStay() {
        val running = seat(5, "running", "x")
        val olderRunning = seat(4, "running", "placeholder", conversation = "x")
        val unknown = seat(6, "stopped", null)
        val other = seat(7, "stopped", "y")
        val all = listOf(olderRunning, running, unknown, other)
        assertEquals(all, withoutSuperseded(all))
    }
}
