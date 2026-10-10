package dev.claudiverse.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoVpnTest {

    private fun decide(reachable: Boolean, ours: Boolean = false, other: Boolean = false, auto: Boolean = true, ready: Boolean = true) =
        decideVpn(reachable, ours, other, auto, ready)

    @Test
    fun onTheLocalNetworkOurTunnelGoesDown() {
        assertEquals(VpnAction.Disconnect, decide(reachable = true, ours = true))
        assertEquals(VpnAction.None, decide(reachable = true, ours = false))
    }

    @Test
    fun awayWithNothingElseOurTunnelComesUp() {
        assertEquals(VpnAction.Connect, decide(reachable = false))
    }

    /** Android has one VPN slot: connecting would evict the operator's system VPN. */
    @Test
    fun anotherVpnIsNeverReplaced() {
        assertEquals(VpnAction.None, decide(reachable = false, other = true))
        assertEquals(VpnAction.None, decide(reachable = true, other = true))
    }

    @Test
    fun staysOffWhenAutoConnectIsOffOrTheProfileIsNotReady() {
        assertEquals(VpnAction.None, decide(reachable = false, auto = false))
        assertEquals(VpnAction.None, decide(reachable = false, ready = false))
    }

    @Test
    fun awayWithOurTunnelUpNothingChanges() {
        assertEquals(VpnAction.None, decide(reachable = false, ours = true))
    }
}
