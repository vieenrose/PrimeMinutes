package studio.primeminutes.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.export.ExportNotes
import studio.primeminutes.core.export.PdfLinker
import studio.primeminutes.core.export.TranscriptExport
import studio.primeminutes.core.reader.Note

class ExportNotesTest {
    private val wj = "⁠"
    private val zhLabels = mapOf("DECISION" to "決議", "ACTION" to "待辦", "OPEN-ISSUE" to "未決", "NUMBER" to "數字", "PROPOSAL" to "提議")
    private val notes = listOf(
        Note(1, 0, "0:15", null, "討論新平站開業細節"),
        Note(2, 0, "1:16", "PROPOSAL", "建議初期推出低價套餐"),
        Note(3, 0, "23:15", "NUMBER", "積分兌換比例建議為十元一分"),
        Note(4, 0, "28:42", "OPEN", "化妝品進貨渠道需掌握。"),
        Note(5, 0, "29:00", "-", "-"),
    )
    private val sec = ExportNotes.section("AI 筆記", notes, { zhLabels[it] })!!
    private val utts = listOf(TranscriptEvent.Utterance(index = 0, text = "大家好", startSec = 15.0, endSec = 18.0, speaker = 0))
    private val ts = Regex("""\d+:\d{2}(?::\d{2})?""")

    @Test fun groupsByTypeUntypedFirstNoIndexNoRawTag() {
        assertEquals(listOf(null, "未決", "數字", "提議"), sec.groups.map { it.label })
        val txt = ExportNotes.plain(sec)
        assertEquals(
            "【AI 筆記】\n・討論新平站開業細節。${wj}[0:15]\n\n未決：\n・化妝品進貨渠道需掌握。${wj}[28:42]\n\n" +
                "數字：\n・積分兌換比例建議為十元一分。${wj}[23:15]\n\n提議：\n・建議初期推出低價套餐。${wj}[1:16]", txt)
        assertFalse(txt.contains("#1") || txt.contains("(PROPOSAL)") || txt.contains("(-)"))
    }

    @Test fun markdownUsesHeadingsAndCodeCites() {
        val md = ExportNotes.markdown(sec)
        assertTrue(md.startsWith("## AI 筆記\n\n- 討論新平站開業細節。${wj}`0:15`\n\n### 未決\n\n"))
        assertTrue(md.contains("### 提議\n\n- 建議初期推出低價套餐。${wj}`1:16`"))
    }

    @Test fun englishNotesPlainHeadingAndNbspGlue() {
        val en = ExportNotes.section("AI notes", listOf(Note(1, 0, "1:02", "DEC", "Ship on Monday")), { if (it == "DECISION") "Decision" else null })!!
        assertEquals("AI NOTES\nDecision:\n- Ship on Monday [1:02]", ExportNotes.plain(en))
    }

    @Test fun summaryCiteMovesAfterTheClausePunctuation() {
        val s = ExportNotes.summary("涵蓋多個方面 [0:15]。在營銷上 [1:16]，推出套餐 [1:49]。")
        assertEquals("涵蓋多個方面。${wj}[0:15] 在營銷上，推出套餐。${wj}[1:16]", s)
        assertFalse(s.contains("]["))
        assertFalse(s.contains(" ["))
        assertEquals(listOf("0:15", "1:16"), ts.findAll(s).map { it.value }.toList())
        assertEquals("a b. [0:18, 0:35]", ExportNotes.summary("a b [0:18, 0:35]."))
    }

    @Test fun longSummaryIsCutIntoParagraphsAtCiteBoundariesOnly() {
        // 12 sentences of 40 Han characters, one cite each: 480+ chars → paragraphs of ~250.
        val raw = (1..12).joinToString("") { "會".repeat(39) + "議 [${it}:00]。" }
        val ps = ExportNotes.paragraphs(raw)
        assertTrue(ps.size >= 2)
        ps.forEach { p ->
            assertTrue(p, p.endsWith("]"))                    // every cut right after a cite
            assertFalse(p.startsWith(" ") || p.endsWith(" "))
            assertTrue(p.length in 150..360)
        }
        assertEquals(12, ps.sumOf { ts.findAll(it).count() })
        assertEquals(ExportNotes.summary(raw), ps.joinToString("\n\n"))
        // The model's own blank-line breaks are kept; a short paragraph is never cut.
        assertEquals(listOf("甲。${wj}[0:01]", "乙。${wj}[0:02]"), ExportNotes.paragraphs("甲 [0:01]。\n\n乙 [0:02]。"))
        val en = (1..14).joinToString(" ") { "The team agreed on item number $it for the move [${it}:00]." }
        val eps = ExportNotes.paragraphs(en)
        assertTrue(eps.size >= 2)
        eps.forEach { assertTrue(it.endsWith("]")) }
    }

