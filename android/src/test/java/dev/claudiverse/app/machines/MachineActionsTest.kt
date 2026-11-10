package dev.claudiverse.app.machines

import dev.claudiverse.core.AlreadyReopening
import dev.claudiverse.core.HostReboot
import dev.claudiverse.core.NothingToReopen
import dev.claudiverse.core.RebootBrief
import dev.claudiverse.core.RebootSeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineActionsTest {

    private val brief = RebootBrief("rolling", "2026-11-10T10:00:00Z", "2026-11-10T10:05:00Z", null,
        listOf(RebootSeat("pe-ceo", busy = true), RebootSeat("docs-mac", busy = false)), listOf("w1"))

    @Test
    fun anOlderAgentOrAnOfflineMachineOffersNothing() {
        assertEquals("agent older than 0.23", RebootActions.NONE, rebootActions(online = true, reboot = null))
        assertEquals("offline", RebootActions.NONE, rebootActions(online = false, reboot = HostReboot(brief, null, null, null)))
    }

    @Test
    fun aRecoverableListIsOfferedToReopen() {
        val a = rebootActions(online = true, reboot = HostReboot(recoverable = brief, prepared = null, lastReopen = null, reopening = null))
        assertTrue(a.canPrepare)
        assertEquals(brief, a.reopen)
        assertFalse(a.reopening)
        assertNull("nothing left to reopen", rebootActions(true, HostReboot(null, brief, null, null)).reopen)
    }

    @Test
    fun whileReopeningNothingIsOfferedAgain() {
        val a = rebootActions(online = true, reboot = HostReboot(recoverable = brief, prepared = null, lastReopen = null, reopening = brief))
        assertTrue(a.reopening)
        assertNull(a.reopen)
        assertFalse(a.canPrepare)
    }

    @Test
    fun refusalsSayWhy() {
        assertEquals("A reopen of m5 is running already", reopenNotice("m5", AlreadyReopening("409")))
        assertEquals("m5 has nothing to reopen", reopenNotice("m5", NothingToReopen("404")))
        assertEquals("Not reopened: boom", reopenNotice("m5", IllegalStateException("boom")))
        assertEquals("Not prepared: boom", prepareNotice("m5", IllegalStateException("boom")))
    }
}
