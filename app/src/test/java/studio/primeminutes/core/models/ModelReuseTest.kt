package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** mobile-v2 E4B replaces mobile-v1 E4B: the choice migrates, the old model reads until the new one is
 *  installed, then goes; v1's embedders and tokenizer are lent instead of downloaded again. */
class ModelReuseTest {
    private val v1 = LlmRegistry.byId(LlmRegistry.E4B_MOBILE_ID)
    private val v2 = LlmRegistry.byId(LlmRegistry.E4B_MOBILE_V2_ID)

    @Test fun v2IsANewIdWithItsOwnFolderAndPrompt() {
        assertEquals(LlmRegistry.E4B_MOBILE_V2_ID, v2.id)
        assertNotEquals(v1.dirName, v2.dirName)
        assertEquals("044150e8e18c05f807c88ca88358953f81fcc74fe85959184d66c65d81fd4843", v2.files.getValue(v2.systemPromptFile).second)
        assertEquals(LlmRegistry.E2B_MOBILE_ID, LlmRegistry.DEFAULT_ID)   // the default does not move
        // Only the main graph and the prompt differ from mobile-v1 E4B.
        val shared = v2.files.values.toSet().intersect(v1.files.values.toSet())
        assertEquals(3, shared.size)
    }

    @Test fun anInstalledModelLendsItsIdenticalFiles() {
        val root = Files.createTempDirectory("models").toFile()
        val dirOf = { s: LlmSpec -> File(root, s.dirName) }
        val rel = "mobile-v2/mfa/Section2_TFLiteModel_tf_lite_embedder.tflite"
        val (bytes, _) = v2.files.getValue(rel)
        val src = File(dirOf(v1), "mfa/Section2_TFLiteModel_tf_lite_embedder.tflite").apply { parentFile!!.mkdirs() }
        java.io.RandomAccessFile(src, "rw").use { it.setLength(bytes) }
        assertEquals(src, ModelManager.reuseSource(v2, rel, LlmRegistry.ALL, dirOf) { it.id == v1.id })
        assertNull(ModelManager.reuseSource(v2, rel, LlmRegistry.ALL, dirOf) { false })                     // v1 not installed
        assertNull(ModelManager.reuseSource(v2, "mobile-v2/mfa/prefill_decode_fused.tflite", LlmRegistry.ALL, dirOf) { true })   // a new file
        src.delete(); root.deleteRecursively()
    }

    @Test fun v1IsReplacedAndNoLongerOffered() {
        assertEquals(LlmRegistry.E4B_MOBILE_V2_ID, v1.replacedBy)
        assertEquals(v2.id, LlmRegistry.current(v1.id).id)
        assertEquals(v2.id, LlmRegistry.current(v2.id).id)
        assertEquals(LlmRegistry.E2B_MOBILE_ID, LlmRegistry.current(LlmRegistry.E2B_MOBILE_ID).id)   // E2B stays mobile-v1
        assertEquals(listOf(LlmRegistry.E2B_MOBILE_ID, v2.id), LlmRegistry.OFFERED.map { it.id })
    }

    /** Mid-migration the user always has a reader: v1 runs until v2 is installed. */
    @Test fun theOldModelRunsUntilTheNewOneIsInstalled() {
        assertEquals(v1.id, LlmRegistry.runnable(v1.id) { it.id == v1.id }.id)   // v2 not yet there
        assertEquals(v1.id, LlmRegistry.runnable(v2.id) { it.id == v1.id }.id)   // chose E4B, v1 still on the phone
        assertEquals(v2.id, LlmRegistry.runnable(v1.id) { true }.id)             // both: the new one
        assertEquals(v2.id, LlmRegistry.runnable(v1.id) { false }.id)            // none: download the new one
        assertEquals(LlmRegistry.E2B_MOBILE_ID, LlmRegistry.runnable(LlmRegistry.E2B_MOBILE_ID) { it.id == v1.id }.id)
    }

    @Test fun theOldModelGoesOnlyOnceTheNewOneIsInstalled() {
        assertEquals(emptyList<String>(), LlmRegistry.reclaimable { it.id == v1.id }.map { it.id })
        assertEquals(listOf(v1.id), LlmRegistry.reclaimable { it.id == v2.id }.map { it.id })
        assertEquals(emptyList<String>(), LlmRegistry.reclaimable { it.id == LlmRegistry.E2B_MOBILE_ID }.map { it.id })
    }
}
