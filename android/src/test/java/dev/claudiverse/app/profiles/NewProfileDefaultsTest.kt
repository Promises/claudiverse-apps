package dev.claudiverse.app.profiles

import org.junit.Assert.assertEquals
import org.junit.Test

/** A new profile starts in auto mode; a chosen mode is what it keeps. */
class NewProfileDefaultsTest {
    @Test
    fun aNewProfileDefaultsToAutoMode() {
        assertEquals("auto", ProfileEditorState().draft.permissionMode)
    }

    @Test
    fun aChosenModeIsKept() {
        val s = ProfileEditorState()
        assertEquals("plan", s.copy(draft = s.draft.copy(permissionMode = "plan")).draft.permissionMode)
    }
}
