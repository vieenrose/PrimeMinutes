package studio.primeminutes.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.export.DocxExport
import studio.primeminutes.core.export.ExportNotes
import studio.primeminutes.core.export.PdfExport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxExportTest {
    private val utts = (0 until 40).map { i ->
        TranscriptEvent.Utterance(index = i, text = "第 $i 句 <a&b>", startSec = i * 10.0, endSec = i * 10.0 + 9, speaker = i % 2)
    }
    private val acts = ExportNotes.actions("待辦事項", "- 林雅婷寄出座位表 [2:34]")
    private val doc = PdfExport.Doc(utts, { "S${it + 1}" }, "會議", listOf("2026-10-29 · 7 分鐘"),
        "涵蓋多個方面 [0:15]。另見 [5:00, 1:30]。", "摘要", "逐字稿", actions = acts, backLabel = "↑ 摘要")

    private fun parts(): Map<String, String> {
        val out = ByteArrayOutputStream(); DocxExport.write(out, doc, "zh-TW")
        val m = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return m
    }

    @Test fun zipHasTheWordprocessingParts() {
        val p = parts()
        assertEquals(listOf("[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels", "word/styles.xml", "word/settings.xml", "word/document.xml"), p.keys.toList())
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((_, xml) in p) f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))   // well-formed
        assertTrue(p["word/styles.xml"]!!.contains("w:eastAsia=\"zh-TW\""))
        assertTrue(p["word/styles.xml"]!!.contains("w:styleId=\"Speaker\""))
        assertTrue(p["word/document.xml"]!!.contains("&lt;a&amp;b&gt;"))
    }

    @Test fun citesAnchorToTurnBookmarksAndTurnsLinkBack() {
        val x = parts()["word/document.xml"]!!
        val anchors = Regex("""w:anchor="([^"]+)"""").findAll(x).map { it.groupValues[1] }.toList()
        val marks = Regex("""w:bookmarkStart w:id="\d+" w:name="([^"]+)"""").findAll(x).map { it.groupValues[1] }.toSet()
        assertTrue("every link has its bookmark", marks.containsAll(anchors))
        // 0:15 → turn 1, 5:00 → turn 30, 1:30 → turn 9, 2:34 → turn 15.
        assertEquals(listOf("t1", "t30", "t9", "t15"), anchors.filter { it.startsWith("t") })
        assertEquals(4, anchors.count { it.startsWith("c") })
        assertEquals(4, Regex("↑ 摘要").findAll(x).count())
        assertFalse("no AI notes heading", x.contains("AI"))
        val ids = Regex("""w:bookmarkStart w:id="(\d+)"""").findAll(x).map { it.groupValues[1] }.toList()
        assertEquals(ids.size, ids.toSet().size)
    }
}
