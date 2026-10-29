package studio.primeminutes.core.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import studio.primeminutes.core.events.TranscriptEvent
import java.io.OutputStream
import java.util.Locale

/**
 * The meeting as one PDF (the Share sheet's "summary + transcript"), drawn with the platform's
 * [PdfDocument]: no third-party dependency; Skia embeds the subset of every font it draws with, so
 * the system Noto CJK glyphs travel inside the file. A4 at 72 dpi, line-by-line pagination, a page
 * number in every footer.
 *
 * Layout: a title block (title, then the meta lines: date · duration, speakers), the summary, the
 * action items (no AI notes: they stay in the .pmin), then the transcript as speaker turns (grey clock gutter, hanging
 * indent). Every cited sentence of the summary / action items is an internal link to the
 * transcript turn its first `[m:ss]` points at (a multi-time cite: each time links to its own turn);
 * a cited turn carries a "↑ back" link to the first sentence that cites it. Links are annotations
 * added afterwards by [PdfLinker] (PdfDocument has no link API).
 */
object PdfExport {

    private const val PW = 595   // A4 width  @ 72 dpi
    private const val PH = 842   // A4 height @ 72 dpi
    private const val MX = 54    // side margin
    private const val MT = 54    // top margin
    private const val MB = 58    // bottom margin (the footer sits inside it)
    private const val CW = PW - 2 * MX

    /** What the PDF shows; texts already localized, speaker refs already resolved. */
    data class Doc(
        val utterances: List<TranscriptEvent.Utterance>,
        val label: (Int) -> String,
        val title: String?,
        /** Under the title, one grey line each (e.g. "29 Oct 2026 · 42 min", "Speakers: Ann, Bo"). */
        val meta: List<String> = emptyList(),
        val summary: String?,
        val summaryHeading: String,
        val transcriptHeading: String,
        /** The action items, formatted like the notes (ExportNotes.actions). */
        val actions: ExportNotes.Section? = null,
        /** The back-link drawn on a cited transcript turn ("↑ Summary"). */
        val backLabel: String = "↑",
        /** The interface language: picks the Han glyph variants (zh-CN and zh-TW draw some characters differently). */
        val locale: Locale = Locale.getDefault(),
    )

    fun write(out: OutputStream, d: Doc) {
        val loc = d.locale
        val titlePaint = paint(20f, bold = true, loc).apply { color = INK }
        val metaPaint = paint(9.5f, locale = loc).apply { color = GREY }
        val headingPaint = paint(13.5f, bold = true, loc).apply { color = ACCENT }
        val labelPaint = paint(10.5f, bold = true, loc).apply { color = INK }
        val bodyPaint = paint(10.5f, locale = loc).apply { color = INK }
        val greyPaint = paint(10.5f, locale = loc).apply { color = GREY }
        val linkPaint = paint(10.5f, locale = loc).apply { color = LINK }
        val backPaint = paint(8.5f, locale = loc).apply { color = LINK }
        val footPaint = paint(8.5f, locale = loc).apply { color = GREY }

        val turns = TranscriptExport.turns(d.utterances)
        val starts = turns.filter { !it.cont }.map { it.u.startSec }

        val doc = PdfDocument()
        val pager = Pager(doc, footPaint, starts)

        // --- title block ---
        d.title?.trim()?.takeIf { it.isNotEmpty() }?.let { pager.block(it, titlePaint); pager.gap(6f) }
        d.meta.filter { it.isNotBlank() }.forEach { pager.block(it, metaPaint); pager.gap(2f) }
        pager.gap(8f); pager.rule(); pager.gap(14f)

        fun heading(h: String) {
            pager.keep(48f)
            pager.block(h, headingPaint); pager.gap(7f)
        }

        d.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { sm ->
            heading(d.summaryHeading)
            ExportNotes.paragraphs(sm).flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }.forEach { p ->
                pager.para(p, bodyPaint, linkPaint, 0f, 0f, 0f, null, cites = true); pager.gap(7f)
            }
            pager.gap(12f)
        }
        fun section(sec: ExportNotes.Section) {
            heading(sec.heading)
            val bullet = "•"
            val indent = bodyPaint.measureText(bullet) + 8f
            for (g in sec.groups) {
                g.label?.let { pager.gap(2f); pager.block(it, labelPaint); pager.gap(3f) }
                for (i in g.items) {
                    pager.para(ExportNotes.line(i), bodyPaint, linkPaint, indent, 0f, 0f, bullet, cites = true)
                    pager.gap(4f)
                }
                pager.gap(5f)
            }
            pager.gap(12f)
        }
        d.actions?.let { section(it) }

