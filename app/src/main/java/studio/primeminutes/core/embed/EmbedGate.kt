package studio.primeminutes.core.embed

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The embedder's one-at-a-time gate, with priority for user queries. A foreground holder (an Ask,
 * a semantic search) is admitted before any waiting background holder, and a background holder
 * (the indexer building a whole meeting) calls [checkpoint] between two chunks: when a query
 * waits, the gate is handed over and taken back after it. A query issued during a catch-up thus
 * waits at most one chunk (one embed call), not the whole meeting. Blocking: callers run on IO.
 */
class EmbedGate {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var held = false
    private var fgWaiting = 0
    /** Bumped each time a background holder hands the gate over (tests, logs). */
    @Volatile var handovers = 0; private set

    fun acquire(foreground: Boolean) = lock.withLock {
        if (foreground) fgWaiting++
        try {
            while (held || (!foreground && fgWaiting > 0)) cond.await()
        } finally { if (foreground) fgWaiting-- }
        held = true
    }

    fun release() = lock.withLock { held = false; cond.signalAll() }

    /** True when a foreground caller waits for the gate. */
    val queryWaiting: Boolean get() = lock.withLock { fgWaiting > 0 }

    /** Called by a background holder between two chunks: hands the gate to waiting queries, then
     *  takes it back. Returns true if it yielded (the caller re-checks what may have changed). */
    fun checkpoint(): Boolean {
        if (!queryWaiting) return false
        handovers++
        release()
        acquire(foreground = false)
        return true
    }

    inline fun <T> with(foreground: Boolean, block: () -> T): T {
        acquire(foreground)
        try { return block() } finally { release() }
    }
}
