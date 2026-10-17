package studio.primeminutes.core.embed

import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.reader.Note
import studio.primeminutes.core.reader.ReaderProtocol

/**
 * The in-meeting search (docs/design/in-session-search-ask.md): the open meeting's notes and transcript
 * lines matching the query, notes first, then lines, each group in meeting order. Keyword matches are
 * found in memory (the live view of a meeting still processing too); passages found by meaning
 * ([LibrarySearch.dense] on this one meeting) are merged in with [Result.semanticOnly].
 */
object MeetingSearch {
    const val MAX_RESULTS = 50
    /** Passages asked from the dense pass over one meeting. */
    const val DENSE_PASSAGES = 20

    enum class Kind { NOTE, LINE }

    /** [line]: the transcript line a tap jumps to (for a note, the line at its time; -1 when none). */
    data class Result(
        val kind: Kind, val line: Int, val timeSec: Double, val text: String,
        val speaker: Int? = null, val semanticOnly: Boolean = false,
    )

    /**
     * The line a time falls in: the last line starting at or before [t], -1 for none. A whole-second
     * time (a note's or a citation's `m:ss`) also takes a line starting later in that same second: the
     * note cites the line shown with that time (a line at 202.4 s shows 3:22; "3:22" is 202.0 s).
     */
    fun lineAt(utterances: List<TranscriptEvent.Utterance>, t: Double): Int {
        if (utterances.isEmpty()) return -1
        val limit = if (t == kotlin.math.floor(t)) t + 0.999 else t + 0.01
        return utterances.indexOfLast { it.startSec <= limit }.coerceAtLeast(0)
    }

    /** The player position (ms) that parks on a line starting at [startSec]: rounded up, so the
     *  playing-line test (start <= position) picks that line and not the one before. */
    fun parkMs(startSec: Double): Int = kotlin.math.ceil(startSec * 1000.0).toInt()

    /**
     * Keyword results: every note and line containing [query] (case-insensitive, like the in-line
     * highlight), then the [dense] passages not already there, marked semantic-only. Capped at [MAX_RESULTS].
     */
    fun results(
        query: String,
        utterances: List<TranscriptEvent.Utterance>,
        notes: List<Note>,
        dense: List<LibrarySearch.Passage> = emptyList(),
    ): List<Result> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val noteHits = notes.filter { it.text.contains(q, ignoreCase = true) }.map { n ->
            val t = (ReaderProtocol.parseTs(n.ts) ?: 0).toDouble()
            Result(Kind.NOTE, lineAt(utterances, t), t, n.text)
        }.toMutableList()
        val lineHits = utterances.indices.filter { utterances[it].text.contains(q, ignoreCase = true) }.map { i ->
            val u = utterances[i]; Result(Kind.LINE, i, u.startSec, u.text, u.speaker)
        }.toMutableList()
        for (p in dense) when (p.kind) {
            SessionIndex.Kind.NOTE -> if (noteHits.none { it.text == p.snippet } && p.snippet.isNotBlank())
                noteHits += Result(Kind.NOTE, lineAt(utterances, p.timeSec), p.timeSec, p.snippet, semanticOnly = true)
            SessionIndex.Kind.CHUNK -> if (p.uttIndex in utterances.indices && lineHits.none { it.line in p.uttIndex..p.uttEnd }) {
                val u = utterances[p.uttIndex]
                lineHits += Result(Kind.LINE, p.uttIndex, u.startSec, u.text, u.speaker, semanticOnly = true)
            }
            SessionIndex.Kind.MEETING -> {}
        }
        return (noteHits.sortedBy { it.timeSec } + lineHits.sortedBy { it.line }).take(MAX_RESULTS)
    }

    /** The lines the bar's stepper walks: the line results (a note's line too), in transcript order. */
    fun stepLines(results: List<Result>, semantic: Boolean): List<Int> =
        results.filter { it.line >= 0 && (semantic || !it.semanticOnly) && it.kind == Kind.LINE }.map { it.line }.distinct().sorted()
}
