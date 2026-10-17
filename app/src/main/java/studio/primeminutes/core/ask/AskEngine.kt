package studio.primeminutes.core.ask

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.primeminutes.core.embed.EmbedHost
import studio.primeminutes.core.embed.Embedder
import studio.primeminutes.core.embed.LibrarySearch
import studio.primeminutes.core.embed.SessionIndex
import studio.primeminutes.core.hw.HwInfo
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.llm.MfaEngine
import studio.primeminutes.core.llm.MfaSession
import studio.primeminutes.core.llm.SpTokenizer
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelCard
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.reader.MeetingLanguage
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.BuildConfig
import java.io.File

/**
 * Ask (RAG, experimental — plan §6): a question over the whole library, answered by the reader
 * model from the passages ranked highest by meaning (the semantic index) and by keywords (BM25),
 * fused by reciprocal rank. Each passage carries a short tag `[[k]]`, numbered across the
 * conversation, that the model is told to cite; the answer is published with the tags expanded to
 * `[[entryId@sec]]`, which the sheet renders as "title · m:ss" and opens the meeting there.
 *
 * The reader model is not resident (it is unloaded after each summary), so Ask loads it on demand
 * and keeps it while the sheet stays open ([release] frees it). It is refused while a recording, a
 * transcription or a reading runs (memory and CPU), and a job starting mid-answer cancels the
 * answer: the pipeline always wins.
 */
object AskEngine {
    private const val TAG = "primeminutes-ask"
    private const val MAX_PASSAGES = 8
    /** Tokens of excerpts per question: what 8 fused passages used to fill on the median (quality eval
     *  2026-10, "Ask after fixes"); routing makes them count, a larger budget only slowed the prefill. */
    private const val PASSAGE_TOKENS = 800
    private const val NOTE_BONUS = 0.05f      // notes are denser than raw chunks: prefer them at equal score
    private const val RRF_K = 60f
    /** The tokens of an excerpt's header line (`[[k]] title · m:ss`) and spacing. */
    private const val HEADER_TOKENS = 24

    sealed class Phase {
        object Idle : Phase()
        object Busy : Phase()                    // refused: a job is running
        object Retrieving : Phase()
        /** The model loads (when it is not resident) and reads the [excerpts] found: the long wait. */
        data class Reading(val excerpts: Int) : Phase()
        /** From the first token of the answer. */
        object Answering : Phase()
        data class Failed(val error: String) : Phase()

        /** A question is being worked on (the sheet shows the stage and the seconds since [startedAt]). */
        val working: Boolean get() = this is Retrieving || this is Reading || this is Answering
    }

    /** When the question in progress was asked (SystemClock.elapsedRealtime), for the elapsed seconds. */
    private val _startedAt = MutableStateFlow(0L)
    val startedAt: StateFlow<Long> = _startedAt

    /** One excerpt handed to the model. */
    /** [sec] is the whole second of the citation tag; [at] the passage's exact start, where a tap opens it
     *  (115 for a line starting at 115.4 s opened the line before). */
    /** [k] is the short tag the model sees (`[[k]]`), unique across the conversation. */
    /** [a]..[b]: the transcript utterances a CHUNK excerpt covers (-1 for a note), so neighbours can widen it. */
    data class Excerpt(
        val entry: SessionLibrary.Entry, val sec: Int, val text: String, val score: Float, val at: Double = sec.toDouble(), val k: Int = 0,
        val a: Int = -1, val b: Int = -1,
    )

