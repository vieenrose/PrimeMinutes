package studio.primeminutes.core.export

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.LeadingMarginSpan
import android.text.TextPaint
import studio.primeminutes.core.events.TranscriptEvent
import java.io.OutputStream
import java.util.Locale

/**
 * Render a transcript + summary to a paginated PDF using the platform's [PdfDocument] — no third-party
 * dependency, and Traditional Chinese / CJK renders via the system Noto fonts. A4 at 72 dpi with
 * line-by-line pagination so any length flows across pages. Pure: caller supplies the localised
 * headings + speaker label, and an [OutputStream] (a SAF document).
 */
object PdfExport {

    private const val PW = 595   // A4 width  @ 72 dpi
    private const val PH = 842   // A4 height @ 72 dpi
    private const val M = 40     // margin
    private const val CW = PW - 2 * M

    fun write(
        out: OutputStream,
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
        summary: String?,
        summaryHeading: String,
        transcriptHeading: String,
        actionItems: String? = null,
        actionsHeading: String? = null,
        /** The interface language: picks the Han glyph variants (zh-CN and zh-TW draw some characters differently). */
        locale: Locale = Locale.getDefault(),
        /** The AI notes, grouped by type (ExportNotes.section). */
        notes: ExportNotes.Section? = null,
        /** The action items, formatted like the notes (ExportNotes.actions). */
        actions: ExportNotes.Section? = null,
    ) {
        val titlePaint = paint(18f, bold = true, locale)
        val headingPaint = paint(13f, bold = true, locale)
        val labelPaint = paint(10.5f, bold = true, locale)
        val bodyPaint = paint(10f, locale = locale)
        val greyPaint = paint(10f, locale = locale).apply { color = GUTTER_GREY }

        val doc = PdfDocument()
        val pager = Pager(doc)
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { pager.block(it, titlePaint); pager.gap(12f) }
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let { sm ->
            pager.block(summaryHeading, headingPaint); pager.gap(6f)
            ExportNotes.paragraphs(sm).flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }.forEach { p ->
                pager.para(p, bodyPaint, greyPaint, 0f, 0f, 0f, null, cites = true); pager.gap(8f)
            }
            pager.gap(10f)
        }
        // "-" is the extractor's own "nothing found" marker, not an action item.
        actionItems?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let {
            actionsHeading?.let { h -> pager.block(h, headingPaint); pager.gap(4f) }
            pager.block(it, bodyPaint); pager.gap(16f)
        }
        for (sec in listOfNotNull(actions, notes)) {
            pager.block(sec.heading, headingPaint); pager.gap(6f)
            val bullet = "•"
            val indent = bodyPaint.measureText(bullet) + 6f
            for (g in sec.groups) {
                g.label?.let { pager.gap(2f); pager.block(it, labelPaint); pager.gap(3f) }
                for (i in g.items) {
                    pager.para(ExportNotes.line(i), bodyPaint, greyPaint, indent, 0f, 0f, bullet, cites = true)
                    pager.gap(3f)
                }
                pager.gap(5f)
            }
            pager.gap(10f)
        }
        pager.block(transcriptHeading, headingPaint); pager.gap(6f)
        // Speaker turns as in the txt/md exports (TranscriptExport.turns: newlines flattened, blank
        // lines dropped): the clock sits in a grey left gutter, the body hangs under its text.
        val pad = TranscriptExport.gutterWidth(utterances)
        val gutter = greyPaint.measureText("0".repeat(pad)) + 10f
        var hang = 0f
        var first = true
        for ((u, text, cont) in TranscriptExport.turns(utterances)) {
            if (cont) {
                pager.para(text, bodyPaint, greyPaint, gutter, hang, hang, null, cites = false)
            } else {
                if (!first) pager.gap(6f)
                val who = u.speaker?.let { "${label(it)}: " } ?: ""
                hang = bodyPaint.measureText(who)
                pager.target(u.startSec)
                pager.para(who + text, bodyPaint, greyPaint, gutter, 0f, hang, TranscriptExport.clock(u.startSec, pad), cites = false)
            }
            first = false
            pager.gap(3f)
        }
        pager.finish()
        val bytes = java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        doc.close()
        out.write(PdfLinker.link(bytes, pager.links()))
    }

    private fun paint(size: Float, bold: Boolean = false, locale: Locale = Locale.getDefault()) = TextPaint().apply {
        textLocale = locale
        isAntiAlias = true
        color = Color.BLACK
        textSize = size
        isFakeBoldText = bold
    }

    private val CITE = Regex("""\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)]""")
    private val TIME = Regex("""\d+:\d{2}(?::\d{2})?""")

    /** Lays out text and emits it line-by-line, starting a new page whenever the next line won't fit. */
    private class Pager(private val doc: PdfDocument) {
        private var num = 1
        private var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, num).create())
        private var canvas = page.canvas
        private var y = M.toFloat()

        private class Cite(val page: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val sec: Int)
        private class Target(val sec: Double, val page: Int, val y: Float)
        private val citeRects = ArrayList<Cite>()
        private val targets = ArrayList<Target>()

        fun gap(h: Float) { y += h; if (y > PH - M) newPage() }

        fun block(text: CharSequence, paint: TextPaint) {
            // Split on hard newlines (e.g. summary bullets) so each is its own soft-wrapped paragraph.
            for (para in text.split('\n')) {
                val layout = StaticLayout.Builder.obtain(para, 0, para.length, paint, CW).build()
                for (i in 0 until layout.lineCount) {
                    val lineH = (layout.getLineTop(i + 1) - layout.getLineTop(i)).toFloat()
                    if (y + lineH > PH - M) newPage()
                    val baseline = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                    canvas.drawText(para, layout.getLineStart(i), layout.getLineEnd(i), M.toFloat(), baseline, paint)
                    y += lineH
                }
            }
        }

        /** A transcript turn starts here: the destination of the cites that point at [sec]. */
        fun target(sec: Double) {
            if (y + 14f > PH - M) newPage()
            targets += Target(sec, num - 1, y)
        }

        /**
         * One paragraph from [left] (past the margin), its first line indented by [first], the rest by
         * [rest] (a hanging indent). [gutter] is drawn grey at the margin on the first line (the clock,
         * or a note's bullet at [left] − its width). With [cites], every `[m:ss]` is drawn grey and
         * each time in it becomes a link to its transcript turn.
         */
        fun para(text: String, paint: TextPaint, grey: TextPaint, left: Float, first: Float, rest: Float, gutter: String?, cites: Boolean) {
            val body = SpannableString(text)
            body.setSpan(LeadingMarginSpan.Standard(first.toInt(), rest.toInt()), 0, body.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val x0 = M + left
            val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, (CW - left).toInt())
                .setLineSpacing(if (cites) 2.5f else 1f, 1f).build()
            val ranges = if (cites) CITE.findAll(text).map { it.range }.toList() else emptyList()
            for (i in 0 until layout.lineCount) {
                val lineH = (layout.getLineTop(i + 1) - layout.getLineTop(i)).toFloat()
                if (y + lineH > PH - M) newPage()
                val baseline = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                if (i == 0 && gutter != null) {
                    val gx = if (left > 0f && gutter.length <= 2) M + left - grey.measureText(gutter) - 6f else M.toFloat()
                    canvas.drawText(gutter, gx, baseline, grey)
                }
                val x = x0 + if (i == 0) first else rest
                val ls = layout.getLineStart(i); val le = layout.getLineEnd(i)
                // Runs: plain text black, cite characters grey; cite times recorded as link rects.
                var at = ls
                var cx = x
                while (at < le) {
                    val r = ranges.firstOrNull { at <= it.last && it.first < le && it.last >= at }
                    val runEnd = if (r == null) le else if (r.first > at) r.first else minOf(r.last + 1, le)
                    val inCite = r != null && r.first <= at
                    val p = if (inCite) grey else paint
                    val w = p.measureText(text, at, runEnd)
                    canvas.drawText(text, at, runEnd, cx, baseline, p)
                    if (inCite) for (t in TIME.findAll(text.substring(at, runEnd))) {
                        val a = at + t.range.first; val b = at + t.range.last + 1
                        val tx1 = cx + p.measureText(text, at, a) - 1f
                        val tx2 = cx + p.measureText(text, at, b) + 1f
                        citeRects += Cite(num - 1, tx1, y, tx2, y + lineH, ExportNotes.seconds(t.value))
                    }
                    cx += w
                    at = runEnd
                }
                y += lineH
            }
        }

        fun links(): List<PdfLinker.Link> {
            val starts = targets.map { it.sec }
            return citeRects.mapNotNull { c ->
                val t = ExportNotes.targetFor(c.sec, starts)?.let { targets[it] } ?: return@mapNotNull null
                PdfLinker.Link(c.page, floatArrayOf(c.x1, PH - c.y2, c.x2, PH - c.y1), t.page, PH - t.y + 4f)
            }
        }

        fun finish() = doc.finishPage(page)

        private fun newPage() {
            doc.finishPage(page)
            num++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, num).create())
            canvas = page.canvas
            y = M.toFloat()
        }
    }

    /** Timestamp gutter colour (the mail's #5A6982 grey). */
    private val GUTTER_GREY = Color.rgb(0x5A, 0x69, 0x82)
}
