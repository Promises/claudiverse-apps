package dev.claudiverse.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thinking bubble: trimmed, and a preview only when it is long. */
class ThinkingTextTest {
    @Test
    fun shortThinkingIsShownWholeAndTrimmed() {
        val (text, preview) = thinkingText("Checking the log.\n\n\n")
        assertEquals("Checking the log.", text)
        assertNull(preview)
    }

    @Test
    fun moreThanSixLinesPreviewsTheFirstSix() {
        val raw = (1..9).joinToString("\n") { "line $it" }
        assertEquals((1..6).joinToString("\n") { "line $it" } + "…", thinkingText(raw).second)
    }

    @Test
    fun oneLongParagraphIsCutAtTheCharacterLimit() {
        val preview = thinkingText("word ".repeat(200)).second!!
        assertTrue(preview.length <= 601 && preview.endsWith("…"))
    }
}
