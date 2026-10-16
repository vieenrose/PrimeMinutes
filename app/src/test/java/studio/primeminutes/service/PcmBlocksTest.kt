package studio.primeminutes.service

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.primeminutes.core.audio.WavWriter
import java.io.File

class PcmBlocksTest {

    /** A work WAV whose sample i holds (i % 1000) / 32768, so positions can be read back. */
    private fun ramp(seconds: Double): File {
        val f = File.createTempFile("pcm", ".wav").apply { deleteOnExit() }
        val n = (seconds * 16_000).toInt()
        WavWriter(f).use { w -> w.write(FloatArray(n) { (it % 1000) / 32767f }) }
        return f
    }

    private fun samples(blocks: List<FloatArray>) = blocks.flatMap { it.toList() }

    @Test fun readsTheWholeFileInRecorderSizedBlocks() = runBlocking {
        val blocks = wavChunks(ramp(1.0)).toList()
        assertEquals(16_000, samples(blocks).size)
        assertEquals(2048, blocks.first().size)
        assertEquals(999, Math.round(samples(blocks)[999] * 32768f))
    }

    @Test fun startsAtTheResumeOffset() = runBlocking {
        val s = samples(wavChunks(ramp(1.0), startSec = 0.5).toList())
        assertEquals(8_000, s.size)
        assertEquals(8_000 % 1000, Math.round(s[0] * 32768f))
    }

    @Test fun anEnrollmentClipStopsAtItsEnd() = runBlocking {
        val s = samples(wavChunks(ramp(2.0), startSec = 0.25, endSec = 0.75).toList())
        assertEquals(8_000, s.size)
        assertEquals(4_000 % 1000, Math.round(s[0] * 32768f))
    }

    @Test fun levelBucketsAreLogIsh() {
        assertEquals(0, micLevelBucket(0.01f))
        assertEquals(1, micLevelBucket(0.03f))
        assertEquals(2, micLevelBucket(0.07f))
        assertEquals(3, micLevelBucket(0.2f))
        assertEquals(4, micLevelBucket(0.3f))
        assertEquals(5, micLevelBucket(0.9f))
    }
}
