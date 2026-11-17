package dev.claudiverse.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The operator's "always 1 hour" bug: a length picked while On or Off is in force must reach the server. */
class PagingLengthTest {

    @Test
    fun aLengthPickedWhileOnIsSentWithIt() {
        assertEquals(true to 240, pagingRegrant(grant = true, minutes = 240))
        assertEquals(true to 1440, pagingRegrant(grant = true, minutes = 1440))
        assertEquals("until changed: minutes null, no expiry", true to null, pagingRegrant(grant = true, minutes = null))
        assertEquals(false to 240, pagingRegrant(grant = false, minutes = 240))
    }

    @Test
    fun aLengthPickedWhileDefaultSendsNothing() {
        assertNull(pagingRegrant(grant = null, minutes = 240))
    }
}
