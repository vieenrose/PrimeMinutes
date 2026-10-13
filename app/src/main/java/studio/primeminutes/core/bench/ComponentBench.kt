package studio.primeminutes.core.bench

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import studio.primeminutes.core.asr.AsrStats
import studio.primeminutes.core.asr.NemoStreamEngine
import studio.primeminutes.core.audio.WavIo
import studio.primeminutes.core.hw.HwInfo
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.llm.MfaEngine
import studio.primeminutes.core.llm.MfaSession
import studio.primeminutes.core.llm.SpTokenizer
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelManager
import java.io.File
import kotlin.math.min

/**
 * In-app benchmark of the three AI components on this phone: the streaming ASR and diarizer (one
 * native engine, split by its own timers) and the summarizer (the reader's model, one fixed prompt).
 * Each runs alone, so their numbers do not include each other's load.
 */
object ComponentBench {
    /** 60 s of 16 kHz mono PCM16 from a meeting (EN2002a, 2:00–3:00), shipped with the app. */
    const val CLIP = "bench/clip60.wav"
    private const val BLOCK = 8000                 // 0.5 s at 16 kHz, the engine's push size
    private const val PROMPT_REPEATS = 40          // about 1,000 prompt tokens
    private const val DECODE_TOKENS = 128
    private const val SENTENCE =
        "The team reviewed the migration plan, agreed on the owner for each task, and noted the open risk on the device backend. "

    data class SummaryStats(
        val prefilled: Int, val prefillSec: Double,
        val generated: Int, val decodeSec: Double,
    ) {
        val prefillTps: Double get() = if (prefillSec > 0) prefilled / prefillSec else 0.0
        val decodeTps: Double get() = if (decodeSec > 0) generated / decodeSec else 0.0
    }

    /** One ASR + diarizer run over the clip, on the engine config the reader's transcription uses. */
    suspend fun asr(context: Context): AsrStats = withContext(Dispatchers.IO) {
        val pcm = readClip(context)
        val chunks = (pcm.indices step BLOCK).map { pcm.copyOfRange(it, min(it + BLOCK, pcm.size)) }
        // The engine's weight cache goes under TMPDIR; point it at our cache (see TranscriptionService.createEngine).
        runCatching { android.system.Os.setenv("TMPDIR", context.cacheDir.absolutePath, true) }
        val models = ModelManager(context)
        val lit = models.asrLitertFiles() ?: error("LiteRT ASR graphs missing")
        NemoStreamEngine(models.asrFiles(), HwInfo.threads(context), TranscriptionConfig.Holder.config.speakerDelaySec,
            backend = "litert_host", xasrTflite = lit.xasr, diarTflite = lit.diar, diarPreF32 = lit.preEncode).use { engine ->
            engine.transcribeLive(chunks.asFlow()).collect {}
            engine.stats()
        }
    }

    /** One summarizer run: prefill of a fixed prompt, then [DECODE_TOKENS] generated tokens. */
    suspend fun summarizer(context: Context): SummaryStats = withContext(Dispatchers.IO) {
        val (engine, tok) = loadSummarizer(context)
        engine.use { tok.use {
            val session = MfaSession(engine, tok)
            val prompt = "Summarize the following meeting notes.\n" + SENTENCE.repeat(PROMPT_REPEATS)
            session.reset()
            // The chat template as the reader builds it (ReaderLane.fitting): special tokens on the frame.
            session.append(session.tokenize("<bos><|turn>user\n", true) + session.tokenize(prompt, false) +
                session.tokenize("<turn|>\n<|turn>model\n", true))
            // append() prefills in its own generate call; generateContinue() decodes in the next one.
            val pre = checkNotNull(engine.lastStats) { "summarizer returned no prefill timing" }
            session.generateContinue(DECODE_TOKENS, "<turn|>", 0.0f) {}
            val dec = checkNotNull(engine.lastStats) { "summarizer returned no decode timing" }
            SummaryStats(pre.prefilled, pre.prefillSec, dec.generated, dec.decodeSec)
        } }
    }

    /** The reader's model on the CPU, loaded the way the reader loads it (see TranscriptionService). */
    private fun loadSummarizer(context: Context): Pair<MfaEngine, SpTokenizer> {
        val models = ModelManager(context)
        val spec = LlmRegistry.byId(TranscriptionConfig.Holder.config.llmModelId)
        val dir = models.llmDir(spec)
        val main = File(dir, spec.mainFile)
        check(main.exists()) { "summarizer model not downloaded" }
        // The reader loads from the main file's folder (its embedders sit beside it), not the model root.
        val engine = MfaEngine.load(
            main.parentFile!!.path, ctx = spec.maxCtx, threads = HwInfo.threads(context),
            weightCache = File(dir, "weights.xnnpack_cache").path, backend = 0, main = main.path,
        )
        val tok = SpTokenizer.load(File(dir, spec.tokenizerFile).path)
        return engine to tok
    }

    /** The clip as float samples in [-1, 1). */
    private fun readClip(context: Context): FloatArray {
        val bytes = context.assets.open(CLIP).use { it.readBytes() }
        val n = (bytes.size - WavIo.HEADER) / 2
        return FloatArray(n) { i ->
            val lo = bytes[WavIo.HEADER + 2 * i].toInt() and 0xff
            val hi = bytes[WavIo.HEADER + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }
}
