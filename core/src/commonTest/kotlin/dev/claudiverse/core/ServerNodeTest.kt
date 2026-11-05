package dev.claudiverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the socket must be reopened: it is joined on a copy that is not the
 * live one (2026-11-05: five hours on the standby after a proxy restart).
 */
class ServerNodeTest {
    private val blue = ServerNode("blue", active = true)
    private val green = ServerNode("green", active = true)

    /** The socket's own reply says it is on the standby: that alone is enough. */
    @Test
    fun aSocketOnTheStandbyReconnects() {
        assertTrue(mustReconnect(ServerNode("blue", active = false), live = null))
        assertTrue(mustReconnect(ServerNode("blue", active = false), live = green))
    }

    /**
     * Both copies say active, and the socket is on the other one: the case a
     * health read alone cannot see, since a fresh connection reaches the live
     * copy whatever the socket is joined on.
     */
    @Test
    fun aSocketOnAnotherCopyThanTheLiveOneReconnects() {
        assertTrue(mustReconnect(socket = blue, live = green))
        assertFalse(mustReconnect(socket = green, live = green))
    }

    /** A copy running alone (node null), or a live copy not known now: nothing to compare, so no reconnect loop. */
    @Test
    fun withNothingToCompareItStays() {
        assertFalse(mustReconnect(ServerNode(null, active = true), live = green))
        assertFalse(mustReconnect(blue, live = ServerNode(null, active = true)))
        assertFalse(mustReconnect(blue, live = null))
        assertFalse(mustReconnect(blue, live = ServerNode("green", active = false)))
        assertFalse(mustReconnect(socket = null, live = green))
    }

    /** A join reply's and /api/health's shapes, as the server (680b2ba) sends them. */
    @Test
    fun readsTheServersShapes() {
        fun o(s: String) = Json.parseToJsonElement(s).jsonObject
        assertEquals(ServerNode("blue", true), ServerNode.from(o("""{"node":"blue","active":true}""")))
        assertEquals(ServerNode("green", false), ServerNode.from(o("""{"active":false,"node":"green","status":"ok"}""")))
        assertEquals(ServerNode(null, true), ServerNode.from(o("""{"node":null,"active":true}""")))
        assertNull(ServerNode.from(o("""{"status":"ok"}""")), "an older server's reply says nothing")
        assertNull(ServerNode.from(null))
    }
}
