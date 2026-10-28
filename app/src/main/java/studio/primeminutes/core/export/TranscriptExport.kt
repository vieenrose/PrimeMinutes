package studio.primeminutes.core.export

import studio.primeminutes.core.events.TranscriptEvent
import java.util.Locale

/**
 * Pure, on-device serialisation of a transcript to portable text formats — plain text, SRT/VTT
 * subtitles, and Markdown. The session `.ogg` is the round-trip archive; THIS is how the words get
 * out into other apps (paste into a doc, caption a video, send over LINE/email). No Android/Context
 * dependency so it stays unit-testable; the caller supplies the localised speaker label + headings.
 *
 * Everything runs over the already-loaded [TranscriptEvent.Utterance] list (startSec/endSec/text/
 * speaker) — no model, no network, nothing leaves the device. Subtitles exploit Prime Minutes's
 * per-utterance timestamps + diarization labels, which the audio-only `.ogg` cannot express.
 */
object TranscriptExport {

    /**
     * Plain text: optional title + summary + action items, then the transcript as speaker turns —
     * `[mm:ss] Speaker: text` opens a turn, a blank line separates turns, and a further utterance of
     * the same speaker hangs under the text (indented by the gutter width, no repeated label).
     * The `[mm:ss]` token stays byte-identical to what `ReaderProtocol.LINE` / `Markdown.ANCHOR_RE` parse.
     */
    fun plainText(
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
        summary: String?,
        actionItems: String? = null,
        actionsHeading: String? = null,
    ): String = buildString {
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
        // "-" is the extractor's own "nothing found" marker, not an action item.
        actionItems?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let {
            actionsHeading?.let { h -> append(h).append("\n") }
            append(it).append("\n\n")
        }
        val pad = gutterWidth(utterances)
        for ((u, text, cont) in turns(utterances)) {
            if (cont) { append(" ".repeat(pad + 3)).append(text).append('\n'); continue }
            if (length > 0 && !endsWith("\n\n")) append('\n')
            append('[').append(clock(u.startSec, pad)).append("] ")
            u.speaker?.let { append(label(it)).append(": ") }
            append(text).append('\n')
        }
    }

