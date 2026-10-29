package studio.primeminutes

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.export.PdfExport
import java.io.ByteArrayOutputStream

/**
 * PdfExport uses the platform [android.graphics.pdf.PdfDocument], so it can only run on a device.
 * Asserts a non-trivial, well-formed PDF (the %PDF- header + %%EOF trailer) — incl. enough content
 * to force a second page, so the pagination path is exercised.
 */
@RunWith(AndroidJUnit4::class)
class PdfExportTest {

    @Test fun producesValidMultiPagePdf() {
        val utts = (0 until 120).map { i ->
            TranscriptEvent.Utterance(
                index = i, text = "Line $i — 這是一段中英混合的逐字稿內容，用來測試換頁與 CJK 渲染。",
                startSec = i.toDouble(), endSec = i + 1.0, speaker = i % 2,
            )
        }
        val bos = ByteArrayOutputStream()
        PdfExport.write(bos, PdfExport.Doc(utts, { "Speaker ${it + 1}" }, "My Meeting", listOf("29 Oct 2026 · 2 min"), "- a summary point\n- another", "Summary", "Transcript"))
        val bytes = bos.toByteArray()

        assertTrue("PDF should be sizeable, was ${bytes.size}", bytes.size > 1000)
        assertTrue("starts with %PDF- header", String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-")
        val tail = String(bytes, maxOf(0, bytes.size - 1024), minOf(1024, bytes.size), Charsets.US_ASCII)
        assertTrue("ends with %%EOF", tail.contains("%%EOF"))
    }

    @Test fun summaryAndNoteCitesLinkToTheirTranscriptTurns() {
        val utts = (0 until 120).map { i ->
            TranscriptEvent.Utterance(index = i, text = "第 $i 句，這是一段逐字稿內容，用來測試換頁。", startSec = i * 10.0, endSec = i * 10.0 + 9, speaker = i % 2)
        }
        val notes = studio.primeminutes.core.export.ExportNotes.section("AI 筆記", listOf(
            studio.primeminutes.core.reader.Note(1, 0, "0:15", "PROPOSAL", "建議推出套餐"),
            studio.primeminutes.core.reader.Note(2, 0, "19:00", null, "討論細節"),
        ), { if (it == "PROPOSAL") "提議" else null })
        val bos = ByteArrayOutputStream()
        PdfExport.write(bos, PdfExport.Doc(utts, { "S${it + 1}" }, "會議", emptyList(), "涵蓋多個方面 [0:15]。另見 [5:00, 15:30]。", "摘要", "逐字稿", notes = notes, backLabel = "↑ 摘要"))
        val s = String(bos.toByteArray(), Charsets.ISO_8859_1)
        // Summary: 2 cited sentences + 3 times; notes: 2 items + 2 times; 4 cited turns → 4 back links.
        val links = Regex("""/Subtype /Link""").findAll(s).count()
        assertTrue("links: $links", links == 13)
        assertTrue(Regex("""/Dest \[\d+ 0 R /XYZ null [\d.]+ null]""").findAll(s).count() == links)
        assertTrue(s.contains("/Annots ["))
        assertTrue(s.trimEnd().endsWith("%%EOF"))
    }
}
