package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import studio.primeminutes.core.asr.LiveAsrEngine
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.text.OpenCcConverter

/** One tag for the whole abort path (service stop handler and pipelines), so a device run can be
 *  read with a single filter. */
internal const val STOP_TAG = "primeminutes-stop"

/**
 * What the import and recording pipelines ([ImportPipeline], [RecordingPipeline]) and the queue
 * drain ([QueueRunner]) need from [TranscriptionService], the Android lifecycle shell that runs
 * them: its context and scope, the AI notes worker, the event bus, the foreground and failure
 * notifications, and the speech engine's models.
 * Shaped like [ReaderWorker.Host]; the service implements both.
 */
internal interface PipelineHost {
    val context: Context
    /** The service's lifecycle scope: the recording's mic job runs in it, outside the run's job. */
    val scope: CoroutineScope
    /** The AI notes worker, shared by every run. */
    val reader: ReaderWorker
    /** Emit a UI event stamped with the current coroutine's run generation (also drives the notification). */
    suspend fun emitEvent(e: TranscriptEvent)
    fun updateNotification(text: String)
    /** Start/refresh the foreground service: microphone type while recording, else data sync. */
    fun startForegroundTyped(recording: Boolean, text: String)
    fun transcriptFingerprint(cfg: TranscriptionConfig): String
    /** OpenCC for generated text (summary, title, actions). */
    fun outputConverter(cfg: TranscriptionConfig): OpenCcConverter?
    /** OpenCC for the transcript (conservative, phonetic). */
    fun transcriptConverter(): OpenCcConverter?
    /** Download the engine's models when missing (progress on the current run's bar). */
    suspend fun ensureEngineModels(models: ModelManager)
    /** The streaming ASR + diarization engine. */
    fun createEngine(models: ModelManager): LiveAsrEngine
    /** Total media duration in seconds; 0 if unknown. */
    fun probeDurationSec(uri: Uri): Double
    /** Dismissible notification that a background queue item failed to process. */
    fun notifyItemFailed(title: String)
}
