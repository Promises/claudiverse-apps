package dev.claudiverse.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** The default title must pass the host agent's check: [A-Za-z0-9._-]{1,64}. */
class TitleForTest {
    private val allowed = Regex("[A-Za-z0-9._-]{1,64}")

    @Test
    fun theFolderNameIsTheTitle() {
        assertEquals("mobile-app", seatTitleFor("~/dev/mobile-app"))
        assertEquals("ipsum-api", seatTitleFor("/Users/me/Documents/work/client/ipsum-api/"))
    }

    @Test
    fun charactersTheAgentRejectsBecomeDashes() {
        assertEquals("my-project-v2", seatTitleFor("~/code/my project:v2"))
        assertTrue(allowed.matches(seatTitleFor("~/Documents/Prosjekt æøå (gammel)")))
    }

    @Test
    fun leadingDotsAndLongNamesAreTrimmed() {
        assertEquals("config", seatTitleFor("~/.config"))
        assertEquals(64, seatTitleFor("/srv/" + "a".repeat(100)).length)
    }
}
