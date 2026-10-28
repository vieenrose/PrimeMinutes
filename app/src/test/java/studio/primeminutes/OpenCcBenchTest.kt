package studio.primeminutes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File

/**
 * The zh-TW <-> zh-CN bench (meeting sentences with one-to-many characters, vocabulary sentences and the
 * exception cases), shared with iOS: ios/tests/golden/opencc_bench.json. Also checks span gating (text
 * already in the target script is untouched) and the lexicon policy (no TWPhrases over-localisation).
 */
@RunWith(RobolectricTestRunner::class) // android.util.Log only
class OpenCcBenchTest {
    private val root = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
    private val converters by lazy { OpenCcConverter.forTest { File(root, it).inputStream() } }
    private val transcriptSimplified by lazy { OpenCcConverter.transcriptSimplifiedForTest { File(root, it).inputStream() } }

    private fun conv(name: String) = when (name) {
        "generated-traditional" -> converters.first
        "transcript-traditional" -> converters.second
        "generated-simplified" -> converters.third
        "transcript-simplified" -> transcriptSimplified
        else -> error(name)
    }

    @Test fun `bench cases`() {
        val cases = org.json.JSONObject(javaClass.getResourceAsStream("/opencc_bench.json")!!.readBytes().toString(Charsets.UTF_8))
            .getJSONArray("cases")
        val fails = (0 until cases.length()).map { cases.getJSONArray(it) }.mapNotNull { c ->
            val got = conv(c.getString(0)).convert(c.getString(1))
            if (got == c.getString(2)) null else "${c.getString(0)} ${c.getString(1)} -> $got, want ${c.getString(2)}"
        }
        assertEquals(fails.joinToString("\n"), 0, fails.size)
    }

    @Test fun `target-script text passes through untouched`() {
        val trad = "我們討論了解決方案，他說明了原因。我們不應該干涉他們；皇后出席了典禮！距離只有五公里 文件 權限 數據 項目"
        assertEquals(trad, converters.first.convert(trad))
        assertEquals(trad, converters.second.convert(trad))
        val simp = "我们讨论了解决方案，乾坤未定。这个文件的权限"
        assertEquals(simp, converters.third.convert(simp))
        assertEquals(simp, transcriptSimplified.convert(simp))
    }

    @Test fun `lexicon does not over-localise`() {
        val keys = File(root, OpenCcConverter.LEXICON).readLines()
            .filter { it.isNotBlank() && !it.startsWith('#') }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        val dropped = listOf("文件", "權限", "優化", "數據", "項目", "發佈", "發布", "性能", "擴展", "類型", "對象", "用戶", "支持", "代碼", "打開")
        for ((k, v) in keys) assertFalse("$k → $v over-localises", k in dropped && k != v)
        val m = keys.toMap()
        for ((k, v) in mapOf("軟件" to "軟體", "視頻" to "影片", "信息" to "資訊", "質量" to "品質", "服務器" to "伺服器",
            "屏幕" to "螢幕", "文件夾" to "資料夾")) assertEquals(k, v, m[k])
    }
}
