package studio.primeminutes.core.models

/**
 * The speech recognizer's streaming variants: X-ASR zh-en punct int8 (sherpa-onnx 2026-06-05), one checkpoint exported
 * with four chunk sizes. A longer chunk sees more audio before it commits words: more accurate, slower to show them.
 * The user picks one in Settings ([studio.primeminutes.core.config.TranscriptionConfig.asrLatencyMs]).
 *
 * Model specifics live here, not in the engine: the engine reads the chunk geometry from the graph, and the token
 * timestamp offset ([tokenOffsetMs], how far a token's emission frame runs behind its audio) comes from this entry.
 */
data class AsrVariant(
    /** Chunk length in ms: the setting's value and the variant's id. */
    val latencyMs: Int,
    /** LiteRT encoder graph (int8 weights, dynamic wi8 OCTAV), in its own folder [dirName]. */
    val tflite: AsrPin,
    /** Token emission offset for the transcript's speaker attribution (engine Config.token_offset_ms). */
    val tokenOffsetMs: Double,
) {
    val dirName: String get() = "xasr-punct-${latencyMs}ms"
    /** Provenance written into each session (which recognizer produced the transcript). */
    val modelId: String get() = "xasr-punct-int8-${latencyMs}ms+sortformer-int8-f190"
}

data class AsrPin(val file: String, val url: String, val bytes: Long, val sha256: String)

object AsrRegistry {
    private const val REPO = "https://huggingface.co/Luigi/primeminutes-nemo-litert/resolve"
    /** The Luigi/primeminutes-nemo-litert commit holding the four punct graphs and the shared host weights. */
    const val REV = "7ac87f4bb4239e92cb8d37f64db6c544e8640a06"

    private fun pin(file: String, bytes: Long, sha: String) = AsrPin(file, "$REPO/$REV/$file", bytes, sha)

    /** Decoder / joiner / tokens / fbank of the punct checkpoint: identical for the four chunk sizes. */
    val HOST: AsrPin = pin("xasr_punct_host.bin", 16_497_008L, "2fdfc8893a3e4f4308fd2dda5221d9b185e4bd6b3652c72659afa99d4bc69a92")

    val ALL: List<AsrVariant> = listOf(
        AsrVariant(160, pin("xasr_punct_160ms_q8.tflite", 152_257_712L, "2fe0e2c5a7a8601b50e7d9883ba178bb17aa5ba06940c6a0ff7dbe6b039cfc32"), 300.0),
        AsrVariant(480, pin("xasr_punct_480ms_q8.tflite", 152_697_936L, "311b18d7bdf4741151af8c1ad240108fc5ef9f907c6c3a4da2edd07257f639b2"), 300.0),
        AsrVariant(960, pin("xasr_punct_960ms_q8.tflite", 153_370_208L, "7ca126a3b832c385eda2f1b23d27154bad3992f24fb7cff304b318a81d9c0883"), 300.0),
        AsrVariant(1920, pin("xasr_punct_1920ms_q8.tflite", 154_783_344L, "cb739c2113e3347aba1761b6c190c9ae6a9e374e77c0519da61c81568db87e55"), 300.0),
    )

    const val DEFAULT_MS = 1920
    val LATENCIES: List<Int> get() = ALL.map { it.latencyMs }

    /** The variant for a stored setting; an unknown value (a removed option) reads as the default. */
    fun byLatency(ms: Int): AsrVariant = ALL.firstOrNull { it.latencyMs == ms } ?: ALL.first { it.latencyMs == DEFAULT_MS }

    /** What actually runs for the chosen [target]: the target once installed; until then the installed variant
     *  closest to it on the slider (a switch never leaves the app without a recognizer); else null ([legacy] decides). */
    fun runnable(target: Int, installed: (AsrVariant) -> Boolean): AsrVariant? {
        val t = byLatency(target)
        if (installed(t)) return t
        // Closest by slider position; on a tie the more accurate (longer) one.
        val i = ALL.indexOf(t)
        return ALL.filter(installed).minWithOrNull(compareBy<AsrVariant>({ kotlin.math.abs(ALL.indexOf(it) - i) }, { -it.latencyMs }))
    }
}
