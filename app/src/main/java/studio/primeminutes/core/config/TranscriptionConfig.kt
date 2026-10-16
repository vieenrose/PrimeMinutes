package studio.primeminutes.core.config

import studio.primeminutes.core.models.LlmRegistry

/**
 * User-facing pipeline configuration — the Android equivalent of the original web app's
 * ASR / Diarization / LLM / Summarization settings sidebar. Held in a process-wide
 * [Holder] so the Activity can set it before starting the foreground service (same process).
 */
data class TranscriptionConfig(
    // --- ASR ---
    val asrBackend: String = "nemo",   // provenance id (AsrBackend); nemo is the only engine
    // Provenance written into every session: the LiteRT pair the engine actually loads
    // (ModelManager.LITERT_*_FILE). Not a setting, so ConfigStore no longer persists it — a
    // stored value would outlive a backend change (sessions kept naming the GGUF pair).
    val asrModelId: String = ASR_MODEL_ID,

    // --- Diarization ---
    // Live view (recording booth): seconds a line waits before its speaker tag is shown. The
    // diarizer revises recent labels for tens of seconds, so waiting longer trades latency for
    // precision — live-vs-final speaker agreement on 4 meetings: 93.0% at 8 s, 94.7% at 15 s,
    // 95.8% at 30 s (tools/nemo-eval). Text itself is always shown immediately. The final
    // transcript does not depend on this.
    val speakerDelaySec: Int = 15,
    /** Experimental: show the model's action items under the summary (off by default — not reliable enough). */
    val showActionItems: Boolean = false,
    /** Ask — a question over the whole library, answered by the reader model from the passages the
     *  search ranks highest (plan §6). On by default: the Ask button shows once the AI notes model is
     *  downloaded (it was an Experimental toggle, off, that few found). */
    val askEnabled: Boolean = true,

    // --- Summarization ---
    // The actually-used summary model. MUST track LlmRegistry.DEFAULT_ID — hardcoding it here (it was
    // pinned to gemma) silently kept new installs on the old default even after the registry's default
    // changed, so the "recommended" model in Settings and the model that actually ran disagreed.
    val llmModelId: String = LlmRegistry.DEFAULT_ID,
    // Target language for ALL out-coming text — summary, title, transcript, and detected speaker names
    // Han script every Chinese text is normalized to (a [SummaryScript] id). Summaries are always
    // in the RECORDING's language — the translate-as-you-summarize option was removed because it
    // degraded a 0.8B summarizer's output. This is a post-hoc OpenCC mapping, not a model task.
    val summaryScript: String = "zh-Hant",
) {
    companion object {
        const val ASR_MODEL_ID = "xasr-stream-q8-octav+sortformer-int8-f190"
        const val SPEAKER_DELAY_MIN = 5
        const val SPEAKER_DELAY_MAX = 30
    }

    object Holder {
        @Volatile var config: TranscriptionConfig = TranscriptionConfig()
    }

}
