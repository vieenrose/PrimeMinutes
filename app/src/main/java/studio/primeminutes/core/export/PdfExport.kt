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
    ) {
        val titlePaint = paint(18f, bold = true, locale)
        val headingPaint = paint(13f, bold = true, locale)
        val bodyPaint = paint(10f, locale = locale)

        var hang = 0f
        val doc = PdfDocument()
        val pager = Pager(doc)
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { pager.block(it, titlePaint); pager.gap(10f) }
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            pager.block(summaryHeading, headingPaint); pager.gap(4f)
            pager.block(it, bodyPaint); pager.gap(14f)
        }
        // "-" is the extractor's own "nothing found" marker, not an action item.
        actionItems?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let {
            // No heading given = the block carries its own (as in the text exports): an English
            // "Action items" stood above the localized one in a Chinese PDF.
            actionsHeading?.let { h -> pager.block(h, headingPaint); pager.gap(4f) }
            pager.block(it, bodyPaint); pager.gap(14f)
        }
        pager.block(transcriptHeading, headingPaint); pager.gap(4f)
        // Speaker turns as in the txt/md exports (TranscriptExport.turns: newlines flattened, blank
        // lines dropped): the clock sits in a grey left gutter, the body hangs under its text.
        val clockPaint = paint(10f, locale = locale).apply { color = GUTTER_GREY }
        val pad = TranscriptExport.gutterWidth(utterances)
        val gutter = clockPaint.measureText("0".repeat(pad)) + 10f
        var first = true
        for ((u, text, cont) in TranscriptExport.turns(utterances)) {
            if (cont) {
                pager.turn(null, "", text, bodyPaint, clockPaint, gutter, hang)
            } else {
                if (!first) pager.gap(6f)
                val who = u.speaker?.let { "${label(it)}: " } ?: ""
                hang = bodyPaint.measureText(who)
                pager.turn(TranscriptExport.clock(u.startSec, pad), who, text, bodyPaint, clockPaint, gutter, hang)
            }
            first = false
            pager.gap(3f)
        }
        pager.finish()
        doc.writeTo(out)
        doc.close()
    }

    private fun paint(size: Float, bold: Boolean = false, locale: Locale = Locale.getDefault()) = TextPaint().apply {
        textLocale = locale
        isAntiAlias = true
        color = Color.BLACK
        textSize = size
        isFakeBoldText = bold
    }

    /** Lays out text and emits it line-by-line, starting a new page whenever the next line won't fit. */
    private class Pager(private val doc: PdfDocument) {
        private var num = 1
        private var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, num).create())
        private var canvas = page.canvas
        private var y = M.toFloat()

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

        /**
         * One transcript line: [clock] (null on a continuation) in the gutter, then `who + text` from
         * the gutter edge, wrapped lines hanging-indented by [hang] (the speaker label's width).
         */
        fun turn(clock: String?, who: String, text: String, paint: TextPaint, clockPaint: TextPaint, gutter: Float, hang: Float) {
            val body = SpannableString(who + text)
            val firstHangs = clock == null
            body.setSpan(LeadingMarginSpan.Standard(if (firstHangs) hang.toInt() else 0, hang.toInt()), 0, body.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val left = M + gutter
            val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, (CW - gutter).toInt()).build()
            for (i in 0 until layout.lineCount) {
                val lineH = (layout.getLineTop(i + 1) - layout.getLineTop(i)).toFloat()
                if (y + lineH > PH - M) newPage()
                val baseline = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                if (i == 0 && clock != null) canvas.drawText(clock, M.toFloat(), baseline, clockPaint)
                val x = left + if (i > 0 || firstHangs) hang else 0f
                canvas.drawText(body, layout.getLineStart(i), layout.getLineEnd(i), x, baseline, paint)
                y += lineH
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
