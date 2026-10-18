package studio.primeminutes.core.asr

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.primeminutes.core.events.TranscriptEvent
import java.io.File

/** On-disk paths of the two GGUFs the engine loads (see ModelManager.asrFiles). */
data class NemoModelFiles(val xasr: File, val diar: File)

/**
 * Streaming ASR + diarization in one pass — nemo-x-asr-diarizer (app/src/main/cpp/nemo): X-ASR
 * (streaming Zipformer2 transducer, zh + en) and Nemotron-3 diarization (streaming Sortformer) on
 * one audio timeline, each word tagged with the speaker whose turn covers it.
 *
 * Words appear ~0.4 s after they are spoken; speaker turns are committed ~5 s behind the audio, so
 * labels near the live edge are provisional. Every emission is a replace-all
 * [TranscriptEvent.UtteranceSnapshot] whose `stable` prefix will not change until the final one.
 */
/** [speakerDelaySec]: how far behind the audio a line must be before the live view freezes it with
 *  its speaker (TranscriptionConfig.speakerDelaySec). Longer = more precise live speaker tags. */
class NemoStreamEngine(
    files: NemoModelFiles,
    threads: Int,
    speakerDelaySec: Int = 15,
    /** "ggml" (default, the shipped path) or "litert_cpu" (bench, CPU only). */
    backend: String = "ggml",
    /** LiteRT graphs, read only when [backend] is "litert_cpu". */
    xasrTflite: File? = null,
    diarTflite: File? = null,
    /** Pre-encode [512,1024] fp32 matrix, read only when [backend] is "litert_cpu". */
    diarPreF32: File? = null,
    /** How far a token's emission frame runs behind its audio (model-specific: AsrVariant.tokenOffsetMs). */
    tokenOffsetMs: Double = 300.0,
) : LiveAsrEngine {

    private var handle: Long = NemoNative.nativeCreate(
        files.xasr.path, files.diar.path, threads, speakerDelaySec.toDouble(),
        backend, xasrTflite?.path.orEmpty(), diarTflite?.path.orEmpty(), diarPreF32?.path.orEmpty(), tokenOffsetMs,
    )
        .also { check(it != 0L) { "nemo engine failed to load ${files.xasr.name} / ${files.diar.name}" } }

    /** Distinct speakers in the final snapshot; null until the stream has finished. */
    override var speakerCount: Int? = null
        private set

    /**
     * Feed [chunks] (16 kHz mono float) and emit a transcript snapshot every [LIVE_EVERY_SEC] of audio.
     * Each update re-attributes only the unsettled tail (native `Engine::live`), so its cost stays flat
     * however long the recording runs; segments that can no longer change are frozen once and reused.
     * The last snapshot, after end of input, is the full final attribution.
     */
    override fun transcribeLive(chunks: Flow<FloatArray>): Flow<TranscriptEvent> = flow {
        val frozen = ArrayList<TranscriptEvent.Utterance>()
        var lastTail: List<TranscriptEvent.Utterance> = emptyList()
        var nextAt = LIVE_EVERY_SEC
        chunks.collect { c ->
            check(NemoNative.nativePush(handle, c, c.size)) { "nemo push failed" }
            val fed = NemoNative.nativeFedSec(handle)
            if (fed >= nextAt) {
                nextAt = fed + LIVE_EVERY_SEC
                val (newlyFrozen, tail) = parseLive(NemoNative.nativeLive(handle), firstIndex = frozen.size)
                frozen += newlyFrozen
                val reTail = tail.mapIndexed { i, u -> u.copy(index = frozen.size + i) }
                if (newlyFrozen.isNotEmpty() || reTail != lastTail) {
                    lastTail = reTail
                    emit(TranscriptEvent.UtteranceSnapshot(frozen + reTail, stable = frozen.size))
                }
            }
        }
        val final = parseSegments(NemoNative.nativeFinish(handle) ?: error("nemo finish failed"))
        speakerCount = final.mapNotNull { it.speaker }.distinct().size.takeIf { it > 0 }
        emit(TranscriptEvent.UtteranceSnapshot(final, stable = final.size))
    }

    /** Timing of the stream; call after [transcribeLive] has finished. */
    fun stats(): AsrStats {
        val j = org.json.JSONObject(NemoNative.nativeStats(handle))
        return AsrStats(
            audioSec = j.getDouble("audio_s"), wallSec = j.getDouble("wall_s"), loadSec = j.getDouble("load_s"),
            asrSec = j.getDouble("asr_s"), diarSec = j.getDouble("diar_s"),
            firstPartialSec = j.getDouble("first_partial_s"), pieceP95Ms = j.getDouble("piece_p95_ms"),
            pieceMaxMs = j.getDouble("piece_max_ms"), speakers = j.getInt("speakers"), turns = j.getInt("turns"),
            peakRssMb = j.getDouble("peak_rss_mb"),
        )
    }

    override fun close() {
        if (handle != 0L) { NemoNative.nativeFree(handle); handle = 0L }
    }

    companion object {
        /** Audio between live updates. */
        const val LIVE_EVERY_SEC = 0.5

        /** Split a `nativeLive` result (newly frozen segments, GS, tail) and parse both halves;
         *  indices of the frozen ones continue from [firstIndex]. */
        fun parseLive(raw: String, firstIndex: Int): Pair<List<TranscriptEvent.Utterance>, List<TranscriptEvent.Utterance>> {
            val gs = raw.indexOf('\u001d')
            val frozen = parseSegments(if (gs < 0) raw else raw.substring(0, gs), firstIndex)
            val tail = if (gs < 0) emptyList() else parseSegments(raw.substring(gs + 1))
            return frozen to tail
        }

        /** Decode the JNI segment encoding (see nemo_jni.cpp) into utterances. */
        fun parseSegments(raw: String, firstIndex: Int = 0): List<TranscriptEvent.Utterance> {
            val out = ArrayList<TranscriptEvent.Utterance>()
            for (rec in raw.split('\u001e')) {
                if (rec.isEmpty()) continue
                val f = rec.split('\u001f', limit = 5)
                if (f.size < 4) continue
                val text = AsrEngine.cleanTranscript(f[3]).trim()
                if (text.isEmpty()) continue
                val start = f[1].toDouble()
                // Optional 5th field (final pass): each raw codepoint's start, ms after start -> per-token times.
                val times = f.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { ms ->
                    runCatching { ms.split(',').map { start + it.toLong() / 1000.0 } }.getOrNull()
                }?.let { TokenTimes.fromCodepoints(f[3], it, text) }
                out += TranscriptEvent.Utterance(
                    index = firstIndex + out.size,
                    text = text,
                    startSec = start,
                    endSec = f[2].toDouble(),
                    speaker = f[0].toInt().takeIf { it >= 0 },
                    tokenTimes = times?.let { TokenTimes.relative(it, start) },
                )
            }
            return LongUtteranceSplitter.split(out).mapIndexed { i, u -> u.copy(index = firstIndex + i) }
        }
    }
}

