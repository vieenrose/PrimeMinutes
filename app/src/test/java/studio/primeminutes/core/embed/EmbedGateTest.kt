package studio.primeminutes.core.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A query issued during a catch-up gets the embedder within one chunk, not after the meeting. */
class EmbedGateTest {
    private val chunkMs = 40L
    private val chunks = 100   // a 4 s "meeting"

    private fun indexer(gate: EmbedGate, started: CountDownLatch, order: MutableList<String>) = thread {
        gate.with(foreground = false) {
            started.countDown()
            repeat(chunks) { i ->
                gate.checkpoint()
                Thread.sleep(chunkMs)   // one embed call
                synchronized(order) { order += "c$i" }
            }
        }
    }

    @Test fun queryDuringCatchUpIsAdmittedWithinAChunk() {
        val gate = EmbedGate(); val started = CountDownLatch(1); val order = mutableListOf<String>()
        val bg = indexer(gate, started, order)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(300)
        val t0 = System.nanoTime()
        gate.with(foreground = true) { Thread.sleep(chunkMs); synchronized(order) { order += "q" } }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000 - chunkMs
        bg.join(10_000)
        assertTrue("query waited $waitedMs ms", waitedMs < 3 * chunkMs)
        assertEquals(chunks + 1, order.size)                  // the build still completes every chunk
        assertTrue(order.indexOf("q") < chunks / 2)           // and the query did not wait for its end
        assertEquals(1, gate.handovers)
    }

    @Test fun severalQueriesAllGoBeforeTheIndexerResumes() {
        val gate = EmbedGate(); val started = CountDownLatch(1); val order = mutableListOf<String>()
        val bg = indexer(gate, started, order)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val qs = (0 until 3).map { k -> thread { gate.with(true) { Thread.sleep(5); synchronized(order) { order += "q$k" } } } }
        val t0 = System.nanoTime()
        qs.forEach { it.join(5_000) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        bg.join(10_000)
        assertTrue("queries took $ms ms", ms < 3 * chunkMs + 100)
        val qIdx = order.withIndex().filter { it.value.startsWith("q") }.map { it.index }
        assertEquals(3, qIdx.size)
        assertTrue("queries interleaved with chunks: $order", qIdx.last() - qIdx.first() <= 3)
    }

    @Test fun noQueryNoHandover() {
        val gate = EmbedGate(); val started = CountDownLatch(1); val order = mutableListOf<String>()
        indexer(gate, started, order).join(10_000)
        assertEquals(0, gate.handovers); assertEquals(chunks, order.size)
    }
}