    /**
     * Retrieval knobs, model-agnostic (the token budget is counted with the loaded model's tokenizer).
     * Meeting routing: the [routeMeetings] meetings ranked first by their passages (dense and keyword
     * evidence, see the function [routeMeetings]) give [routeDepth] candidates per list instead of [perMeeting], and their
     * passages lead the ranking — near-duplicate meetings stole the slots of the one asked about.
     * [seeds] best passages are taken, overlapping transcript passages of a meeting merged into one;
     * then each excerpt is widened [widen] times by the transcript chunk after it and the one before it
     * while [budgetTokens] lasts. [rrfK]: the reciprocal-rank constant of the passage fusion.
     */
    data class Retrieval(
        val maxExcerpts: Int = MAX_PASSAGES,
        val budgetTokens: Int = PASSAGE_TOKENS,
        val perMeeting: Int = 4,
        val seeds: Int = MAX_PASSAGES,
        val widen: Int = 0,
        val routeMeetings: Int = 2,
        val routeDepth: Int = 16,
        val rrfK: Float = RRF_K,
    ) {
        companion object {
            /** The retrieval before meeting routing (8 fused passages, ≤ 1 500 tokens): DEV before/after timings. */
            val LEGACY = Retrieval(budgetTokens = 1500, routeMeetings = 0)
        }
    }

    /** What [select] needs from the library: a meeting's chunk ranges and the text of an utterance range. */
    interface Transcripts {
        fun chunks(e: SessionLibrary.Entry): List<IntRange>
        /** The utterances [a]..[b] as an excerpt (score 0), or null. */
        fun span(e: SessionLibrary.Entry, a: Int, b: Int): Excerpt?
    }

    @Volatile var retrieval = Retrieval()

    /** Tokens of the last question's prompt (this turn's part of the conversation), for logs and the eval harness. */
    @Volatile var lastPromptTokens = 0
        private set

    /** [pool]: every excerpt of the conversation so far, this turn's included — what a citation may point to. */
    data class Turn(val question: String, val answer: String, val excerpts: List<Excerpt>, val done: Boolean, val pool: List<Excerpt> = excerpts)

    private val SHORT_TAG = Regex("""\[{1,2}(\d{1,3})]{1,2}""")

    /** The model's `[[k]]` tags rewritten `[[entryId@sec]]`; a number no excerpt carries is left for the sheet to drop. */
    fun expandTags(answer: String, pool: List<Excerpt>): String = dedupeTags(SHORT_TAG.replace(answer) { m ->
        pool.firstOrNull { it.k == m.groupValues[1].toInt() }?.let { "[[${it.entry.id}@${it.sec}]]" } ?: m.value
    })

    private val LONG_TAG = Regex("""\[\[[^\[\]@]+@\d+]]""")
    private val TAG_RUN = Regex("""\[\[[^\[\]@]+@\d+]](?:[ \t]*[,，、;；]?[ \t]*\[\[[^\[\]@]+@\d+]])+""")

    /** A run of citations naming the same passage twice (`[[x@879]], [[x@879]]`) keeps it once. */
    fun dedupeTags(text: String): String = TAG_RUN.replace(text) { m ->
        val tags = LONG_TAG.findAll(m.value).map { it.value }.toList()
        val distinct = tags.distinct()
        if (distinct.size == tags.size) m.value
        else distinct.joinToString(m.value.substring(LONG_TAG.find(m.value)!!.range.last + 1, LONG_TAG.findAll(m.value).elementAt(1).range.first))
    }

    /** Whole seconds since [startedAt] (both SystemClock.elapsedRealtime), never negative. */
    fun elapsedSec(startedAt: Long, now: Long): Int = if (startedAt <= 0L) 0 else ((now - startedAt) / 1000L).toInt().coerceAtLeast(0)

    /** A follow-up continues the conversation only if its prompt and a whole answer still fit the cache. */
    fun followUpFits(seqLength: Int, promptTokens: Int, answerTokens: Int, context: Int): Boolean =
        seqLength + promptTokens + answerTokens < context

