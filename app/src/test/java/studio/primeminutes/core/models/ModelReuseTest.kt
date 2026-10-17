package studio.primeminutes.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** mobile-v2 E4B ships mobile-v1's embedders and tokenizer: an installed v1 lends them instead of a re-download. */
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
}
