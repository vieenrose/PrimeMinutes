package studio.primeminutes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File

/**
 * STPhrases' 了解→瞭解 (longest match) used to swallow the particle 了 plus the next word:
 * 討論了解決方案 → 討論瞭解決方案. The app's converters over the bundled dictionary files.
 */
@RunWith(RobolectricTestRunner::class) // android.util.Log only
class OpenCcLiaojieTest {
    private val converters by lazy {
        val root = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
        OpenCcConverter.forTest { File(root, it).inputStream() }
    }
    private val generated get() = converters.first
    private val transcript get() = converters.second
    private val toSimplified get() = converters.third

    private val cases = mapOf(
        "会议讨论了解决方案" to "會議討論了解決方案",
        "说明了解约流程" to "說明了解約流程",
        "會議討論了解決方案" to "會議討論了解決方案",
        "說明了解約流程" to "說明了解約流程",
        "明瞭" to "明瞭",
        "一目瞭然" to "一目瞭然",
        "这很明了" to "這很明瞭",
        "这一目了然" to "這一目瞭然",
        // Span gating: no character the S→T table changes, so already valid Traditional and left alone.
        "明了" to "明了",
        "一目了然" to "一目瞭然",   // ...unless it holds an STForce word
        "我们需要了解客户" to "我們需要了解客戶",
        "据了解" to "據了解",
        "讨论了如何推进" to "討論了如何推進",
        "做完了然后再说" to "做完了然後再說",
        "了如指掌" to "瞭如指掌",   // STForce
        "他对这了如指掌" to "他對這瞭如指掌",
        "这证明了解法可行" to "這證明了解法可行",
    )

    @Test fun `s2twp keeps the particle le`() = cases.forEach { (i, o) -> assertEquals(i, o, generated.convert(i)) }

    @Test fun `transcript s2tw keeps the particle le`() = cases.forEach { (i, o) -> assertEquals(i, o, transcript.convert(i)) }

    @Test fun `t2s is unaffected`() {
        assertEquals("会议讨论了解决方案", toSimplified.convert("會議討論了解決方案"))
        assertEquals("说明了解约流程", toSimplified.convert("說明了解約流程"))
        assertEquals("我们需要了解客户", toSimplified.convert("我們需要瞭解客戶"))
        assertEquals("这一目了然", toSimplified.convert("這一目瞭然"))
        assertEquals("一目了然", toSimplified.convert("一目瞭然"))   // TSForce
    }

}
