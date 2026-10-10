package dev.claudiverse.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A topic is joined once per socket, so views showing the same seat share its
 * channel. Moving a board tile builds the new tile before the old one goes:
 * the old one's leave must not leave the topic under the new one.
 */
class ChannelClaimsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // No server: the socket never opens, so only the claims are exercised.
    private val socket = PhoenixSocket({ ServerAccess(baseUrl = "", token = "") }, scope)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun aLeaveByOneOfTwoUsersKeepsTheChannelJoined() {
        val old = socket.channel("session:1").also { it.join() }
        val new = socket.channel("session:1").also { it.join() }
        assertSame(old, new)

        old.leave()
        assertTrue(new.wanted)
        assertEquals(1, new.users)
        assertSame(new, socket.channel("session:1").also { it.leave() }, "still registered for the topic")

        new.leave()
        assertFalse(new.wanted)
        assertNotSame(new, socket.channel("session:1"), "the last leave drops the topic")
    }

    @Test
    fun extraLeavesDoNotBorrowFromALaterClaim() {
        val a = socket.channel("session:2").also { it.join() }
        a.leave()
        a.leave()
        val b = socket.channel("session:2").also { it.join() }
        assertEquals(1, b.users)
        assertTrue(b.wanted)
    }
}
