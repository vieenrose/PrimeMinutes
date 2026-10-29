package studio.primeminutes.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import studio.primeminutes.core.text.ScreenScript
import java.io.File

/** People / Ask / channel page text under 简体 with Traditional stored: converted for display, memoized, edits as typed. */
@RunWith(RobolectricTestRunner::class) // android.util.Log only
class ScreenScriptTest {
    private val root = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
    private val open: (String) -> java.io.InputStream = { File(root, it).inputStream() }
    private val conv by lazy { OpenCcConverter.forTest(open) }
    private val trSimp by lazy { OpenCcConverter.transcriptSimplifiedForTest(open) }
    private val simp by lazy { ScreenScript(ChineseScript.SIMPLIFIED, conv.third::convert, trSimp::convert) }

    @Test fun simplifiedConvertsTitlesAnswersAndExcerpts() {
        assertEquals("周会纪录", simp.gen("週會紀錄"))                                  // meeting / episode title
        assertEquals("会议讨论了资料传送[[e1@10]]。", simp.gen("會議討論了資料傳送[[e1@10]]。"))   // Ask answer, cite tag intact
        assertEquals("我们开会讨论这个问题。", simp.tr("我們開會討論這個問題。"))              // Ask excerpt
        assertEquals("Let's ship it.", simp.gen("Let's ship it."))
    }

    @Test fun memoConvertsEachTextOnce() {
        var calls = 0
        val m = ScreenScript.Memo({ calls++; it.uppercase() })
        repeat(3) { assertEquals("會A", m("會a")) }
        assertEquals(1, calls); assertEquals(1, m.misses)
        assertSame("ascii", m("ascii")); assertEquals(1, calls)   // ASCII skips the converter
    }

    @Test fun memoIsBounded() {
        val m = ScreenScript.Memo({ it }, cap = 2)
        m("一"); m("二"); m("三"); m("一")
        assertEquals(4, m.misses)   // cleared past the cap, then re-converted
    }

    @Test fun editsSaveAsTyped() {
        assertNull(ScreenScript.typed("周会纪录", shown = "周会纪录"))           // unchanged: keep the stored 週會紀錄
        assertEquals("周会记录", ScreenScript.typed("周会记录", shown = "周会纪录"))
    }
}