    /**
     * Reciprocal-rank fusion of the dense and keyword passages (meeting summaries left out), one
     * score per passage: 1/(k+rank) in each list it appears in. A note outranks a chunk at equal
     * cosine in the dense list ([NOTE_BONUS]); the keyword list keeps the BM25 order.
     */
    fun fuse(
        dense: List<LibrarySearch.Hit>, keyword: List<LibrarySearch.Hit>, k: Float = RRF_K,
        /** A transcript passage of a person the question names (entry id, speaker id): the note bonus too. */
        speaks: (String, Int?) -> Boolean = { _, _ -> false },
    ): List<Excerpt> {
        data class Key(val id: String, val kind: SessionIndex.Kind, val a: Int)
        val score = LinkedHashMap<Key, Float>()
        val first = HashMap<Key, Excerpt>()
        fun add(hits: List<LibrarySearch.Hit>, bonus: Boolean) {
            hits.flatMap { h -> h.passages.filter { it.kind != SessionIndex.Kind.MEETING }.map { h.entry to it } }
                .sortedByDescending { (e, p) ->
                    p.score + (if (bonus && p.kind == SessionIndex.Kind.NOTE) NOTE_BONUS else 0f) +
                        (if (bonus && p.kind == SessionIndex.Kind.CHUNK && speaks(e.id, p.speakerId)) NOTE_BONUS else 0f)
                }
                .forEachIndexed { rank, (e, p) ->
                    val key = Key(e.id, p.kind, p.uttIndex)
                    score[key] = (score[key] ?: 0f) + 1f / (k + rank + 1)
                    first.getOrPut(key) {
                        if (p.kind == SessionIndex.Kind.CHUNK) Excerpt(e, p.timeSec.toInt(), p.snippet, 0f, p.timeSec, a = p.uttIndex, b = p.uttEnd)
                        else Excerpt(e, p.timeSec.toInt(), p.snippet, 0f, p.timeSec)
                    }
                }
        }
        add(dense, bonus = true)
        add(keyword, bonus = false)
        return score.entries.sortedByDescending { it.value }.map { (key, v) -> first.getValue(key).copy(score = v) }
    }

    /**
     * The excerpts handed to the model, from the ranking [ranked] (routed meetings first, [retrieve]): seeds,
     * overlapping transcript passages merged, then widening by neighbour chunks while the budget lasts.
     * [tokens] counts an excerpt's text.
     */
    fun select(ranked: List<Excerpt>, p: Retrieval, tokens: (String) -> Int, src: Transcripts?): List<Excerpt> {
        val out = ArrayList<Excerpt>()
        val cost = HashMap<Excerpt, Int>()
        var used = 0
        fun price(x: Excerpt) = tokens(x.text) + HEADER_TOKENS
        /** Puts [x] (a widened or merged excerpt) in place of [olds], if the budget allows. */
        fun replace(olds: List<Excerpt>, x: Excerpt): Boolean {
            val n = price(x); val freed = olds.sumOf { cost.getValue(it) }
            if (used - freed + n > p.budgetTokens) return false
            val at = olds.minOf { out.indexOf(it) }   // where the earliest of them stood
            olds.forEach { out.remove(it); cost.remove(it) }
            out.add(at.coerceIn(0, out.size), x); cost[x] = n; used += n - freed
            return true
        }
        fun widened(x: Excerpt, a: Int, b: Int): Excerpt? = src?.span(x.entry, a, b)?.copy(score = x.score)
        /** Excerpts of [x]'s meeting overlapping or touching the utterances [a]..[b]. */
        fun touching(x: Excerpt, a: Int, b: Int) = out.filter { it.entry.id == x.entry.id && it.a >= 0 && it.a <= b + 1 && it.b >= a - 1 }
        for (c in ranked) {
            if (out.size >= minOf(p.seeds, p.maxExcerpts)) break
            // A note and its transcript chunk, or a meeting imported twice, can carry the same words: one excerpt is enough.
            if (out.any { it.text == c.text }) continue
            val near = if (c.a >= 0) touching(c, c.a, c.b) else emptyList()
            if (near.isNotEmpty()) {
                val a = minOf(c.a, near.minOf { it.a }); val b = maxOf(c.b, near.maxOf { it.b })
                if (near.size == 1 && near[0].a <= c.a && near[0].b >= c.b) continue   // already inside
                widened(c, a, b)?.let { replace(near, it.copy(score = near.maxOf { n -> n.score })) }
                continue
            }
            val n = price(c)
            if (used + n > p.budgetTokens) continue
            out += c; cost[c] = n; used += n
        }
        if (src == null) return out
        repeat(p.widen) {
            for (dir in intArrayOf(1, -1)) {
                for (x in out.toList()) {
                    if (x !in out || x.a < 0) continue
                    val cs = src.chunks(x.entry)
                    val r = if (dir > 0) cs.firstOrNull { it.last > x.b && it.first <= x.b + 1 } else cs.lastOrNull { it.first < x.a && it.last >= x.a - 1 }
                    if (r == null) continue
                    val a = minOf(x.a, r.first); val b = maxOf(x.b, r.last)
                    val olds = listOf(x) + touching(x, a, b).filter { it !== x }
                    val a2 = minOf(a, olds.minOf { it.a }); val b2 = maxOf(b, olds.maxOf { it.b })
                    widened(x, a2, b2)?.let { replace(olds, it) }
                }
            }
        }
        return out
    }