    @Test fun trivialNotesAreDropped() {
        val sec = ExportNotes.section("AI 筆記", listOf(
            Note(1, 0, "3:34", "ACTION", "3"), Note(2, 0, "3:40", "ACTION", "…"), Note(3, 0, "3:50", "ACTION", "寄出通知"),
            Note(4, 0, "4:00", null, " - "),
        ), { zhLabels[it] })!!
        assertEquals(listOf("寄出通知"), sec.groups.flatMap { g -> g.items.map { it.text } })
        assertEquals(null, ExportNotes.section("AI 筆記", listOf(Note(1, 0, "0:01", null, "42。")), { null }))
        assertTrue(studio.primeminutes.core.export.Cites.trivial("3"))
        assertFalse(studio.primeminutes.core.export.Cites.trivial("3 月"))
    }

    @Test fun actionItemsAreFormattedLikeTheNotes() {
        val acts = ExportNotes.actions("行動項目", "- 林雅婷寄出座位表 [2:34]\n- 3 [3:34]\n-\n請法務審閱合約")!!
        assertEquals("【行動項目】\n・林雅婷寄出座位表。${wj}[2:34]\n・請法務審閱合約", ExportNotes.plain(acts))
        assertEquals("## 行動項目\n\n- 林雅婷寄出座位表。${wj}[2:34](#t-15)\n- 請法務審閱合約", ExportNotes.markdown(acts, listOf(15.0)))
        assertEquals(null, ExportNotes.actions("行動項目", "-"))
    }

    @Test fun markdownMultiCiteIsOneLinkPerTimeToTheTurnAtOrBefore() {
        val starts = listOf(10.0, 60.5, 120.0)
        assertEquals("x。⁠[0:05](#t-10)、[1:10](#t-60)", ExportNotes.mdCites("x。⁠[0:05、1:10]", starts))
        assertEquals("y [2:00](#t-120), [9:59](#t-120)", ExportNotes.mdCites("y [2:00, 9:59]", starts))
    }

    // --- PdfLinker on a minimal classic-xref PDF --------------------------------------------------

    private fun minimalPdf(): ByteArray {
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offs = ArrayList<Int>()
        objs.forEachIndexed { i, o -> offs += sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        val x = sb.length
        sb.append("xref\n0 5\n0000000000 65535 f \n")
        offs.forEach { sb.append(String.format("%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$x\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test fun linkerAppendsAnIncrementalUpdate() {
        val src = minimalPdf()
        val out = PdfLinker.link(src, listOf(PdfLinker.Link(0, floatArrayOf(10f, 20f, 30f, 32f), 1, 800f)))
        val s = String(out, Charsets.ISO_8859_1)
        assertTrue(s.startsWith(String(src, Charsets.ISO_8859_1)))
        assertTrue(s.contains("5 0 obj\n<< /Type /Annot /Subtype /Link /Rect [10.00 20.00 30.00 32.00] /Border [0 0 0] /Dest [4 0 R /XYZ null 800.00 null] >>"))
        assertTrue(s.contains("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]  /Annots [5 0 R] >>"))
        // xref offsets point at the objects.
        val xref = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(s)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("xref", xref))
        Regex("""(\d+) 1\n(\d{10}) \d{5} n \n""").findAll(s.substring(xref)).forEach {
            assertTrue(s.startsWith("${it.groupValues[1]} 0 obj", it.groupValues[2].toInt()))
        }
        assertTrue(s.contains("/Size 6 /Root 1 0 R /Prev ${String(src, Charsets.ISO_8859_1).indexOf("xref")}"))
    }

    @Test fun linkerLeavesAnUnparsablePdfUntouched() {
        val junk = "not a pdf".toByteArray()
        assertTrue(PdfLinker.link(junk, listOf(PdfLinker.Link(0, floatArrayOf(0f, 0f, 1f, 1f), 0, 1f))).contentEquals(junk))
    }
}
