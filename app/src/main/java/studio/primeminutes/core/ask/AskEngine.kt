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
import studio.primeminutes.core.models.ModelManager
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
    private const val PASSAGE_TOKENS = 1500   // ≤ ~1 500 tokens of excerpts in the mobile 4k context
    private const val NOTE_BONUS = 0.05f      // notes are denser than raw chunks: prefer them at equal score
    private const val RRF_K = 60f

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
    data class Excerpt(val entry: SessionLibrary.Entry, val sec: Int, val text: String, val score: Float, val at: Double = sec.toDouble(), val k: Int = 0)

    /** [pool]: every excerpt of the conversation so far, this turn's included — what a citation may point to. */
    data class Turn(val question: String, val answer: String, val excerpts: List<Excerpt>, val done: Boolean, val pool: List<Excerpt> = excerpts)

    private val SHORT_TAG = Regex("""\[{1,2}(\d{1,3})]{1,2}""")

    /** The model's `[[k]]` tags rewritten `[[entryId@sec]]`; a number no excerpt carries is left for the sheet to drop. */
    fun expandTags(answer: String, pool: List<Excerpt>): String = SHORT_TAG.replace(answer) { m ->
        pool.firstOrNull { it.k == m.groupValues[1].toInt() }?.let { "[[${it.entry.id}@${it.sec}]]" } ?: m.value
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
    fun fuse(dense: List<LibrarySearch.Hit>, keyword: List<LibrarySearch.Hit>): List<Excerpt> {
        data class Key(val id: String, val kind: SessionIndex.Kind, val a: Int)
        val score = LinkedHashMap<Key, Float>()
        val first = HashMap<Key, Excerpt>()
        fun add(hits: List<LibrarySearch.Hit>, bonus: Boolean) {
            hits.flatMap { h -> h.passages.filter { it.kind != SessionIndex.Kind.MEETING }.map { h.entry to it } }
                .sortedByDescending { (_, p) -> p.score + if (bonus && p.kind == SessionIndex.Kind.NOTE) NOTE_BONUS else 0f }
                .forEachIndexed { rank, (e, p) ->
                    val key = Key(e.id, p.kind, p.uttIndex)
                    score[key] = (score[key] ?: 0f) + 1f / (RRF_K + rank + 1)
                    first.getOrPut(key) { Excerpt(e, p.timeSec.toInt(), p.snippet, 0f, p.timeSec) }
                }
        }
        add(dense, bonus = true)
        add(keyword, bonus = false)
        return score.entries.sortedByDescending { it.value }.map { (key, v) -> first.getValue(key).copy(score = v) }
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
                    val t = s.template
                    val prompt = buildString {
                        if (first) { append(t.p0); append(system(uiLanguage)); append(t.p1) }
                        else append(t.p3)
                        append(render(excerpts, uiLanguage)); append("\n\n"); append(question.trim()); append(t.p2)
                    }
                    val tPrompt = System.nanoTime()
                    val card = card()
                    val toks = s.tokenize(prompt, special = true)
                    // A follow-up that would leave no room for a whole answer, or that the cache refuses,
                    // starts a fresh conversation with this question only (the answer was cut short before).
                    if (!first && !followUpFits(s.seqLength(), toks.size, card.ask.answerTokens, engine?.context ?: 0)
                        || s.append(toks) < 0) {
                        Log.i(TAG, "prompt does not fit the conversation (${s.seqLength()} + ${toks.size} + ${card.ask.answerTokens}): fresh conversation")
                        s.reset()
                        val fresh = t.p0 + system(uiLanguage) + t.p1 + render(excerpts, uiLanguage) + "\n\n" + question.trim() + t.p2
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
                            render(excerpts, uiLanguage).lines().forEach { Log.d("primeminutes-askdump", it) }
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
        _turns.value = emptyList(); nextK = 1
        _phase.value = Phase.Idle
        scope.launch { mutex.withLock { close() } }
    }

    /** Frees the reader model under memory pressure, after any answer in progress; the next
     *  question reloads it (it only loses the prompt-prefix reuse). */
    fun trim() {
        scope.launch { mutex.withLock { if (engine != null) { close(); Log.i(TAG, "reader unloaded (memory pressure)") } } }
    }

    fun reset() {
        cancel(); _turns.value = emptyList(); nextK = 1; _phase.value = Phase.Idle
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

    /** Hybrid retrieval ([fuse]): dense (when the embedder is there) and keyword passages, ≤ [PASSAGE_TOKENS]. */
    private suspend fun retrieve(app: Context, entries: List<SessionLibrary.Entry>, question: String): List<Excerpt> {
        LibrarySearch.refresh(app, entries)
        val qv = EmbedHost.use(app, 2) { it.embed(Embedder.Prompts.query(question)) }
        val dense = if (qv != null) LibrarySearch.dense(app, entries, qv, maxPassages = 4, snippetChars = 1200) else emptyList()
        val keyword = LibrarySearch.keyword(app, entries, question, maxPassages = 4, snippetChars = 1200)
        val all = fuse(dense, keyword)
        val out = ArrayList<Excerpt>()
        var tokens = 0
        val t = mutex.withLock { tok ?: tokenizer(app) }
        for (x in all) {
            if (out.size >= MAX_PASSAGES) break
            // A note and its transcript chunk, or a meeting imported twice, can carry the same words: one excerpt is enough.
            if (out.any { it.text == x.text }) continue
            val n = withContext(Dispatchers.Default) { t.encode(x.text).size } + 24
            if (tokens + n > PASSAGE_TOKENS) continue
            tokens += n; out += x
        }
        return out
    }

    /** The excerpts as the reader sees them. Outside Chinese, amounts are written in digits (ChineseAmounts). */
    private fun render(xs: List<Excerpt>, lang: String): String = buildString {
        if (xs.isEmpty()) { append(card().ask.noExcerpts + "\n"); return@buildString }
        xs.forEachIndexed { i, x ->
            append("[[${x.k}]] "); append(x.entry.title ?: ""); append(" · "); append(ReaderProtocol.formatTs(x.sec))
            append('\n'); append(x.text.trim().let { if (lang.startsWith("zh")) it else ChineseAmounts.toDigits(it) }); append("\n\n")
        }
    }

    /** The answer follows the interface language (plan §6), whatever the question's: the card's
     *  system prompt for Simplified Chinese, Traditional Chinese or English. */
    private fun system(lang: String): String = card().ask.system(when {
        lang.startsWith("zh") && (lang.contains("Hans") || lang.endsWith("CN") || lang.endsWith("SG")) -> "zh-Hans"
        lang.startsWith("zh") -> "zh-Hant"
        else -> "en"
    })

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
