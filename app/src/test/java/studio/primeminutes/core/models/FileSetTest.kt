package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A paused model fetch keeps the files it completed; a set from another revision is never half-trusted. */
class FileSetTest {
    private fun dir() = Files.createTempDirectory("set").toFile()
    private val files = listOf("a.bin", "b.bin")

    @Test fun aResumedFetchKeepsWhatItCompleted() {
        val d = dir()
        assertFalse(ModelManager.beginSet(d, "r2", files))   // first pass: fetch everything
        File(d, "a.bin").writeText("complete")                  // then paused before b.bin
        assertTrue(ModelManager.beginSet(d, "r2", files))    // the resume keeps a.bin
        assertEquals("complete", File(d, "a.bin").readText())
        ModelManager.endSet(d, "r2")
        assertFalse(File(d, ModelManager.FETCHING_MARKER).exists())
        assertEquals("r2", File(d, ModelManager.REVISION_MARKER).readText())
        assertTrue(ModelManager.beginSet(d, "r2", files))
    }

    @Test fun anotherRevisionsFilesAreDeletedBeforeTheFetchBegins() {
        val d = dir()
        File(d, "a.bin").writeText("old"); File(d, "b.bin").writeText("old"); File(d, "keep.txt").writeText("x")
        File(d, ModelManager.REVISION_MARKER).writeText("r1")
        assertFalse(ModelManager.beginSet(d, "r2", files))
        assertFalse(File(d, "a.bin").exists()); assertFalse(File(d, "b.bin").exists())
        assertTrue(File(d, "keep.txt").exists())
        // A fetch for r1 interrupted, then the pin moved to r3: r1's completed files are not kept.
        File(d, "a.bin").writeText("r2 bytes")
        assertFalse(ModelManager.beginSet(d, "r3", files))
        assertFalse(File(d, "a.bin").exists())
    }

    @Test fun unstampedFilesAreNotTrusted() {
        val d = dir()
        File(d, "a.bin").writeText("unknown")
        assertFalse(ModelManager.beginSet(d, "r1", files))
        assertFalse(File(d, "a.bin").exists())
    }

    // A re-pin of part of a set (X-ASR 480 ms -> 1920 ms, diarizer unchanged) keeps the unchanged files.
    @Test fun aRepinKeepsTheFilesWhosePinnedBytesAreOnDisk() {
        val d = dir()
        File(d, "diar.bin").writeText("diar"); File(d, "old.bin").writeText("old")
        File(d, ModelManager.REVISION_MARKER).writeText("r1")
        val diarSha = ModelManager.sha256Of(File(d, "diar.bin"))
        val pins = mapOf("diar.bin" to (4L to diarSha), "new.bin" to (3L to "00"))
        assertEquals(setOf("diar.bin"), ModelManager.carriedOver(d, "r2", pins))
        assertFalse(ModelManager.beginSet(d, "r2", pins.keys - setOf("diar.bin")))
        assertTrue(File(d, "diar.bin").exists())
    }

    @Test fun aRepinDoesNotKeepAFileWithTheRightSizeButOtherBytes() {
        val d = dir()
        File(d, "diar.bin").writeText("DIAR")
        File(d, ModelManager.REVISION_MARKER).writeText("r1")
        val pins = mapOf("diar.bin" to (4L to "ff"))
        assertTrue(ModelManager.carriedOver(d, "r2", pins).isEmpty())
    }

    @Test fun nothingIsCarriedOverWithinTheSameRevision() {
        val d = dir()
        File(d, "a.bin").writeText("a"); File(d, ModelManager.REVISION_MARKER).writeText("r2")
        assertTrue(ModelManager.carriedOver(d, "r2", mapOf("a.bin" to (1L to ModelManager.sha256Of(File(d, "a.bin"))))).isEmpty())
    }
}
