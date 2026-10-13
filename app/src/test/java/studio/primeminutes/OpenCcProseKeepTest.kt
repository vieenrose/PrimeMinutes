package studio.primeminutes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File

/**
 * The s2twp stage drops TWPhrases' software vocabulary ([OpenCcConverter.PROSE_KEEP]) so meeting
 * prose keeps 擴展性 / 權限 / 類型 / 項目. Pure check against the bundled dictionary (no Context).
 */
class OpenCcProseKeepTest {
    private val twPhrases: Map<String, String> by lazy {
        val f = listOf("src/main/assets/opencc/TWPhrases.txt", "app/src/main/assets/opencc/TWPhrases.txt")
            .map(::File).first { it.exists() }
        f.readLines().filter { it.isNotBlank() && !it.startsWith('#') && '\t' in it }
            .associate { it.substringBefore('\t') to it.substringAfter('\t').substringBefore(' ') }
    }

    @Test
    fun `every kept word is a real TWPhrases entry (list not stale)`() {
        OpenCcConverter.PROSE_KEEP.forEach { k ->
            assertTrue("$k no longer in TWPhrases.txt — drop it from PROSE_KEEP", twPhrases.containsKey(k))
        }
    }

    @Test
    fun `the reported artefacts are the ones excluded`() {
        assertEquals("擴充套件", twPhrases["擴展"])
        assertEquals("許可權", twPhrases["權限"])
        assertEquals("型別", twPhrases["類型"])
        assertEquals("專案", twPhrases["項目"])
        listOf("擴展", "權限", "類型", "項目").forEach { assertTrue(it in OpenCcConverter.PROSE_KEEP) }
    }
}
