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
}
