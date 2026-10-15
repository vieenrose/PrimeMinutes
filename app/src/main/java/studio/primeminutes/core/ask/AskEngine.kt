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
import studio.primeminutes.service.TranscriptionService
import studio.primeminutes.BuildConfig
import java.io.File

/**
 * Ask (RAG, experimental — plan §6): a question over the whole library, answered by the reader
 * model from the passages the semantic index ranks highest. The passages carry `[[entryId@sec]]`
 * tags the model is told to cite; the sheet renders them as "title · m:ss" and opens the meeting
 * there.
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
    private const val ANSWER_TOKENS = 320
    private const val NOTE_BONUS = 0.05f      // notes are denser than raw chunks: prefer them at equal score

    sealed class Phase {
        object Idle : Phase()
        object Busy : Phase()                    // refused: a job is running
        object Retrieving : Phase()
        object Loading : Phase()                 // reader model loading (seconds)
        object Answering : Phase()
        data class Failed(val error: String) : Phase()
    }

    /** One excerpt handed to the model. */
    data class Excerpt(val entry: SessionLibrary.Entry, val sec: Int, val text: String, val score: Float)

    data class Turn(val question: String, val answer: String, val excerpts: List<Excerpt>, val done: Boolean)

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

    /** The loaded-model holder: the embedder of the search index must be present. */
    fun available(context: Context): Boolean =
        EmbedHost.ready(context) && ModelManager(context).llmReady(LlmRegistry.byId(
            studio.primeminutes.core.config.TranscriptionConfig.Holder.config.llmModelId))

    fun busy(): Boolean =
        TranscriptionService.recordingJobActive || TranscriptionService.pipelineActive || TranscriptionService.readerActive

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
                _phase.value = Phase.Retrieving
                val excerpts = retrieve(app, entries, question)
                _turns.value = _turns.value + Turn(question, "", excerpts, false)
                _phase.value = Phase.Loading
                // The model is opened, prompted and run under the lock: release() cannot close the
                // engine under a running generate (cancel() makes that generate return first).
                mutex.withLock {
                    val s = open(app)
                    s.resume()
                    _phase.value = Phase.Answering
                    val sb = StringBuilder()
                    val first = _turns.value.size == 1
                    val prompt = buildString {
                        if (first) { append(ReaderProtocol.P0); append(system(uiLanguage)); append(ReaderProtocol.P1) }
                        else append(ReaderProtocol.P3)
                        append(render(excerpts, uiLanguage)); append("\n\n"); append(question.trim()); append(ReaderProtocol.P2)
                    }
                    if (s.append(s.tokenize(prompt, special = true)) < 0) {
                        // The conversation outgrew the cache: start a fresh one with this question only.
                        s.reset()
                        val fresh = ReaderProtocol.P0 + system(uiLanguage) + ReaderProtocol.P1 + render(excerpts, uiLanguage) + "\n\n" + question.trim() + ReaderProtocol.P2
                        if (s.append(s.tokenize(fresh, special = true)) < 0) error("prompt does not fit")
                    }
                    val t0 = System.nanoTime()
                    var tFirst = 0L
                    var cut = false
                    s.generateContinue(ANSWER_TOKENS, "", LlmRegistry.byId(modelId!!).sampler.temp) { piece ->
                        if (busy()) { cut = true; s.cancel() }   // a job started: the pipeline wins
                        if (tFirst == 0L) tFirst = System.nanoTime()
                        sb.append(piece); publish(sb.toString(), done = false)
                    }
                    val st = engine?.lastStats
                    Log.i(TAG, "answered in ${(System.nanoTime() - t0) / 1_000_000} ms: first token ${(tFirst - t0) / 1_000_000} ms, prefill ${st?.prefilled} tok ${st?.prefillSec}s, ${st?.generated} tok ${st?.decodeSec}s${if (cut) ", cut by a job" else ""}")
                    if (BuildConfig.DEBUG) {
                        // DEV: the answer and the excerpt tags, so a device run can be checked from logcat.
                        Log.d(TAG, "excerpts: " + excerpts.joinToString(" ") { "[[${it.entry.id}@${it.sec}]]" })
                        if (System.getProperty("vox.askdump") != null || android.util.Log.isLoggable("primeminutes-askdump", Log.DEBUG))
                            render(excerpts, uiLanguage).lines().forEach { Log.d("primeminutes-askdump", it) }
                        Log.d(TAG, "answer: " + sb.toString().replace("\n", " ⏎ "))
                    }
                    publish(sb.toString().trim(), done = true)
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
        _turns.value = emptyList()
        _phase.value = Phase.Idle
        scope.launch { mutex.withLock { close() } }
    }

    fun reset() { cancel(); _turns.value = emptyList(); _phase.value = Phase.Idle; session?.reset(); session?.resume() }

    private fun currentAnswer() = _turns.value.lastOrNull()?.answer ?: ""

    private fun publish(answer: String, done: Boolean) {
        val ts = _turns.value
        if (ts.isEmpty()) return
        _turns.value = ts.dropLast(1) + ts.last().copy(answer = answer, done = done)
    }

    /** Dense retrieval: top passages across the library, notes first at equal score, ≤ [PASSAGE_TOKENS]. */
    private suspend fun retrieve(app: Context, entries: List<SessionLibrary.Entry>, question: String): List<Excerpt> {
        val qv = EmbedHost.use(app, 2) { it.embed(Embedder.Prompts.query(question)) } ?: return emptyList()
        LibrarySearch.refresh(app, entries)
        val hits = LibrarySearch.dense(app, entries, qv, maxPassages = 4, snippetChars = 1200)
        val all = hits.flatMap { h -> h.passages.filter { it.kind != SessionIndex.Kind.MEETING }.map { p ->
            Excerpt(h.entry, p.timeSec.toInt(), p.snippet, p.score + if (p.kind == SessionIndex.Kind.NOTE) NOTE_BONUS else 0f)
        } }.sortedByDescending { it.score }
        val out = ArrayList<Excerpt>()
        var tokens = 0
        val t = mutex.withLock { tok ?: tokenizer(app) }
        for (x in all) {
            if (out.size >= MAX_PASSAGES) break
            // A note and its transcript chunk can carry the same words: one excerpt is enough.
            if (out.any { it.entry.id == x.entry.id && it.text == x.text }) continue
            val n = withContext(Dispatchers.Default) { t.encode(x.text).size } + 24
            if (tokens + n > PASSAGE_TOKENS) continue
            tokens += n; out += x
        }
        return out
    }

    /** The excerpts as the reader sees them. Outside Chinese, amounts are written in digits (ChineseAmounts). */
    private fun render(xs: List<Excerpt>, lang: String): String = buildString {
        if (xs.isEmpty()) { append("(no excerpts)\n"); return@buildString }
        xs.forEachIndexed { i, x ->
            append("[[${x.entry.id}@${x.sec}]] "); append(x.entry.title ?: ""); append(" · "); append(ReaderProtocol.formatTs(x.sec))
            append('\n'); append(x.text.trim().let { if (lang.startsWith("zh")) it else ChineseAmounts.toDigits(it) }); append("\n\n")
        }
    }

    /** The answer follows the interface language (plan §6), whatever the question's. */
    private fun system(lang: String): String = when {
        lang.startsWith("zh") && (lang.contains("Hans") || lang.endsWith("CN") || lang.endsWith("SG")) ->
            "你是会议助理。只能根据下列会议摘录回答问题，不得加入摘录以外的信息。" +
            "每个论点后面附上出处标签，格式与摘录开头完全相同：[[id@秒]]。" +
            "问题的用词可能和摘录不同，请找意思相同的内容。只有在每段摘录都没有相关内容时，" +
            "才说「会议记录中没有提到」。回答请简短，使用简体中文。"
        lang.startsWith("zh") ->
            "你是會議助理。只能根據下列會議摘錄回答問題，不得加入摘錄以外的資訊。" +
            "每個論點後面附上出處標籤，格式與摘錄開頭完全相同：[[id@秒]]。" +
            "問題的用詞可能和摘錄不同，請找意思相同的內容。只有在每段摘錄都沒有相關內容時，" +
            "才說「會議記錄中沒有提到」。回答請簡短，使用繁體中文。"
        else ->
            "You are a meeting assistant. Answer only from the meeting excerpts below; never add facts " +
            "that are not in them. After each claim, cite its source tag exactly as it appears at the " +
            "start of the excerpt: [[id@seconds]]. If the excerpts do not contain the answer, say that " +
            "the meetings do not mention it. Be brief and answer in English. Do not add a currency " +
            "the excerpts do not name."
    }

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
            weightCache = File(dir, "weights.xnnpack_cache").path,
        )
        Log.i(TAG, "reader loaded for Ask in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val t = tokenizer(app)
        return MfaSession(e, t, spec.sampler.topK, spec.sampler.topP).also { engine = e; session = it; modelId = spec.id }
    }

    private fun close() {
        session = null; modelId = null
        engine?.close(); engine = null
        tok?.close(); tok = null
    }
}
