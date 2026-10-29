package studio.primeminutes.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.export.DocxExport
import studio.primeminutes.core.export.ExportNotes
import studio.primeminutes.core.export.ExportScript
import studio.primeminutes.core.export.PdfExport
import studio.primeminutes.core.text.OpenCcConverter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** A zh-Hant meeting shared with the 简体 setting comes out Simplified, links intact; 繁體 leaves it. */
@RunWith(RobolectricTestRunner::class) // android.util.Log only
class ExportScriptTest {
    private val root = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
    private val open: (String) -> java.io.InputStream = { File(root, it).inputStream() }
    private val conv by lazy { OpenCcConverter.forTest(open) }
    private val trSimp by lazy { OpenCcConverter.transcriptSimplifiedForTest(open) }

    private val utts = listOf(
        TranscriptEvent.Utterance(0, "我們開會討論這個問題。", 0.0, 9.0, 0),
        TranscriptEvent.Utterance(1, "請發送資料給團隊。", 10.0, 19.0, 1),
        TranscriptEvent.Utterance(2, "Let's ship it.", 90.0, 99.0, 0),
    )
    private val names = mapOf(0 to "陳經理", 1 to "劉專員")
    private val doc = PdfExport.Doc(utts, { names.getValue(it) }, "週會紀錄", listOf("說話者：陳經理、劉專員"),
        "會議討論了資料傳送 [0:10]。後續見 [1:30]。", "摘要", "逐字稿",
        actions = ExportNotes.actions("待辦事項", "- 劉專員寄出報告 [0:10]"), backLabel = "↑ 摘要")

    private fun docXml(d: PdfExport.Doc, zh: String): String {
        val out = ByteArrayOutputStream(); DocxExport.write(out, d, zh)
        System.getenv("EXPORT_SCRIPT_OUT")?.let { File(it, "export-$zh.docx").writeBytes(out.toByteArray()) }
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == "word/document.xml") return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no document.xml")
    }

    private fun links(xml: String) = Regex("""w:anchor="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
    private fun marks(xml: String) = Regex("""w:bookmarkStart w:id="\d+" w:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()

    @Test fun simplifiedSettingConvertsEveryField() {
        val d = ExportScript.apply(doc, conv.third::convert, trSimp::convert)
        assertEquals("周会纪录", d.title)
        assertEquals("会议讨论了资料传送 [0:10]。后续见 [1:30]。", d.summary)
        assertEquals("陈经理", d.label(0)); assertEquals("刘专员", d.label(1))
        assertEquals("我们开会讨论这个问题。", d.utterances[0].text)
        assertEquals("Let's ship it.", d.utterances[2].text)   // English unchanged
        assertEquals("刘专员寄出报告", d.actions!!.groups[0].items[0].text)
        assertEquals("说话者：陈经理、刘专员", d.meta[0])
        val xml = docXml(d, "zh-CN")
        assertFalse(xml.contains("會議")); assertTrue(xml.contains("会议"))
        // Same links as the unconverted document, and every one lands on a bookmark.
        val before = docXml(doc, "zh-TW")
        assertEquals(links(before), links(xml))
        assertTrue(links(xml).isNotEmpty())
        assertTrue(marks(xml).containsAll(links(xml)))
    }

    @Test fun traditionalSettingLeavesItAsIs() {
        val d = ExportScript.apply(doc, conv.first::convert, conv.second::convert)
        assertEquals(doc.title, d.title); assertEquals(doc.summary, d.summary); assertEquals(doc.meta, d.meta)
        assertEquals(doc.utterances.map { it.text }, d.utterances.map { it.text })
        assertEquals("陳經理", d.label(0))
        assertEquals(docXml(doc, "zh-TW"), docXml(d, "zh-TW"))
    }
}
