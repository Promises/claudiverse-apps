package dev.claudiverse.core

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A join is answered by a replay; joined without one, a seat shows nothing for
 * as long as it stays open (the Mac's main window reuses a kept seat without
 * rejoining). [joined] starts a wait of [waitMs]; if no [replayed] came since
 * the channel was last [unjoined], [rejoin] runs, once per join: a server that
 * never replays a seat must not be asked again every [waitMs].
 */
class ReplayWatch(private val scope: CoroutineScope, private val waitMs: Long, private val rejoin: () -> Unit) {
    private val lock = SynchronizedObject()
    /** No replay since the channel was last unjoined. The replay may come before the join reply marks it joined. */
    private var awaiting = true
    private var retried = false
    private var watch: Job? = null

    fun joined() {
        synchronized(lock) {
            watch?.cancel()
            if (!awaiting || retried) return
            watch = scope.launch {
                delay(waitMs)
                val again = synchronized(lock) { (awaiting && !retried).also { if (it) retried = true } }
                if (again) rejoin()
            }
        }
    }

    fun unjoined() = synchronized(lock) {
        watch?.cancel()
        awaiting = true
        retried = false
    }

    fun replayed() = synchronized(lock) {
        awaiting = false
        watch?.cancel()
    }
}
