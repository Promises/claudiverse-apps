package dev.claudiverse.core

import kotlin.test.assertEquals
import kotlin.test.Test

class LinkEndTest {

    private fun link(line: String): String {
        val start = line.indexOf("http")
        return line.substring(start, linkEnd(line, start))
    }

    @Test
    fun aBareLinkRunsToTheEnd() {
        assertEquals("https://github.com/example/claudiverse-apps/releases/tag/build-386b120d",
            link("https://github.com/example/claudiverse-apps/releases/tag/build-386b120d"))
    }

    @Test
    fun sentencePunctuationIsNotPartOfTheLink() {
        assertEquals("https://x.y/a", link("See https://x.y/a."))
        assertEquals("https://x.y/a", link("At https://x.y/a, then"))
        assertEquals("https://x.y/a", link("Open https://x.y/a!"))
    }

    @Test
    fun aClosingBracketTheLinkDidNotOpenIsLeftOut() {
        assertEquals("https://x.y/a", link("(see https://x.y/a)"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", link("https://en.wikipedia.org/wiki/Foo_(bar)"))
    }

    @Test
    fun theLinkStopsAtWhitespaceAndQuotes() {
        assertEquals("https://x.y/a", link("https://x.y/a and more"))
        assertEquals("https://x.y/a", link("\"https://x.y/a\""))
    }
}
