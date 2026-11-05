package dev.claudiverse.app.seat

import dev.claudiverse.core.RestartPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ForceableSkipTest {

    private fun plan(action: String, reasonCode: String? = null, reason: String? = null, isProtected: Boolean = false) =
        RestartPlan(sessionId = 1, title = "s", host = "m5", action = action, reason = reason, reasonCode = reasonCode,
            clientVersion = null, targetVersion = null, isProtected = isProtected)

    @Test
    fun aSkipForAnIdleCheckCanBeForcedInTheServersWords() {
        val r = forceableSkip(plan(RestartPlan.SKIP, "background", "s has 1 background task(s) running."))
        assertNotNull(r)
        assertEquals("s has 1 background task(s) running. Restart anyway? Its running work is stopped; the conversation is kept.", r!!.forcePrompt)
        for (code in listOf("busy", "permission", "question", "recent_activity")) assertNotNull(code, forceableSkip(plan(RestartPlan.SKIP, code)))
    }

    @Test
    fun nothingElseIsEverForced() {
        assertNull(forceableSkip(null))
        assertNull("restartable as it is", forceableSkip(plan(RestartPlan.RESTART)))
        assertNull("restartable as it is", forceableSkip(plan(RestartPlan.ADOPT)))
        assertNull("protected is its own confirm", forceableSkip(plan(RestartPlan.SKIP, "protected", isProtected = true)))
        for (code in listOf("runner", "fleet", "self", "not_running", "offline", "up_to_date", "refused", "max_seats", "new_code"))
            assertNull(code, forceableSkip(plan(RestartPlan.SKIP, code)))
        assertNull("no code", forceableSkip(plan(RestartPlan.SKIP, null, "busy")))
    }
}
