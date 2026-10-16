package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.primeminutes.core.asr.LiveAsrEngine
import studio.primeminutes.core.audio.WavWriter
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File
import java.util.Collections

/**
 * Robolectric here runs without the merged app resources: strings read back as "#<id>" (the same
 * convention as SessionReducerTest), everything else is the application context.
 */
@Suppress("DEPRECATION")
internal fun stringlessContext(base: Context): Context = object : android.content.ContextWrapper(base) {
    private val res = object : android.content.res.Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
        override fun getString(id: Int) = "#$id"
        override fun getString(id: Int, vararg formatArgs: Any?) = "#$id"
        override fun getText(id: Int): CharSequence = "#$id"
    }
    override fun getResources(): android.content.res.Resources = res
    override fun getApplicationContext(): Context = this
}

/** A [ReaderWorker] that queues tasks but never reads them: its loop is launched on a dead scope. */
internal fun inertReader(ctx: Context) = ReaderWorker(object : ReaderWorker.Host {
    override val context = ctx
    override val scope = CoroutineScope(Job().apply { cancel() })
    override suspend fun emitEvent(e: TranscriptEvent) {}
    override suspend fun emitDownloadStatus(text: String) {}
    override fun updateNotification(text: String) {}
    override fun notifyItemFailed(title: String) {}
    override fun notifySessionReady(title: String) {}
    override fun dlBegin(key: String, totalBytes: Long) {}
    override fun dlEnd(key: String) {}
    override fun reportDownload(gen: Int, key: String, msgRes: Int, frac: Float) {}
    override fun downloadsDone(gen: Int) {}
    override fun transcriptFingerprint(cfg: TranscriptionConfig) = FakePipelineHost.FP
    override fun outputConverter(cfg: TranscriptionConfig): OpenCcConverter? = null
    override fun threads() = 1
    override fun onReaderIdle() {}
})

/** One utterance per [perSec] seconds of fed audio; the last one stays provisional until the end. */
internal class ScriptedEngine(
    private val speakers: Int = 2,
    private val perSec: Double = 0.5,
    /** Pause per fed block, so a file run lasts long enough to be stopped mid-ASR. */
    private val blockDelayMs: Long = 0,
) : LiveAsrEngine {
    override var speakerCount: Int? = null
    @Volatile var closed = false
    @Volatile var fedSamples = 0L

    private fun utts(sec: Double) = (0 until (sec / perSec).toInt()).map { i ->
        TranscriptEvent.Utterance(i, "line $i", i * perSec, (i + 1) * perSec, i % speakers)
    }

    override fun transcribeLive(chunks: Flow<FloatArray>): Flow<TranscriptEvent> = flow {
        var last = 0
        chunks.collect { c ->
            if (blockDelayMs > 0) kotlinx.coroutines.delay(blockDelayMs)
            fedSamples += c.size
            val u = utts(fedSamples / 16_000.0)
            if (u.size > last) { last = u.size; emit(TranscriptEvent.UtteranceSnapshot(u, stable = u.size - 1)) }
        }
        val final = utts(fedSamples / 16_000.0)
        speakerCount = speakers
        emit(TranscriptEvent.UtteranceSnapshot(final, stable = final.size))
    }

    override fun close() { closed = true }
}

/** Records what the pipelines ask of the service. */
internal class FakePipelineHost(
    override val context: Context,
    override val scope: CoroutineScope,
    val engine: LiveAsrEngine = ScriptedEngine(),
    private val durationSec: Double = 0.0,
) : PipelineHost {
    override val reader = inertReader(context)
    val events: MutableList<TranscriptEvent> = Collections.synchronizedList(ArrayList())
    val foreground: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
    var enginesCreated = 0

    override suspend fun emitEvent(e: TranscriptEvent) { events += e }
    override fun updateNotification(text: String) {}
    override fun startForegroundTyped(recording: Boolean, text: String) { foreground += recording }
    override fun transcriptFingerprint(cfg: TranscriptionConfig) = FP
    override fun outputConverter(cfg: TranscriptionConfig): OpenCcConverter? = null
    override fun transcriptConverter(): OpenCcConverter? = null
    override suspend fun ensureEngineModels(models: ModelManager) {}
    override fun createEngine(models: ModelManager): LiveAsrEngine { enginesCreated++; return engine }
    override fun probeDurationSec(uri: Uri) = durationSec

    inline fun <reified T : TranscriptEvent> of(): List<T> = synchronized(events) { events.filterIsInstance<T>() }

    companion object { const val FP = "fp" }
}

/** A canonical 16 kHz mono work WAV with a little signal in it. */
internal fun testWav(file: File, seconds: Double): File {
    file.parentFile?.mkdirs()
    val n = (seconds * 16_000).toInt()
    WavWriter(file).use { w -> w.write(FloatArray(n) { (if (it % 40 < 20) 0.2f else -0.2f) }) }
    return file
}
