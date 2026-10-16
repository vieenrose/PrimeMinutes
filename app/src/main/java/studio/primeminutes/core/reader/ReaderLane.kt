package studio.primeminutes.core.reader

import studio.primeminutes.core.models.ModelCard
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import studio.primeminutes.core.events.TranscriptEvent
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * The summarization LANE: one dedicated, low-priority thread that owns the reader session. The ASR
 * lane hands it utterances as they become stable ([feed]); lines queue on the thread and are never
 * dropped — if a reading turn is slow, feeding catches up afterwards. ASR never waits for it
 * (integration note §5).
 *
 * The same lane runs post-hoc (imports on small devices, re-summarize, the queue drain): feed the
 * finished transcript, then [finish].
 */
class ReaderLane(
    private val llm: ReaderLlm,
    private val systemPrompt: String,
    /** The model's card: protocol, budgets, sampler and the title/prose prompts. */
    private val card: ModelCard,
    private val events: (AgentEvent) -> Unit,
) : AutoCloseable {
    private val budget = card.budget
    /** Title and prose read the journal compacted to this many characters (0 = all of it): a whole
     *  meeting's journal does not fit a 4k context (integration note §12.3). */
    private val notesChars = card.reader.notesChars


    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "primeminutes-reader")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val reader = MeetingReader(llm, systemPrompt, events = events, budget = budget, pc = card.reader, temp = card.sampler.temp,
        byLanguage = { card.forLanguage(it).reader })

    /** The card for this meeting's language (as heard so far; call on the lane's thread). */
    private fun langCard() = card.forLanguage(reader.language)

    /** The meeting's language as the reader detected it ([MeetingLanguage] code), after [finish]. */
    @Volatile var language: String? = null
        private set

    /** The last prose reply as the model wrote it, before [cleanProse] (diagnostics, the quality harness). */
    @Volatile var lastProseRaw: String? = null
        private set

    /** Utterances already handed to the reader (by index in the live snapshot). */
    private var fed = 0
    /** End time of the last fed utterance — how the final re-attributed snapshot is joined. */
    private var fedUntilSec = -1.0
    private var failure: Throwable? = null

    /** Queue [block] on the reader thread; a failure is kept and rethrown by [finish]. */
    private fun post(block: () -> Unit) {
        executor.execute {
            if (failure != null) return@execute
            try { block() } catch (t: Throwable) { failure = t; android.util.Log.e("primeminutes-reader", "reader failed", t) }
        }
    }

    fun start() = post { llm.reset(); reader.start() }

    /** Persist the reader's state after each window (called on the reader thread). */
    fun onCheckpoint(f: (ReaderCheckpoint) -> Unit) { reader.onCheckpoint = f }

    /**
     * Start from a saved [cp] over [utterances] (the transcript so far): the lines it covers are not
     * read again, the rest are queued like a live feed. [fed]/[fedUntilSec] then continue from the
     * end of [utterances], for both the live snapshots and [finish].
     */
    fun resume(cp: ReaderCheckpoint, utterances: List<TranscriptEvent.Utterance>): Boolean {
        val lines = utterances.mapNotNull { toLine(it) }
        val n = cp.offered
        // The notes only stand for the transcript they were read from: a different one (re-run ASR,
        // edits) means a full reading, never a resume on mismatched notes.
        if (n > lines.size || ReaderCheckpoint.digestOf(lines.subList(0, n)) != cp.digest) return false
        fed = utterances.size
        utterances.lastOrNull()?.let { fedUntilSec = it.endSec }
        post {
            llm.reset()
            reader.seed(cp.copy(offered = n), lines.subList(0, n))
            lines.drop(n).forEach(reader::offer)
        }
        return true
    }

    /** A live snapshot: hand over the utterances that became stable since the last call. */
    fun feed(snapshot: TranscriptEvent.UtteranceSnapshot) {
        val stable = snapshot.stable.coerceAtMost(snapshot.utterances.size)
        if (stable <= fed) return
        val fresh = snapshot.utterances.subList(fed, stable).toList()
        fed = stable
        fresh.lastOrNull()?.let { fedUntilSec = it.endSec }
        offer(fresh)
    }

    /** Post-hoc: hand over a whole finished transcript. */
    fun feedAll(utterances: List<TranscriptEvent.Utterance>) {
        utterances.lastOrNull()?.let { fedUntilSec = it.endSec }
        offer(utterances)
    }

    /**
     * End of input. [final] is the final (fully re-attributed) transcript; the part after what the
     * live view already fed is appended, then the last window is read. Returns the result once the
     * lane has drained everything queued.
     */
    suspend fun finish(final: List<TranscriptEvent.Utterance>): ReaderResult {
        // The input is over: say so now — the lane may still be prefilling queued lines, and its
        // "reading the transcript, progress to the next notes" bar means nothing after Stop.
        events(AgentEvent.State(AgentState.READING, window = maxOf(reader.window, 1), notes = reader.journal.size))
        offer(final.filter { it.startSec >= fedUntilSec - 0.01 })
        return withContext(dispatcher) {
            failure?.let { throw it }
            val minutes = reader.finish()
            val g = reader.guard
            val exceeded = g.exceeds(card.reader.guard)
            if (exceeded) android.util.Log.w("primeminutes-reader", "guard: off-protocol output above ${card.reader.guard.maxOffProtocolRate} (${card.name}): ${g.summary()}")
            else android.util.Log.i("primeminutes-reader", "guard: ${g.summary()}")
            events(AgentEvent.Guard(g, exceeded))
            language = reader.language
            ReaderResult(reader.journal.toList(), minutes, langCard().reader.actions(reader.journal), g)
        }
    }

    /** Post-hoc from an already formatted transcript (`[M:SS] S1: text` lines, re-summarize). */
    fun feedText(transcript: String) {
        val lines = transcript.lines().mapNotNull(ReaderProtocol::parseLine)
        if (lines.isNotEmpty()) post { lines.forEach(reader::offer) }
    }

    /**
     * A short title for the meeting, from the journal — one fresh conversation on the same model
     * (the reading session is over). Not part of the upstream protocol: the model was trained to
     * write notes, so this is a plain instruction and its output is sanitised hard. Null when the
     * journal is empty or nothing usable comes back.
     */
    suspend fun title(journal: List<Note>): String? = withContext(dispatcher) {
        if (journal.isEmpty()) return@withContext null
        llm.reset()
        val c = langCard()
        val toks = fitting(journal, c.title.maxTokens, c.title::render)
        if (llm.append(toks) < 0) return@withContext null
        val raw = llm.generateContinue(c.title.maxTokens, llm.template.end, c.sampler.temp) {}
        raw.substringBefore(llm.template.end).lines().firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('「', '」', '"', '*', '#', ' ')
            ?.let { t -> c.title.label?.let { t.replace(it, "") } ?: t }   // "會議：…" / "標題：…" — the model labels its answer now and then
            ?.trim('「', '」', '"', '*', '#', ' ')?.let(::cutTitle)?.ifBlank { null }
    }

    /**
     * The final summary as prose, from the journal — like [title], one fresh conversation on the same
     * model and not part of the upstream protocol. Every `[ts]` it cites must be a journal time
     * (anything else is stripped), so the summary's timestamps stay tap-to-play and grounded. Null
     * when the journal is empty or the reply is unusable; callers fall back to the grouped minutes.
     */
    suspend fun prose(journal: List<Note>): String? = withContext(dispatcher) {
        if (journal.isEmpty()) return@withContext null
        llm.reset()
        val c = langCard()
        val toks = fitting(journal, c.prose.maxTokens, c.prose::render)
        if (llm.append(toks) < 0) return@withContext null
        val raw = llm.generateContinue(c.prose.maxTokens, llm.template.end, c.sampler.temp) {}
        lastProseRaw = raw
        cleanProse(raw, journal.map { it.ts }.toSet(), llm.template.end)
    }

    private fun notesFor(journal: List<Note>, chars: Int = notesChars): List<Note> =
        if (chars > 0) card.reader.compactNotes(journal, chars) else journal

    /** The one-shot prompt over the notes, compacted further while it would not leave [maxOut]
     *  tokens of room in the context (3,900 characters is upstream's measure; Chinese runs denser
     *  in some meetings). */
    private fun fitting(journal: List<Note>, maxOut: Int, prompt: (String) -> String): IntArray {
        var chars = notesChars
        while (true) {
            val notes = notesFor(journal, chars).joinToString("\n") { ReaderProtocol.render(it) }
            val toks = llm.tokenize(llm.template.userFirst, true) + llm.tokenize(prompt(notes), false) +
                llm.tokenize(llm.template.p2, true)
            if (chars <= 0 || toks.size + maxOut + 8 <= budget.ctxBudget || chars <= 800) return toks
            chars -= 400
        }
    }

    private fun offer(utts: List<TranscriptEvent.Utterance>) {
        val lines = utts.mapNotNull { toLine(it) }
        if (lines.isNotEmpty()) post { lines.forEach(reader::offer) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** The prose reply made presentable: `[ts]` tags checked against the notes' times
         *  ([ReaderProtocol.repairTimestamps]: kept, snapped onto a near one, or stripped with the space
         *  it leaves before punctuation), no headings or bullets, and a reply cut off by the card's prose budget (no end-of-turn)
         *  ends at its last whole sentence — a long meeting's summary stopped mid-word on "[12:28".
         *  [turnEnd] is the template's end of turn. */
        fun cleanProse(raw: String, known: Set<String>, turnEnd: String = ModelCard.DEFAULT.template.end): String? {
            val ended = raw.contains(turnEnd)
            var text = ReaderProtocol.repairTimestamps(raw.substringBefore(turnEnd), known)
                .replace(Regex("""(?<=\S)[ \t]{2,}(?=\S)"""), " ")
                .replace(Regex("""[ \t]+([。，、；：！？.,;:!?])"""), "$1")
                .lines().map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() && !it.startsWith("-") && !it.startsWith("*") }
                .joinToString("\n\n")
            if (!ended) {
                // The last sentence end, with the timestamp that may follow it ("…。 [1:23]" / "… [1:23]。").
                val end = Regex("""[。！？!?](\s*\[\d+:\d{2}(?::\d{2})?\])?|[.](?=\s|$)""").findAll(text).lastOrNull()
                if (end != null) text = text.substring(0, end.range.last + 1).trim()
            }
            return text.takeIf { it.length >= 20 }
        }

        /** A title kept short: 40 characters, or 60 cut at a word end for a spaced (English) one —
         *  40 characters cut an English title mid-word. */
        fun cutTitle(t: String): String = when {
            t.length <= 40 -> t
            ' ' !in t -> t.take(40)
            t.length <= 60 -> t
            else -> t.take(61).substringBeforeLast(' ').trimEnd(',', ';', ':', '-', ' ')
        }

        /** An utterance as the model reads it (ingest.segments_to_lines): cleaned text, `S{n}`
         *  speaker labels in first-appearance order (the engine's own numbering), whole seconds. */
        fun toLine(u: TranscriptEvent.Utterance): Line? {
            val text = ReaderProtocol.cleanText(u.text)
            if (text.isEmpty()) return null
            return Line(floor(u.startSec).toInt(), u.speaker?.let { "S${it + 1}" }, text)
        }
    }
}

/** What a reader run produced: the journal, the minutes assembled from it and its actions (proposal guard applied). */
data class ReaderResult(val journal: List<Note>, val minutes: String, val actions: List<Note>, val guard: ReaderGuard = ReaderGuard())