    /** Markdown: H1 title, optional Summary + Action items sections, then a timestamped, speaker-bolded list. */
    fun markdown(
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
        summary: String?,
        summaryHeading: String,
        transcriptHeading: String,
        actionItems: String? = null,
        actionsHeading: String? = null,
    ): String = buildString {
        append("# ").append(title?.trim()?.ifEmpty { null } ?: transcriptHeading).append("\n\n")
        summary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("## ").append(summaryHeading).append("\n\n").append(it).append("\n\n")
        }
        actionItems?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let {
            // No heading given: the block carries its own (localized) headings — don't add an English one.
            actionsHeading?.let { h -> append("## ").append(h).append("\n\n") }
            append(it).append("\n\n")
        }
        append("## ").append(transcriptHeading).append("\n\n")
        val pad = gutterWidth(utterances)
        var first = true
        for ((u, text, cont) in turns(utterances)) {
            if (cont) { append("  ").append(text).append('\n'); continue }
            if (!first) append('\n')
            first = false
            append("- `").append(clock(u.startSec, pad)).append("` ")
            u.speaker?.let { append("**").append(label(it)).append("** ") }
            append(text).append('\n')
        }
    }

    // A BLANK line inside cue text terminates the cue early and desyncs every following cue's number/
    // timing — collapse any internal blank line to a single newline (rare, but ASR/edited text can
    // contain one). Applied to both the speaker label and the body. Pattern compiled once (this runs
    // per utterance × per format).
    private val BLANK_LINE = Regex("\\n\\s*\\n+")
    private fun oneCue(s: String): String = s.replace(BLANK_LINE, "\n").trim()

    /** WebVTT treats `<` as a cue tag and `&` as an entity — escape so literal text isn't mangled. */
    private fun vttEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** SubRip (.srt): numbered cues, `HH:MM:SS,mmm` timestamps, speaker prefix inline. */
    fun srt(utterances: List<TranscriptEvent.Utterance>, label: (Int) -> String): String = buildString {
        var n = 1
        for (u in cues(utterances)) {
            val text = oneCue(u.text)
            if (text.isEmpty()) continue
            append(n++).append('\n')
            append(stamp(u.startSec, ',')).append(" --> ").append(stamp(endOf(u), ',')).append('\n')
            u.speaker?.let { append(oneCue(label(it))).append(": ") }
            append(text).append("\n\n")
        }
    }

    /** WebVTT (.vtt): `WEBVTT` header, `HH:MM:SS.mmm` timestamps. */
    fun vtt(utterances: List<TranscriptEvent.Utterance>, label: (Int) -> String): String = buildString {
        append("WEBVTT\n\n")
        for (u in cues(utterances)) {
            val text = oneCue(u.text)
            if (text.isEmpty()) continue
            append(stamp(u.startSec, '.')).append(" --> ").append(stamp(endOf(u), '.')).append('\n')
            u.speaker?.let { append(vttEscape(oneCue(label(it)))).append(": ") }
            append(vttEscape(text)).append("\n\n")
        }
    }

    /**
     * LRC synced lyrics: an `[mm:ss.xx]` timestamp per line, in time order, under a `[ti:]` title header.
     * Saved next to the audio (same base name), players with a `.lrc` mode — Evermusic's "LRC File",
     * Poweramp, MusicBee, Marvis… — scroll the transcript in real time as the track plays.
     */
    fun lrc(
        utterances: List<TranscriptEvent.Utterance>,
        label: (Int) -> String,
        title: String?,
    ): String = buildString {
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { append("[ti:").append(it).append("]\n") }
        for (u in utterances) {
            val text = u.text.replace('\n', ' ').trim()
            if (text.isEmpty()) continue
            append(lrcStamp(u.startSec))
            u.speaker?.let { append(label(it)).append(": ") }
            append(text).append('\n')
        }
    }

    /**
     * Subtitle cues: an utterance is often a long turn (tens of seconds, hundreds of characters),
     * unreadable as one subtitle. Cut it at punctuation into pieces of at most [CUE_WIDTH] display
     * units (a CJK character is 2, anything else 1 — so 32 Chinese characters or 64 Latin ones, the
     * usual two subtitle lines), timing each piece in proportion to its length; short utterances pass
     * through unchanged. A cut forced by length backs off to the last space so no word is split.
     */
    internal fun cues(utterances: List<TranscriptEvent.Utterance>): List<TranscriptEvent.Utterance> =
        utterances.flatMap { u ->
            val text = oneCue(u.text)
            if (width(text) <= CUE_WIDTH) return@flatMap listOf(u)
            val pieces = ArrayList<String>()
            val sb = StringBuilder()
            var w = 0
            fun flush() { pieces += sb.toString().trim(); sb.setLength(0); w = 0 }
            for (c in text) {
                sb.append(c); w += width(c)
                val soft = c in "，、,；;：:" && w >= CUE_WIDTH / 2
                val hard = c in "。？！?!."
                if (hard || soft) flush()
                else if (w >= CUE_WIDTH) {
                    val cut = sb.lastIndexOf(' ')
                    if (cut > 0) {
                        val rest = sb.substring(cut + 1)
                        pieces += sb.substring(0, cut).trim()
                        sb.setLength(0); sb.append(rest); w = width(rest)
                    } else flush()
                }
            }
            if (sb.isNotBlank()) pieces += sb.toString().trim()
            val total = pieces.sumOf { it.length }.toDouble().coerceAtLeast(1.0)
            val dur = endOf(u) - u.startSec
            var at = u.startSec; var used = 0
            pieces.filter { it.isNotEmpty() }.map { p ->
                used += p.length
                val end = u.startSec + dur * used / total
                u.copy(text = p, startSec = at, endSec = end).also { at = end }
            }
        }

    private const val CUE_WIDTH = 64

    /** Display width of a character: CJK ideographs, kana, hangul and fullwidth forms take 2 cells. */
    private fun width(c: Char): Int =
        if (c in '⺀'..'鿿' || c in '가'..'힣' || c in '豈'..'﫿' || c in '＀'..'｠') 2 else 1
    private fun width(s: String): Int = s.sumOf { width(it) }

    /** LRC timestamp `[mm:ss.xx]` — xx = hundredths of a second; ASCII digits always. */
    private fun lrcStamp(sec: Double): String {
        val cs = (if (sec > 0) sec * 100 else 0.0).toLong()   // total centiseconds
        val h = cs % 100; val s = (cs / 100) % 60; val m = cs / 6000
        return String.format(Locale.US, "[%02d:%02d.%02d]", m, s, h)
    }

    // endSec can be <= startSec on a degenerate single-token VAD segment; give the cue at least 1s
    // so a subtitle player doesn't drop a zero-length entry.
    private fun endOf(u: TranscriptEvent.Utterance): Double =
        if (u.endSec > u.startSec) u.endSec else u.startSec + 1.0

    /** One transcript line of a document export: [cont] = same (known) speaker as the previous line. */
    internal data class Line(val u: TranscriptEvent.Utterance, val text: String, val cont: Boolean)

    /** Non-blank utterances (newlines flattened) grouped into speaker turns. Unknown speakers never merge. */
    internal fun turns(utterances: List<TranscriptEvent.Utterance>): List<Line> {
        val out = ArrayList<Line>()
        var prev: Int? = null
        for (u in utterances) {
            val text = u.text.replace('\n', ' ').trim()
            if (text.isEmpty()) continue
            out += Line(u, text, u.speaker != null && u.speaker == prev && out.isNotEmpty())
            prev = u.speaker
        }
        return out
    }

    /** Clock width for the whole document: 5 (`mm:ss`) or 8 (`hh:mm:ss`) so the gutter aligns. */
    internal fun gutterWidth(utterances: List<TranscriptEvent.Utterance>): Int =
        if (utterances.any { it.startSec >= 3600 }) 8 else 5

    /** Zero-padded `mm:ss`, or `hh:mm:ss` when [width] is 8 — still matches `\d+:\d{2}(?::\d{2})?`. */
    internal fun clock(sec: Double, width: Int = 5): String {
        val t = if (sec > 0) sec.toInt() else 0
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0 || width >= 8) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Subtitle timestamp `HH:MM:SS<sep>mmm` (sep = ',' for SRT, '.' for VTT). ASCII digits always. */
    private fun stamp(sec: Double, sep: Char): String {
        val total = (if (sec > 0) sec * 1000 else 0.0).toLong()
        val ms = total % 1000
        val s = (total / 1000) % 60
        val m = (total / 60_000) % 60
        val h = total / 3_600_000
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, sep, ms)
    }
}
