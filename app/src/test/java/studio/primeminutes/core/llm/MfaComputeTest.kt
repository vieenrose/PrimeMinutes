package studio.primeminutes.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Two engines used from two threads never compute at once (the native attention op is not reentrant). */
class MfaComputeTest {
    @Test fun twoThreadsNeverComputeTogether() {
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val start = CountDownLatch(1)
        // Each thread stands for one engine's generate: many short native calls back to back.
        val threads = List(2) {
            Thread {
                start.await()
                repeat(200) {
                    MfaCompute.run {
                        val n = inside.incrementAndGet()
                        maxInside.accumulateAndGet(n, ::maxOf)
                        Thread.sleep(0, 200_000)
                        calls.incrementAndGet()
                        inside.decrementAndGet()
                    }
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }
        assertEquals(400, calls.get())
        assertEquals(1, maxInside.get())
    }

    @Test fun returnsTheBlockValueAndIsReentrant() {
        assertEquals(42, MfaCompute.run { MfaCompute.run { 42 } })
    }
}
