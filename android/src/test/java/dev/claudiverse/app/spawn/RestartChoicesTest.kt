package dev.claudiverse.app.spawn

import dev.claudiverse.app.seat.restartOutcome
import dev.claudiverse.core.RestartPlan
import dev.claudiverse.core.SeatRestartEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Restart idle seats ticks by default, and how a finished restart reads. */
class RestartChoicesTest {
    private fun plan(id: Long, action: String, isProtected: Boolean = false) =
        RestartPlan(id, "lorem-w$id", "ipsum-box", null, action, null, null, "2.1.292", "2.1.296", isProtected = isProtected)

    @Test
    fun protectedAndSkippedSeatsStartUnticked() {
        val plan = listOf(plan(1, "restart"), plan(2, "adopt"), plan(3, "skip"), plan(4, "restart", isProtected = true))
        assertEquals(setOf(1L, 2L), defaultSelection(plan))
    }

    private fun event(outcome: String, newVersion: String? = "2.1.296", killed: Boolean = false, reason: String? = null, note: String? = null) =
        SeatRestartEvent(5, "lorem-w1", "ipsum-box", outcome, reason, null, "2.1.292", newVersion, null, killed, note, "r1", null)

    @Test
    fun aFinishedRestartReadsAsOneLine() {
        assertEquals("Restarted on 2.1.296", restartOutcome(event("restarted")))
        assertTrue(restartOutcome(event("restarted", killed = true)).contains("killed"))
        assertEquals("Adopted into a tmux seat on 2.1.296. The old window shows the exited claude.",
            restartOutcome(event("adopted", note = "The old window shows the exited claude.")))
        assertEquals("Not restarted: It is busy.", restartOutcome(event("skipped", reason = "It is busy.")))
        assertEquals("Restart failed: no reason given", restartOutcome(event("failed")))
    }
}
