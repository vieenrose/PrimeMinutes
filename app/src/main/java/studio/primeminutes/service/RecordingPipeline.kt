package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.asr.AsrEngine
import studio.primeminutes.core.asr.SnapshotConverter
import studio.primeminutes.core.audio.AudioRecorder
import studio.primeminutes.core.audio.RecordingRecovery
import studio.primeminutes.core.audio.WavIo
import studio.primeminutes.core.audio.WavNormalizer
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.models.ModelManager
import java.io.File

/**
 * The live recording pipeline, split out of [TranscriptionService]: mic → streaming ASR + speaker
 * tags while the meeting runs, the capture auto-saved into the library however the run ends, then
 * the talk handed to the AI notes worker. One instance per service; it also owns the graceful-stop
 * flags the service's intents set ("Stop & save", "Next talk").
 */
internal class RecordingPipeline(
    private val host: PipelineHost,
    /** The mic (or, in test builds, the file that stands in for it). */
    private val newRecorder: () -> AudioRecorder = { AudioRecorder(feed = devMicFeed(host.context)) },
) {
    private val ctx: Context get() = host.context

    // Held so a stop request can end the capture promptly (the recorder polls it per block).
    @Volatile var stopRequested = false
        private set
    // "Next talk": when the graceful stop above was requested with DEFER semantics — skip
    // diarization + summary, auto-save the capture as RECORDED, and return immediately.
    @Volatile var deferRequested = false
        private set

    /** ACTION_STOP_RECORDING (defer = false) / ACTION_STOP_RECORDING_DEFER (defer = true). */
    fun requestStop(defer: Boolean) {
        if (defer) deferRequested = true
        stopRequested = true
    }

    /** A new start of any kind clears both flags (they belong to the run that is starting). */
    fun resetStopFlags() {
        stopRequested = false
        deferRequested = false
    }

    /**
     * Live recording: mic → streaming VAD/ASR (utterances stream in as you speak). On stop the
     * capture is written to a WAV (for the synced player) and the shared finish runs
     * diarization + summarization over the full waveform.
     */
    suspend fun run() {
        val cfg = TranscriptionConfig.Holder.config
        val models = ModelManager(ctx)
        // NOT here: ensureEngineModels(). On a first run it downloads ~275 MB, and the mic used to
        // open only after that — minutes of the meeting were lost while the timer ran. The mic
        // starts below first; the download happens while it records (the WAV keeps everything).
        val txtConverter = host.transcriptConverter()
        val snapConv = SnapshotConverter(txtConverter?.let { c -> c::convert })
        val recorder = newRecorder()
        val wav = File(File(ctx.filesDir, "audio").apply { mkdirs() }, "recording_${System.currentTimeMillis()}.wav")
        val utterances = ArrayList<TranscriptEvent.Utterance>()
        var diarized: Pair<List<TranscriptEvent.Utterance>, Int>? = null
        var libEntry: SessionLibrary.Entry? = null
        // Set by the capture coroutine on a mic failure so the post-collect path doesn't ALSO emit
        // 'No audio recorded' (one cause, one terminal event). AtomicBoolean for cross-coroutine visibility.
        val captureFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        // Set when the live recognizer missed audio (its channel overflowed, e.g. while a model was
        // still downloading): its transcript is then incomplete and must not stand in for the queue's.
        val liveDropped = java.util.concurrent.atomic.AtomicBoolean(false)
        // Snapshot of deferRequested taken the moment capture ends: the UI's next-talk flow fires
        // a new ACTION_RECORD (which resets the service-global flag) while THIS run is still
        // finishing — the run must keep the defer decision it stopped under.
        var deferred = false
        // Resume checkpoint inputs (see ImportPipeline): frozen prefix length, and whether live ASR ended cleanly.
        var frozenCount = 0
        var asrEnded = false

        // Track this capture so a process kill mid-meeting is recoverable on next launch. The finally
        // below clears it on a clean stop AND on user cancellation (both run finally) — only a hard
        // process kill leaves the marker, which is exactly what signals "recover this recording".
        RecordingRecovery.markStarted(ctx, wav)
        RunRegistry.micLive(true)

        // Set the live-capture status here (the engine no longer emits it), localized; this also
        // restores it after a model-download status was shown above.
        host.emitEvent(TranscriptEvent.Status(ctx.getString(R.string.status_recording)))
        host.updateNotification(ctx.getString(R.string.status_recording))

        // Start mic capture IMMEDIATELY, in its own job. The ASR engine below takes ~10 s to
        // construct on slow devices, and the recorder used to start only when the engine first
        // collected its flow — the opening seconds of every talk (and the level meter) were
        // silently lost. Blocks buffer in the channel (~33 s of slack, same anti-overrun sizing
        // as before: the channel keeps draining the mic regardless of decode latency, bounded so
        // a permanently-behind decoder can't OOM) while the engine loads and between decodes.
        // Mic level indicator: peak per mic block, quantized to 5 buckets and emitted only on
        // bucket change — visible proof the mic hears something, cheap enough for e-ink.
        val runGen = kotlin.coroutines.coroutineContext[RunGen]?.gen ?: TranscriptionService.UNTAGGED
        val mic = kotlinx.coroutines.channels.Channel<FloatArray>(MIC_BUFFER_BLOCKS)
        val capture = host.scope.launch(Dispatchers.IO) {
            var lastLevelBucket = -1
            try {
                recorder.record(wav) { stopRequested }.collect { chunk ->
                    var pk = 0f
                    for (v in chunk) { val a = if (v < 0f) -v else v; if (a > pk) pk = a }
                    val bucket = micLevelBucket(pk)
                    if (bucket != lastLevelBucket) {
                        lastLevelBucket = bucket
                        TranscriptionService.events.tryEmit(runGen to TranscriptEvent.MicLevel(bucket / 5f))
                    }
                    // trySend, NOT send: the WAV write already happened inside recorder.record()
                    // BEFORE this chunk was emitted, so the saved file is always complete. If the
                    // ASR decode falls &gt;33 s behind (channel full), a suspending send() would stall
                    // the recorder's read loop and DROP mic samples at the hardware buffer — instead
                    // we drop the chunk for the LIVE-PREVIEW recognizer only (the full WAV is
                    // re-transcribable on request) and keep the mic draining + the graceful
                    // stop flag responsive.
                    if (!mic.trySend(chunk).isSuccess) liveDropped.set(true)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // Mic init/read failure (busy device, dead HAL). Surface it — a capture job dying
                // silently produced empty "recordings" the user only discovered much later.
                android.util.Log.w("primeminutes-capture", "capture failed", t)
                captureFailed.set(true)
                // A disk-full write (ENOSPC) mid-recording surfaces here as an IOException — don't
                // mislabel it "microphone failed"; tell the user it's storage so they can free space.
                val msg = if (t is java.io.IOException) R.string.rec_storage_failed else R.string.mic_capture_failed
                TranscriptionService.events.tryEmit(runGen to TranscriptEvent.Failed(ctx.getString(msg)))
            } finally {
                mic.close()   // end-of-stream for transcribeLive (clean stop AND cancellation)
            }
        }
        // Live mode: the reader writes notes while the meeting is still being recorded.
        val live = host.reader.startLiveReader()
        try {
        // Models after the mic: a first-run download no longer delays the capture. Audio recorded
        // meanwhile is safe in the WAV (the live preview catches up within the channel's slack).
        host.ensureEngineModels(models)
        host.emitEvent(TranscriptEvent.Status(ctx.getString(R.string.status_recording)))
        host.createEngine(models).use { engine ->
            engine.transcribeLive(mic.consumeAsFlow())
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.UtteranceSnapshot -> {
                            val snap = snapConv.apply(e)
                            utterances.clear(); utterances += snap.utterances
                            frozenCount = snap.stable
                            host.emitEvent(snap)
                            host.reader.feedLive(live, snap)
                        }
                        else -> host.emitEvent(e)
                    }
                }
            deferred = deferRequested   // capture just ended — freeze this run's defer decision
            // Playback-volume normalization for the capture: a too-quiet recording is fixed in
            // the WAV itself (players can only attenuate, never amplify). Imported files don't
            // need this — their work WAV was already normalized at decode.
            withContext(Dispatchers.IO) { WavNormalizer.normalizeInPlace(wav) }
            // Speakers were tagged live, in the same pass — nothing left to run over the WAV.
            if (!deferred) engine.speakerCount?.let { diarized = utterances.toList() to it }
            asrEnded = true
        } // ASR + mic released here, before the LLM loads.
        } finally {
            // Capture finished (clean stop or user cancel) — the WAV header was finalized in
            // WavWriter.close(); drop the recovery marker so next launch doesn't re-offer it. A hard
            // process kill skips this, leaving the marker for RecordingRecovery.pending() to find.
            // Tear down the capture job first (an engine-load failure or a cancellation would
            // otherwise leave the mic running) and WAIT for it: its own finally closes the WAV,
            // which must be finalized before the promote below moves the file. NonCancellable so
            // the join still runs when this very coroutine was cancelled.
            withContext(NonCancellable) { capture.cancelAndJoin() }
            RunRegistry.micLive(false)
            RecordingRecovery.clear(ctx)
            // Auto-save the finalized capture into the app library immediately — this `finally`
            // runs on a clean stop AND on cancellation (ACTION_STOP), so a recording can no longer
            // be lost by a stray Stop. A hard process kill skips it, but then RecordingRecovery
            // promotes the repaired WAV on next launch. Plain file rename: cheap, non-suspending,
            // safe on a cancelled coroutine.
            if (wav.exists() && wav.length() > WavIo.HEADER + WavIo.SAMPLE_RATE * 2L) {
                libEntry = SessionLibrary.promoteRecording(
                    ctx, wav, (recorder.totalSamples / AsrEngine.SAMPLE_RATE).toInt(),
                )
                // Cancelled (ACTION_STOP) before the live ASR finished: the capture is saved, so also keep
                // the frozen transcript so far — Resume continues ASR from its end instead of from zero.
                // The normal path below hands the transcript over as the pending sidecar instead.
                val saved = libEntry
                if (!asrEnded && saved != null && frozenCount > 0) {
                    val keep = utterances.take(frozenCount)
                    if (keep.isNotEmpty()) SessionLibrary.saveProgress(saved, SessionLibrary.Progress(
                        host.transcriptFingerprint(cfg), keep, keep.last().endSec, false, ""))
                    live.latestCp?.let { SessionLibrary.saveNotes(saved, host.reader.readerModelId(), it) }
                }
            }
        }

        // Drop the microphone foreground type for the CPU-bound finish. The WAV is already on disk.
        host.startForegroundTyped(recording = false, text = ctx.getString(R.string.svc_processing))
        if (recorder.totalSamples == 0L) { if (!captureFailed.get()) host.emitEvent(TranscriptEvent.Failed(ctx.getString(R.string.svc_no_audio_recorded))); return }
        val savedWav = libEntry?.wavFile ?: wav
        host.emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(savedWav).toString()))

        // The live pass already transcribed and tagged this talk with the same engine the queue
        // would run; keep it as the item's pending transcript so the queue skips re-recognition
        // (the costliest step, ~ the talk's own length) and only reads + summarizes — also when the
        // live recognizer missed a little audio (see the hand-over below).
        val entry = libEntry
        if (entry != null && utterances.isNotEmpty() && !captureFailed.get()) {
            if (liveDropped.get()) Log.w("primeminutes-rec", "live recognizer missed audio; keeping its transcript")
            withContext(Dispatchers.IO) {
                SessionLibrary.savePendingTranscript(entry, utterances.toList(), host.transcriptFingerprint(cfg))
            }
        }

        // Hand the talk over to the AI notes worker and return at once: the mic and the speech
        // engine are free for the next talk, and the worker finishes THIS meeting first — notes,
        // summary, title, library save (it emits on this run's gen, so an open session sees it).
        // "Next talk" and "Stop & save" differ only in the UI. When the live recognizer missed
        // audio (it fell behind, e.g. during a model download) the summary is still written now
        // from what it heard: a full re-transcription first made a 7-minute meeting wait ~20 min
        // for its summary. The WAV is complete, so "Re-transcribe" can redo it on request.
        val tagged = diarized?.first ?: utterances
        host.emitEvent(TranscriptEvent.Complete(tagged, if (deferred) null else diarized?.second))
        if (live != null) {
            if (captureFailed.get()) host.reader.closeLive(live)
            else {
                libEntry?.let(live::attachEntry) ?: run { live.entry = libEntry }
                live.complete(tagged.toList())
                if (host.reader.liveReader === live) host.reader.liveReader = null
            }
        }
    }

    companion object {
        // Mic-capture backpressure slack: how many recorder blocks (~128 ms each) may queue ahead of
        // the ASR decode before the mic loop is throttled. ~33 s absorbs slow segment decodes so live
        // capture never overruns the AudioRecord hardware buffer. See run().
        private const val MIC_BUFFER_BLOCKS = 256

        /** Test builds (-PisolatedTestId) only: files/mic_feed.wav stands in for the mic, once. */
        fun devMicFeed(ctx: Context): File? = File(ctx.filesDir, "mic_feed.wav")
            .takeIf { studio.primeminutes.BuildConfig.APPLICATION_ID.endsWith(".androidtest") && it.exists() }
            ?.let { f -> File(ctx.cacheDir, "mic_feed.wav").also { f.renameTo(it) } }
    }
}
