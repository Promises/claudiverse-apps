package dev.claudiverse.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The listeners on operator:events give their channel claim back on stop, so
 * a stop and start (the Mac signs out and in) never adds a claim that keeps
 * the topic joined after everyone has gone.
 */
class EventClaimsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // No server: the socket never opens, so only the claims are exercised.
    private val socket = PhoenixSocket({ ServerAccess(baseUrl = "", token = "") }, scope)

    @AfterTest
    fun tearDown() = scope.cancel()

    /** The claims on the topic: a probe's own, taken and given back. */
    private fun claims(): Int {
        val probe = socket.channel(Fleets.TOPIC)
        return (probe.users - 1).also { probe.leave() }
    }

    @Test
    fun poolEventsGiveTheirClaimBackOnStop() {
        val pool = PoolEvents(socket, scope)
        repeat(3) {
            pool.start()
            assertEquals(1, claims())
            pool.stop()
            assertEquals(0, claims())
        }
    }

    @Test
    fun hostEventsGiveTheirClaimBackOnStop() {
        val hosts = HostEvents(socket, scope)
        hosts.start()
        assertEquals(1, claims())
        hosts.stop()
        assertEquals(0, claims())
    }
}