    /**
     * Meeting routing: the [n] meetings ranked first by reciprocal-rank fusion of two meeting scores —
     * the mean cosine of its 3 best passages (dense, summaries left out) and the sum of its 3 best BM25
     * scores (keyword). Empty when [n] ≤ 0.
     */
    fun routeMeetings(dense: List<LibrarySearch.Hit>, keyword: List<LibrarySearch.Hit>, n: Int): Set<String> {
        if (n <= 0) return emptySet()
        val score = LinkedHashMap<String, Float>()
        fun add(per: List<Pair<String, Float>>) = per.sortedByDescending { it.second }.forEachIndexed { r, (id, _) ->
            score[id] = (score[id] ?: 0f) + 1f / (RRF_K + r + 1)
        }
        add(dense.mapNotNull { h ->
            val top = h.passages.filter { it.kind != SessionIndex.Kind.MEETING }.map { it.score }.sortedDescending().take(3)
            if (top.isEmpty()) null else h.entry.id to top.sum() / 3f
        })
        add(keyword.map { h -> h.entry.id to h.passages.map { it.score }.sortedDescending().take(3).sum() })
        return score.entries.sortedByDescending { it.value }.take(n).map { it.key }.toSet()
    }

    /** A citation checked against the excerpts the model was given for THIS answer: the exact tag, else the
     *  nearest excerpt of the same meeting (a mistyped second), else null — a meeting it was never shown,
     *  or an id it invented. */
    fun cite(id: String, sec: Int, excerpts: List<Excerpt>): Excerpt? {
        val same = excerpts.filter { it.entry.id == id }
        return same.firstOrNull { it.sec == sec } ?: same.minByOrNull { kotlin.math.abs(it.sec - sec) }
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var engine: MfaEngine? = null
    private var tok: SpTokenizer? = null
    private var session: MfaSession? = null
    private var modelId: String? = null
    private var job: Job? = null
    private var nextK = 1
    /** The language of the conversation's system prompt: the first question's ([answerLanguage]). */
    @Volatile private var convLang: String? = null

    /** The loaded-model holder: the embedder of the search index must be present. */
    fun available(context: Context): Boolean =
        EmbedHost.ready(context) && ModelManager(context).llmReady(LlmRegistry.byId(
            studio.primeminutes.core.config.TranscriptionConfig.Holder.config.llmModelId))

    fun busy(): Boolean = studio.primeminutes.service.RunRegistry.busy

    /**
     * Asks [question] over [entries]. A follow-up question keeps the conversation (the model's KV
     * cache holds the earlier turns); [reset] starts over. Streams into [turns].
     */
    fun ask(context: Context, entries: List<SessionLibrary.Entry>, question: String, uiLanguage: String) {
        val app = context.applicationContext
        job?.cancel()
        job = scope.launch {
            if (busy()) { Log.i(TAG, "refused: a job is running"); _phase.value = Phase.Busy; return@launch }
            try {
                _startedAt.value = android.os.SystemClock.elapsedRealtime()
                _phase.value = Phase.Retrieving
                val excerpts = retrieve(app, entries, question).map { it.copy(k = nextK++) }
                val pool = _turns.value.flatMap { it.excerpts } + excerpts
                _turns.value = _turns.value + Turn(question, "", excerpts, false, pool)
                _phase.value = Phase.Reading(excerpts.size)
                // The model is opened, prompted and run under the lock: release() cannot close the
                // engine under a running generate (cancel() makes that generate return first).
                mutex.withLock {
                    val s = open(app)
                    s.resume()
                    val sb = StringBuilder()
                    val first = _turns.value.size == 1
                    val answerLang = answerLanguage(question, uiLanguage)
                    // The system prompt is the first question's language; a follow-up in another language
                    // gets the card's word for its own (the conversation's cached prefix stays as it is).
                    if (first || convLang == null) convLang = answerLang
                    val sysLang = convLang ?: answerLang
                    val t = s.template
                    val prompt = buildString {
                        if (first) { append(t.p0); append(system(sysLang)); append(t.p1) }
                        else append(t.p3)
                        append(render(excerpts, answerLang)); append("\n\n"); append(asked(card(), question, uiLanguage, sysLang)); append(t.p2)
                    }
                    val tPrompt = System.nanoTime()
                    val card = card()
                    val toks = s.tokenize(prompt, special = true)
                    lastPromptTokens = toks.size
                    // A follow-up that would leave no room for a whole answer, or that the cache refuses,
                    // starts a fresh conversation with this question only (the answer was cut short before).
                    if (!first && !followUpFits(s.seqLength(), toks.size, card.ask.answerTokens, engine?.context ?: 0)
                        || s.append(toks) < 0) {
                        Log.i(TAG, "prompt does not fit the conversation (${s.seqLength()} + ${toks.size} + ${card.ask.answerTokens}): fresh conversation")
                        s.reset()
                        convLang = answerLang
                        val fresh = t.p0 + system(answerLang) + t.p1 + render(excerpts, answerLang) + "\n\n" + asked(card, question, uiLanguage, answerLang) + t.p2
                        if (s.append(s.tokenize(fresh, special = true)) < 0) error("prompt does not fit")
                    }
                    val pre = engine?.lastStats   // the prompt's prefill (append runs its own generate call)
                    val t0 = System.nanoTime()
                    var tFirst = 0L
                    var cut = false
                    s.generateContinue(card.ask.answerTokens, "", card.sampler.temp) { piece ->
                        if (busy()) { cut = true; s.cancel() }   // a job started: the pipeline wins
                        if (tFirst == 0L) { tFirst = System.nanoTime(); _phase.value = Phase.Answering }
                        sb.append(piece); publish(expandTags(sb.toString(), pool), done = false)
                    }
                    val st = engine?.lastStats
                    Log.i(TAG, "answered in ${(System.nanoTime() - tPrompt) / 1_000_000} ms: prefill ${pre?.prefilled} tok (${pre?.reused} reused) ${pre?.prefillSec}s, first token ${(tFirst - t0) / 1_000_000} ms, ${st?.generated} tok ${st?.decodeSec}s${if (cut) ", cut by a job" else ""}")
                    if (BuildConfig.DEBUG) {
                        // DEV: the answer and the excerpt tags, so a device run can be checked from logcat.
                        Log.d(TAG, "excerpts: " + excerpts.joinToString(" ") { "[[${it.k}]]=[[${it.entry.id}@${it.sec}]]" })
                        if (System.getProperty("vox.askdump") != null || android.util.Log.isLoggable("primeminutes-askdump", Log.DEBUG))
                            render(excerpts, answerLang).lines().forEach { Log.d("primeminutes-askdump", it) }
                        Log.d(TAG, "answer: " + expandTags(sb.toString(), pool).replace("\n", " ⏎ "))
                    }
                    publish(expandTags(sb.toString().trim(), pool), done = true)
                    _phase.value = if (busy() && sb.isBlank()) Phase.Busy else Phase.Idle
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                publish(currentAnswer(), done = true); _phase.value = Phase.Idle; throw e
            } catch (e: Exception) {
                Log.w(TAG, "ask failed", e)
                publish(currentAnswer(), done = true)
                _phase.value = Phase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Stops the answer in progress (the model stays loaded for a follow-up). */
    fun cancel() { session?.cancel(); job?.cancel(); job = null }

    /** Forgets the conversation and frees the reader model (the sheet closed). */
    fun release() {
        cancel()
        _turns.value = emptyList(); nextK = 1; convLang = null
        _phase.value = Phase.Idle
        scope.launch { mutex.withLock { close() } }
    }

    /** Frees the reader model under memory pressure, after any answer in progress; the next
     *  question reloads it (it only loses the prompt-prefix reuse). */
    fun trim() {
        scope.launch { mutex.withLock { if (engine != null) { close(); Log.i(TAG, "reader unloaded (memory pressure)") } } }
    }

    fun reset() {
        cancel(); _turns.value = emptyList(); nextK = 1; convLang = null; _phase.value = Phase.Idle
        // Under the lock, after the cancelled answer has left the model: resetting the session from
        // the caller's thread raced the native generate still unwinding on the worker.
        scope.launch { mutex.withLock { session?.reset(); session?.resume() } }
    }

    private fun currentAnswer() = _turns.value.lastOrNull()?.answer ?: ""

    private fun publish(answer: String, done: Boolean) {
        val ts = _turns.value
        if (ts.isEmpty()) return
        _turns.value = ts.dropLast(1) + ts.last().copy(answer = answer, done = done)
    }

    /** Hybrid retrieval: dense (when the embedder is there) and keyword passages, meeting routing
     *  ([routeMeetings]), [fuse], then [select] with [retrieval]. */
    internal suspend fun retrieve(app: Context, entries: List<SessionLibrary.Entry>, question: String): List<Excerpt> {
        val p = retrieval
        LibrarySearch.refresh(app, entries)
        val depth = if (p.routeMeetings > 0) maxOf(p.perMeeting, p.routeDepth) else p.perMeeting
        val qv = EmbedHost.use(app, 2) { it.embed(Embedder.Prompts.query(question)) }
        val dense = if (qv != null) LibrarySearch.dense(app, entries, qv, maxPassages = depth, snippetChars = 1200) else emptyList()
        val keyword = LibrarySearch.keyword(app, entries, question, maxPassages = depth, snippetChars = 1200)
        // "What did Mark say": the meetings Mark is linked in are routed first, and his lines get the
        // note bonus (retrieval only: the excerpts already carry "Mark:", the prompt is unchanged).
        val named = studio.primeminutes.core.people.PeopleRouting.mentioned(
            question, studio.primeminutes.core.people.PeopleStore.doc.value, studio.primeminutes.core.people.PeopleStore.fold(app))
        val personMeetings = studio.primeminutes.core.people.PeopleRouting.meetingsOf(
            if (named.isEmpty()) emptyMap() else studio.primeminutes.core.people.PeopleStore.allLinks(app), named)
        val routed = personMeetings.keys.takeIf { it.isNotEmpty() }?.let { ids ->
            routeMeetings(dense.filter { it.entry.id in ids }, keyword.filter { it.entry.id in ids }, maxOf(p.routeMeetings, 1)).ifEmpty { null }
        } ?: routeMeetings(dense, keyword, p.routeMeetings)
        // The other meetings keep perMeeting candidates per list (the passages are in score order).
        fun trim(hs: List<LibrarySearch.Hit>) = hs.map { h -> if (h.entry.id in routed) h else h.copy(passages = h.passages.take(p.perMeeting)) }
        val fused = fuse(trim(dense), trim(keyword), p.rrfK) { id, sid -> sid != null && personMeetings[id]?.contains(sid) == true }
        val ranked = fused.filter { it.entry.id in routed } + fused.filter { it.entry.id !in routed }
        val t = mutex.withLock { tok ?: tokenizer(app) }
        val src = object : Transcripts {
            override fun chunks(e: SessionLibrary.Entry) = LibrarySearch.chunkRanges(e)
            override fun span(e: SessionLibrary.Entry, a: Int, b: Int) = LibrarySearch.span(e, a, b)?.let { x ->
                Excerpt(e, x.timeSec.toInt(), x.snippet, 0f, x.timeSec, a = x.uttIndex, b = x.uttEnd)
            }
        }
        if (routed.isNotEmpty()) Log.d(TAG, "routed to ${routed.joinToString()}" + if (named.isNotEmpty()) " (people ${named.size})" else "")
        return withContext(Dispatchers.Default) { select(ranked, p, { t.encode(it).size }, src) }
    }

    /** The excerpts as the reader sees them. Outside Chinese, amounts are written in digits (ChineseAmounts). */
    internal fun render(xs: List<Excerpt>, lang: String): String = buildString {
        if (xs.isEmpty()) { append(card().ask.noExcerpts + "\n"); return@buildString }
        xs.forEachIndexed { i, x ->
            append("[[${x.k}]] "); append(x.entry.title ?: ""); append(" · "); append(ReaderProtocol.formatTs(x.sec))
            append('\n'); append(x.text.trim().let { if (lang.startsWith("zh")) it else ChineseAmounts.toDigits(it) }); append("\n\n")
        }
    }

    /** The card's system prompt for a language tag (Simplified Chinese, Traditional Chinese or English). */
    private fun system(lang: String): String = card().ask.system(answerKey(lang))

    /** The card's answer-language key (`zh-Hans`, `zh-Hant`, `en`) of a language tag. */
    internal fun answerKey(lang: String): String = when {
        lang.startsWith("zh") && (lang.contains("Hans") || lang.endsWith("CN") || lang.endsWith("SG")) -> "zh-Hans"
        lang.startsWith("zh") -> "zh-Hant"
        else -> "en"
    }

    /** The answer follows the QUESTION's language ([MeetingLanguage] on the question: an English
     *  question on a Chinese interface is answered in English); a question too short or too mixed to
     *  tell, or Chinese on a Chinese interface, follows the interface (and its script). */
    internal fun answerLanguage(question: String, uiLanguage: String): String {
        val q = MeetingLanguage.detect(listOf(question), MeetingLanguage.QUESTION_UNITS)
        return when {
            q == null -> uiLanguage
            q == "zh" && uiLanguage.startsWith("zh") -> uiLanguage
            q == "zh" -> "zh-TW"
            else -> q
        }
    }

    /** The question as the model reads it in a conversation whose system prompt is [systemLang]'s:
     *  followed by the card's word for the answer's language when that differs (`ask.answer_in`). */
    internal fun asked(card: ModelCard, question: String, uiLanguage: String, systemLang: String): String {
        val want = answerKey(answerLanguage(question, uiLanguage))
        val tail = if (want != answerKey(systemLang)) card.ask.answerIn[want].orEmpty() else ""
        return question.trim() + tail
    }

    /** The card of the model loaded for Ask (else the chosen one). */
    private fun card() = LlmRegistry.byId(modelId ?: studio.primeminutes.core.config.TranscriptionConfig.Holder.config.llmModelId).card

    private fun tokenizer(app: Context): SpTokenizer {
        tok?.let { return it }
        val spec = LlmRegistry.byId(studio.primeminutes.core.config.TranscriptionConfig.Holder.config.llmModelId)
        return SpTokenizer.load(ModelManager(app).llmTokenizer(spec).path).also { tok = it }
    }

    /** Loads the reader model (CPU, the XNNPACK weight cache the reader built). */
    private fun open(app: Context): MfaSession {
        val spec = LlmRegistry.byId(studio.primeminutes.core.config.TranscriptionConfig.Holder.config.llmModelId)
        session?.takeIf { modelId == spec.id }?.let { return it }
        close()
        val mm = ModelManager(app)
        check(mm.llmReady(spec)) { "reader model not downloaded" }
        val dir = mm.llmDir(spec)
        val t0 = System.nanoTime()
        // The engine wants the directory of the fused graph (mobile-v1/mfa), the cache sits at the spec root.
        val e = MfaEngine.load(
            File(dir, spec.mainFile).parentFile!!.path, ctx = spec.maxCtx, threads = HwInfo.threads(app),
            weightCache = File(dir, ModelManager.WEIGHT_CACHE).path, stopIds = spec.template.stopIds,
        )
        Log.i(TAG, "reader loaded for Ask in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val t = tokenizer(app)
        return MfaSession(e, t, spec.sampler.topK, spec.sampler.topP, template = spec.template).also { engine = e; session = it; modelId = spec.id }
    }

    private fun close() {
        session = null; modelId = null
        engine?.close(); engine = null
        tok?.close(); tok = null
    }
}
