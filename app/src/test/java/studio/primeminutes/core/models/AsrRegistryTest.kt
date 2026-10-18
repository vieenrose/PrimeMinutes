package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recognizer variants: the chosen one runs once installed, else the closest installed one; never none if any. */
class AsrRegistryTest {
    private fun v(ms: Int) = AsrRegistry.byLatency(ms)

    @Test fun fourVariantsDefault1920() {
        assertEquals(listOf(160, 480, 960, 1920), AsrRegistry.LATENCIES)
        assertEquals(1920, AsrRegistry.DEFAULT_MS)
        assertEquals(1920, v(12345).latencyMs)   // a removed option reads as the default
    }

    @Test fun eachVariantHasItsOwnFolderAndFile() {
        assertEquals(4, AsrRegistry.ALL.map { it.dirName }.toSet().size)
        assertEquals(4, AsrRegistry.ALL.map { it.tflite.file }.toSet().size)
        assertTrue(AsrRegistry.ALL.all { it.tflite.url.endsWith("/" + it.tflite.file) && it.tflite.bytes > 0 && it.tflite.sha256.length == 64 })
    }

    @Test fun theChosenVariantRunsOnceInstalled() {
        assertEquals(960, AsrRegistry.runnable(960) { true }!!.latencyMs)
    }

    @Test fun untilThenTheClosestInstalledOneRuns() {
        assertEquals(1920, AsrRegistry.runnable(960) { it.latencyMs == 1920 || it.latencyMs == 160 }!!.latencyMs)
        assertEquals(160, AsrRegistry.runnable(480) { it.latencyMs == 160 || it.latencyMs == 1920 }!!.latencyMs)
        assertEquals(960, AsrRegistry.runnable(480) { it.latencyMs == 160 || it.latencyMs == 960 }!!.latencyMs)   // tie: more accurate
    }

    @Test fun nothingInstalledIsNull() {
        assertNull(AsrRegistry.runnable(1920) { false })
    }
}