/** JNI surface of libprimeminutes-nemo.so. */
internal object NemoNative {
    init { System.loadLibrary("primeminutes-nemo") }

    @JvmStatic external fun nativeCreate(
        xasr: String, diar: String, threads: Int, settleSec: Double,
        backend: String, xasrTflite: String, diarTflite: String, diarPreF32: String, tokenOffsetMs: Double,
    ): Long
    @JvmStatic external fun nativePush(handle: Long, pcm: FloatArray, n: Int): Boolean
    @JvmStatic external fun nativeLive(handle: Long): String
    @JvmStatic external fun nativeFinish(handle: Long): String?
    @JvmStatic external fun nativeFedSec(handle: Long): Double
    @JvmStatic external fun nativeStats(handle: Long): String
    @JvmStatic external fun nativeFree(handle: Long)
}

/** Timing of one finished stream (native `Stats`): seconds unless the name says otherwise. */
data class AsrStats(
    val audioSec: Double, val wallSec: Double, val loadSec: Double,
    /** Time inside the X-ASR graph and the diarizer graph, summed over the stream. */
    val asrSec: Double, val diarSec: Double,
    val firstPartialSec: Double, val pieceP95Ms: Double, val pieceMaxMs: Double,
    val speakers: Int, val turns: Int, val peakRssMb: Double,
) {
    val rtf: Double get() = if (audioSec > 0) wallSec / audioSec else 0.0
}

/** LiteRT graphs of the same models (the `.tflite` pair, read by the `litert_cpu` backend). */
data class NemoLitertFiles(val xasr: File, val diar: File, val preEncode: File? = null)

/** Everything the engine loads for one recognizer: host weights, graphs, the token offset, the provenance id, and the
 *  registry variant ([variant] null = the pre-variant 480 ms pair still installed). */
data class AsrEngineFiles(
    val host: NemoModelFiles, val litert: NemoLitertFiles, val tokenOffsetMs: Double, val modelId: String,
    val variant: studio.primeminutes.core.models.AsrVariant?,
)
