package dev.claudiverse.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

/** A joined seat with no replay joins again, once per join; one with its replay never does. */
class ReplayWatchTest {
    private fun watch(scope: TestScope, onRejoin: () -> Unit) = ReplayWatch(scope, 15_000, onRejoin)

    @Test
    fun noReplayRejoinsOncePerJoin() {
        val scope = TestScope()
        var rejoins = 0
        val w = watch(scope) { rejoins++ }
        w.joined()
        scope.advanceTimeBy(15_001); scope.runCurrent()
        assertEquals(1, rejoins)
        // A server that never replays this seat is not asked every 15 s.
        w.joined()
        scope.advanceTimeBy(60_000); scope.runCurrent()
        assertEquals(1, rejoins)
        // A new join (the channel went away and came back) may try once more.
        w.unjoined()
        w.joined()
        scope.advanceTimeBy(15_001); scope.runCurrent()
        assertEquals(2, rejoins)
    }

    @Test
    fun aReplayInTimeNeedsNoRejoin() {
        val scope = TestScope()
        var rejoins = 0
        val w = watch(scope) { rejoins++ }
        w.joined()
        scope.advanceTimeBy(5_000); scope.runCurrent()
        w.replayed()
        scope.advanceTimeBy(60_000); scope.runCurrent()
        assertEquals(0, rejoins)
        // The replay can arrive before the join reply marks the channel joined.
        w.unjoined()
        w.replayed()
        w.joined()
        scope.advanceTimeBy(60_000); scope.runCurrent()
        assertEquals(0, rejoins)
    }
}
