package studio.primeminutes.core.export

import studio.primeminutes.core.events.TranscriptEvent
import java.util.Locale

/**
 * The transcript as the PDF lays it out: speaker turns (newlines flattened, blanks dropped) and the
 * zero-padded clock of the timestamp gutter. Pure, no Android.
 */
object TranscriptExport {

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
}