        // --- transcript: speaker turns, the clock in a grey left gutter, the body hanging under its text ---
        heading(d.transcriptHeading)
        val pad = TranscriptExport.gutterWidth(d.utterances)
        val gutter = greyPaint.measureText("0".repeat(pad)) + 12f
        var hang = 0f
        var first = true
        var turn = -1
        for ((u, text, cont) in turns) {
            if (cont) {
                pager.para(text, bodyPaint, greyPaint, gutter, hang, hang, null, cites = false)
            } else {
                turn++
                if (!first) pager.gap(7f)
                val who = u.speaker?.let { "${d.label(it)}: " } ?: ""
                hang = labelPaint.measureText(who)
                pager.target(turn)
                val back = if (pager.isCited(turn)) d.backLabel else null
                pager.para(who + text, bodyPaint, greyPaint, gutter, 0f, hang, TranscriptExport.clock(u.startSec, pad), cites = false,
                    labelOf = labelPaint, labelEnd = who.length, back = back, backPaint = backPaint, turn = turn)
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
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    private val CITE = Regex("""\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)]""")
    private val TIME = Regex("""\d+:\d{2}(?::\d{2})?""")
    /** Lays out text and emits it line-by-line, starting a new page whenever the next line won't fit. */
    private class Pager(private val doc: PdfDocument, private val foot: TextPaint, private val turnStarts: List<Double>) {
        private var num = 1
        private var page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, num).create())
        private var canvas = page.canvas
        private var y = MT.toFloat()

        private class Rect(val page: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float)
        private class Spot(val page: Int, val y: Float)
        private val forward = ArrayList<Pair<Rect, Int>>()        // link → turn index
        private val backward = ArrayList<Pair<Rect, Spot>>()     // link → where the turn is first cited
        private val targets = HashMap<Int, Spot>()
        private val firstCite = HashMap<Int, Spot>()

        private val bottom get() = (PH - MB).toFloat()

        fun gap(h: Float) { y += h; if (y > bottom) newPage() }

        /** Keeps [h] points together (a heading never ends a page alone). */
        fun keep(h: Float) { if (y + h > bottom) newPage() }

        fun rule() {
            val p = Paint().apply { color = HAIRLINE; strokeWidth = 0.75f }
            canvas.drawLine(MX.toFloat(), y, (PW - MX).toFloat(), y, p)
        }

        fun block(text: CharSequence, paint: TextPaint) {
            // Split on hard newlines (e.g. summary bullets) so each is its own soft-wrapped paragraph.
            for (para in text.split('\n')) {
                val layout = StaticLayout.Builder.obtain(para, 0, para.length, paint, CW).build()
                for (i in 0 until layout.lineCount) {
                    val lineH = (layout.getLineTop(i + 1) - layout.getLineTop(i)).toFloat()
                    if (y + lineH > bottom) newPage()
                    val baseline = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                    canvas.drawText(para, layout.getLineStart(i), layout.getLineEnd(i), MX.toFloat(), baseline, paint)
                    y += lineH
                }
            }
        }

        /** Transcript turn [turn] starts here: the destination of the cites that point at it. */
        fun target(turn: Int) {
            if (y + 16f > bottom) newPage()
            targets[turn] = Spot(num - 1, y)
        }

        fun isCited(turn: Int) = turn in firstCite

        private fun turnOf(sec: Int): Int? = ExportNotes.targetFor(sec, turnStarts)

        /**
         * One paragraph from [left] (past the margin), its first line indented by [first], the rest by
         * [rest] (a hanging indent). [gutter] is drawn grey at the margin on the first line (the clock,
         * or a bullet just left of [left]). With [cites], every `[m:ss]` is drawn in [accent] and the
         * sentence it closes becomes a link to its transcript turn. [labelEnd] > 0: the first
         * characters (the speaker) drawn with [labelOf]. [back]: a link drawn at the right of the
         * first line, back to where turn [turn] is first cited.
         */
        fun para(
            text: String, paint: TextPaint, accent: TextPaint, left: Float, first: Float, rest: Float, gutter: String?, cites: Boolean,
            labelOf: TextPaint? = null, labelEnd: Int = 0, back: String? = null, backPaint: TextPaint? = null, turn: Int = -1,
        ) {
            val backW = if (back != null && backPaint != null) backPaint.measureText(back) + 10f else 0f
            val body = SpannableString(text)
            body.setSpan(LeadingMarginSpan.Standard(first.toInt(), rest.toInt()), 0, body.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val x0 = MX + left
            val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, (CW - left - backW).toInt())
                .setLineSpacing(3f, 1f).build()
            val ranges = if (cites) CITE.findAll(text).map { it.range }.toList() else emptyList()
            // Each cite's sentence: [start, cite end] → the turn of the cite's first time.
            class Span(val from: Int, val to: Int, val turn: Int?)
            val spans = ArrayList<Span>()
            var floor = 0
            for (r in ranges) {
                val t = TIME.find(text.substring(r.first, r.last + 1))?.value
                spans += Span(Cites.sentenceStart(text, r.first, floor), r.last + 1, t?.let { turnOf(ExportNotes.seconds(it)) })
                floor = r.last + 1
            }
            for (i in 0 until layout.lineCount) {
                val lineH = (layout.getLineTop(i + 1) - layout.getLineTop(i)).toFloat()
                if (y + lineH > bottom) newPage()
                val baseline = y + (layout.getLineBaseline(i) - layout.getLineTop(i))
                if (i == 0 && gutter != null) {
                    val gx = if (left > 0f && gutter.length <= 2) MX + left - accent.measureText(gutter) - 8f else MX.toFloat()
                    canvas.drawText(gutter, gx, baseline, if (gutter.length <= 2) paint else accent)
                }
                if (i == 0 && back != null && backPaint != null) {
                    val bx = (PW - MX) - backPaint.measureText(back)
                    canvas.drawText(back, bx, baseline, backPaint)
                    firstCite[turn]?.let { backward += Rect(num - 1, bx - 3f, y, (PW - MX).toFloat() + 2f, y + lineH) to it }
                }
                val x = x0 + if (i == 0) first else rest
                val ls = layout.getLineStart(i); val le = layout.getLineEnd(i)
                // Runs: the speaker label, plain text, and cites in the accent colour.
                var at = ls
                var cx = x
                while (at < le) {
                    val r = ranges.firstOrNull { at <= it.last && it.first < le && it.last >= at }
                    var runEnd = if (r == null) le else if (r.first > at) r.first else minOf(r.last + 1, le)
                    if (at < labelEnd) runEnd = minOf(runEnd, labelEnd)
                    val inCite = r != null && r.first <= at
                    val p = when { inCite -> accent; at < labelEnd && labelOf != null -> labelOf; else -> paint }
                    canvas.drawText(text, at, runEnd, cx, baseline, p)
                    if (inCite) for (t in TIME.findAll(text.substring(at, runEnd))) {
                        val a = at + t.range.first; val b = at + t.range.last + 1
                        val tx1 = cx + p.measureText(text, at, a) - 1f
                        val tx2 = cx + p.measureText(text, at, b) + 1f
                        turnOf(ExportNotes.seconds(t.value))?.let { forward += Rect(num - 1, tx1, y, tx2, y + lineH) to it }
                    }
                    cx += p.measureText(text, at, runEnd)
                    at = runEnd
                }
                // The cited sentence itself: one link rect per line it covers (under the times' own rects).
                for (sp in spans) {
                    val a = maxOf(sp.from, ls); val b = minOf(sp.to, le)
                    if (a >= b || sp.turn == null) continue
                    val x1 = x + paint.measureText(text, ls, a)
                    val x2 = x + paint.measureText(text, ls, b)
                    forward.add(0, Rect(num - 1, x1, y, x2, y + lineH) to sp.turn)
                    if (a == sp.from) firstCite.getOrPut(sp.turn) { Spot(num - 1, y) }
                }
                y += lineH
            }
        }

        fun links(): List<PdfLinker.Link> {
            fun box(r: Rect) = floatArrayOf(r.x1, PH - r.y2, r.x2, PH - r.y1)
            val out = ArrayList<PdfLinker.Link>()
            for ((r, t) in forward) targets[t]?.let { out += PdfLinker.Link(r.page, box(r), it.page, PH - it.y + 6f) }
            for ((r, s) in backward) out += PdfLinker.Link(r.page, box(r), s.page, PH - s.y + 14f)
            return out
        }

        private fun footer() {
            val n = num.toString()
            canvas.drawText(n, (PW - foot.measureText(n)) / 2f, (PH - MB / 2 + 4).toFloat(), foot)
        }

        fun finish() { footer(); doc.finishPage(page) }

        private fun newPage() {
            footer()
            doc.finishPage(page)
            num++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, num).create())
            canvas = page.canvas
            y = MT.toFloat()
        }
    }

    private val INK = Color.rgb(0x1F, 0x24, 0x2E)
    /** Timestamp gutter and meta grey (#5A6982). */
    private val GREY = Color.rgb(0x5A, 0x69, 0x82)
    private val ACCENT = Color.rgb(0x1D, 0x4E, 0x89)
    private val LINK = Color.rgb(0x2A, 0x6C, 0xB8)
    private val HAIRLINE = Color.rgb(0xD5, 0xDB, 0xE3)
}
