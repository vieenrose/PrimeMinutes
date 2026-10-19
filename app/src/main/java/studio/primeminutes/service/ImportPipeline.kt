package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.asr.SeamEnrollment
import studio.primeminutes.core.asr.SeamStitcher
import studio.primeminutes.core.asr.SnapshotConverter
import studio.primeminutes.core.audio.ImportRecovery
import studio.primeminutes.core.audio.SilenceSkipper
import studio.primeminutes.core.audio.WavIo
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File

/**
 * The file pipeline, split out of [TranscriptionService]: an imported file (or a library capture
 * re-run, or a queue item's pass 1) is decoded, transcribed and speaker-tagged in one streaming
 * pass, saved into the library however the run ends, read by the AI notes worker, and the results
 * embedded into its entry. One instance per service; [inFlight] is what the service's stop handler
 * banks when the run cannot unwind.
 */
internal class ImportPipeline(private val host: PipelineHost) {
    private val ctx: Context get() = host.context

    /** The foreground import in flight (work WAV, checkpoint, banked entry). */
    val inFlight = InFlightImport { wav, sec -> SessionLibrary.promoteRecording(host.context, wav, sec, importStart) }

    /** The import in flight's capture start ([SessionLibrary.Entry.captureStart]): its media creation date, else unknown. */
    @Volatile private var importStart: Long = SessionLibrary.UNKNOWN_START

    /**
     * Decode (MediaCodec) -> ASR (Phase 1) -> summarization (Phase 2). The ASR models are
     * released before the LLM loads — never both resident on small devices. Phase 3
     * inserts diarization between ASR and the Complete event.
     */
    suspend fun run(
        audioUri: String?,
        // false = the batch drain's pass 1: stop after ASR + diarization (Complete emitted, no LLM
        // touched) — the drain summarizes every item later under ONE engine load.
        summarizeAfter: Boolean = true,
    ): Pair<List<TranscriptEvent.Utterance>, ReaderWorker.SummaryResult>? {
        val uri = audioUri?.let(Uri::parse)
            ?: run { host.emitEvent(TranscriptEvent.Failed(ctx.getString(R.string.err_no_audio_source))); return null }
        val cfg = TranscriptionConfig.Holder.config

        val models = ModelManager(ctx)
        host.ensureEngineModels(models)

        host.emitEvent(TranscriptEvent.Status(ctx.getString(R.string.svc_transcribing)))
        host.emitEvent(TranscriptEvent.Progress(0f))   // restart the bar for the recognition phase
        // Generated text (summary/title/actions) and the transcript both follow the Chinese-script
        // setting; the transcript keeps the conservative s2t — see [transcriptConverter].
        val converter = host.outputConverter(cfg)
        val txtConverter = host.transcriptConverter()
        val snapConv = SnapshotConverter(txtConverter?.let { c -> c::convert })

        // Our own 16 kHz work WAVs (library captures, prior decode outputs) are streamed directly —
        // the same policy as the player source; routing them through the MediaCodec decode path is both
        // wasteful (a byte-identical copy) and unreliable for WAV input on some devices (observed:
        // zero decoded samples → empty transcript when the queue re-processed a library capture).
        val srcFile = if (uri.scheme == "file") uri.path?.let(::File) else null
        val ownWav = srcFile != null && srcFile.exists() && srcFile.extension == "wav" &&
            (srcFile.parentFile?.name == "audio" || srcFile.name == SessionLibrary.WAV_NAME)

        // --- ASR phase: collect utterances while streaming them to the UI. ---
        // Whether this run owns a library entry, decided BEFORE any long phase so the finally below
        // can save the audio no matter where the run stops. A re-run of a library capture already
        // has one (its audio never left) and the queue drain owns its own — both skip the promote.
        val foreground = (kotlin.coroutines.coroutineContext[RunGen]?.gen ?: TranscriptionService.UNTAGGED) != TranscriptionService.QUEUE_GEN
        val existing = if (ownWav && srcFile!!.name == SessionLibrary.WAV_NAME)
            srcFile.parentFile?.let { SessionLibrary.byId(ctx, it.name) } else null
        var entry: SessionLibrary.Entry? = existing
        // An import stopped mid-decode: its recording.wav is only the decoded prefix, and its source
        // was kept next to it. Any run over the entry (Resume, re-transcribe, the queue) decodes that
        // source again — the whole file — instead of streaming the prefix.
        val keptSource = existing?.let(SessionLibrary::importSource)
        val decodeUri = keptSource?.let(Uri::fromFile) ?: uri
        val streamWav = ownWav && keptSource == null
        if (keptSource != null) Log.i(STOP_TAG, "entry ${existing?.id}: decoding its kept import source")
        // A downloaded podcast / YouTube item processed from its kept source (it has no recording.wav yet):
        // the decoded audio goes into that item, not into a new entry.
        val intoEntry = srcFile?.takeIf { existing == null && it.name == SessionLibrary.IMPORT_SOURCE && it.isFile }
            ?.parentFile?.let { SessionLibrary.byId(ctx, it.name) }
        if (intoEntry != null) Log.i(STOP_TAG, "processing the downloaded item ${intoEntry.id}")
        // The import copy a Stop keeps with its entry (InFlightImport): only our own transient files.
        val importCopy = srcFile?.takeIf { !ownWav && it.isFile && it.parentFile?.absolutePath == File(ctx.filesDir, "audio").absolutePath }
        // The decoder reached the end of the source (the work WAV holds all of it).
        val decodedAll = java.util.concurrent.atomic.AtomicBoolean(false)
        // Total audio length (a cheap metadata read) so the recognition phase can report REAL progress
        // as each utterance's end time advances through the file. 0 when unknown → no ASR bar, still fine.
        val totalDurationSec = host.probeDurationSec(decodeUri)
        // When the meeting took place, for an import that becomes a new entry: the file's own creation date (a live
        // capture left in audio/ is named after its start), never the import time.
        if (existing == null && intoEntry == null) importStart = srcFile?.takeIf { ownWav }?.name
            ?.let { Regex("""^recording_(\d{12,})\.wav$""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: studio.primeminutes.core.audio.MediaDate.captureStartMs(ctx, decodeUri) ?: SessionLibrary.UNKNOWN_START

        // Resume (UI "Resume" on a stopped library capture): keep the saved frozen transcript and
        // transcribe only the audio after it. Anything else that re-runs the entry supersedes the
        // checkpoint (a fresh run starts from zero, as before).
        val wantResume = TranscriptionService.resumeRequested; TranscriptionService.resumeRequested = false
        val wantImportResume = TranscriptionService.importResumeRequested; TranscriptionService.importResumeRequested = false
        // The import copy a kill can interrupt (ImportRecovery's marker names it): its progress is saved
        // as it is transcribed, so "finish the interrupted import" continues after it.
        val recoverableCopy = srcFile?.takeIf { foreground && existing == null && ImportRecovery.keptPath(ctx) == it.absolutePath }
        val resume = (if (wantResume && existing != null && ownWav) SessionLibrary.loadProgress(existing)
            else if (wantImportResume && recoverableCopy != null) ImportRecovery.loadProgress(ctx, recoverableCopy)
            else null)?.takeIf { it.fingerprint == host.transcriptFingerprint(cfg) && !it.asrComplete && it.utterances.isNotEmpty() }
        if (resume != null && existing == null) Log.i(STOP_TAG, "import resume at ${resume.asrDoneSec.toInt()} s, ${resume.utterances.size} utterances kept")
        if (existing != null && resume == null) SessionLibrary.clearProgress(existing)
        val prior = resume?.utterances ?: emptyList()
        val seamSec = resume?.asrDoneSec ?: 0.0
        // Resume a little BEFORE the seam: the overlap lets SeamStitcher match the restarted diarizer's
        // speaker ids to the saved ones.
        val offsetSec = if (resume != null) (seamSec - SeamStitcher.PREROLL_SEC).coerceAtLeast(0.0) else 0.0
        // …and, before that, a few seconds of every saved speaker: a speaker silent in the preroll came
        // back as a new one (SeamEnrollment).
        val enroll = if (resume != null) SeamEnrollment.plan(prior, offsetSec) else SeamEnrollment.NONE
        if (enroll.clips.isNotEmpty()) Log.i(STOP_TAG, "resume enrollment: ${enroll.clips.size} clips, ${"%.1f".format(enroll.lengthSec)} s")

        // Stream-decode the source to a 16 kHz mono work WAV while feeding the live VAD/ASR — never
        // the whole waveform in RAM. The WAV is the player + diarization source (16 kHz mono).
        val wav = if (streamWav) srcFile!!
        else File(File(ctx.filesDir, "audio").apply { mkdirs() }, "decoded_${System.currentTimeMillis()}.wav")
        val chunks = if (streamWav) {
            // Raw PCM16 read in recorder-sized blocks (the capture was already AGC'd/normalized).
            kotlinx.coroutines.flow.flow {
                for ((s, e) in enroll.clips) { emitAll(wavChunks(wav, s, e)); emit(FloatArray((SeamEnrollment.GAP_SEC * WavIo.SAMPLE_RATE).toInt())) }
                emitAll(wavChunks(wav, offsetSec))
            }
        } else channelFlow {
            // normalize: quiet far-field imports get an automatic constant gain before the live
            // VAD/ASR sees them — and the work WAV (player + diarization source) carries the same
            // gain, so every downstream consumer hears identical audio.
            // A resumed import decodes the whole file (the work WAV must hold all of it) but feeds the
            // ASR only from offsetSec, like wavChunks does for a library capture.
            var skip = (offsetSec * WavIo.SAMPLE_RATE).toLong()
            // The enrollment clips lie in the skipped audio: kept aside (≤ 60 s) and sent first.
            val ranges = enroll.clips.map { (s, e) -> (s * WavIo.SAMPLE_RATE).toLong() to (e * WavIo.SAMPLE_RATE).toLong() }
            val clipAudio = ranges.map { (a, b) -> FloatArray((b - a).toInt()) }
            var pos = 0L
            var clipsSent = false
            // Stop cancels this producer: the decoder (blocking code) must end there too, also while it
            // waits for room in a full channel (sendFromBlocking; trySendBlocking parked it forever).
            fun send(a: FloatArray) {
                if (!isActive) throw kotlinx.coroutines.CancellationException("import stopped")
                sendFromBlocking(a)
            }
            host.decodeToWav16k(decodeUri, wav) { block, len ->
                if (!isActive) throw kotlinx.coroutines.CancellationException("import stopped")
                ranges.forEachIndexed { k, (a, b) ->
                    val lo = maxOf(a, pos); val hi = minOf(b, pos + len)
                    if (lo < hi) System.arraycopy(block, (lo - pos).toInt(), clipAudio[k], (lo - a).toInt(), (hi - lo).toInt())
                }
                pos += len
                if (skip >= len) { skip -= len; return@decodeToWav16k }
                // First block reaching the preroll: the clips go in front of it, once.
                if (!clipsSent) {
                    clipsSent = true
                    clipAudio.forEach { send(it); send(FloatArray((SeamEnrollment.GAP_SEC * WavIo.SAMPLE_RATE).toInt())) }
                }
                val from = skip.toInt(); skip = 0
                send(block.copyOfRange(from, len))
            }
            decodedAll.set(true)
            inFlight.decoded = true
        }.flowOn(Dispatchers.IO)


        // Publish the work WAV so ACTION_STOP can bank it even when this coroutine never unwinds.
        // Only for a foreground import that does not already own a library entry — a re-run of a
        // library capture is durable already, and the queue drain owns its own row.
        if (foreground && existing == null) {
            inFlight.begin(wav, totalDurationSec.toInt(), importCopy, intoEntry)
        } else if (foreground && summarizeAfter && existing != null) {
            // A Resume or re-run of a library entry: the entry is durable already, but what this run
            // transcribes is not — Stop banks its checkpoint into the entry too (InFlightImport.owner).
            inFlight.beginOwned(existing)
        }

        val utterances = ArrayList<TranscriptEvent.Utterance>()
        var diarized: Pair<List<TranscriptEvent.Utterance>, Int>? = null
        // Resume checkpoint inputs: how many leading utterances are frozen, and whether ASR ran to the end.
        var frozenCount = 0
        var progressSavedSec = resume?.asrDoneSec ?: 0.0   // ImportRecovery progress written up to here
        var asrFinished = false
        inFlight.savedEntry = null
        inFlight.checkpoint = null

        /**
         * Save the decoded audio into the library as a RECORDED entry.
         *
         * Runs from the finally below, so it fires on SUCCESS, on failure, and — the case this
         * exists for — on CANCELLATION. Aborting an import used to lose everything: the promote sat
         * after ASR and diarization, so stopping during either left the decoded WAV in filesDir with
         * no library row, and going Home dropped the whole session (reported from the field:
         * podcast → download → abort during transcription → audio and partial transcript both gone).
         * The recording pipeline has always promoted its capture from a finally for exactly this
         * reason; imports now match it.
         *
         * Plain file rename underneath, so it is safe to call on a cancelled coroutine.
         */
        suspend fun promoteImport() = withContext(NonCancellable) {
            // NonCancellable is REQUIRED, and the first version of this got it wrong. This function
            // is suspend (it emits an event) and runs from a finally on a coroutine that has usually
            // just been CANCELLED — the state a user's Stop puts it in, i.e. exactly the case the
            // promote exists for. Without this wrapper the first suspension point throws
            // CancellationException and the save never happens. The earlier comment here argued the
            // wrapper was unnecessary "because promoteRecording is a rename, not a suspending call";
            // that stopped being true the moment this wrapper itself became suspend.
            val len = if (wav.exists()) wav.length() else -1L
            Log.i("primeminutes-promote", "promoteImport foreground=$foreground existing=${existing?.id} " +
                "entry=${entry?.id} wavExists=${wav.exists()} wavLen=$len min=${WavIo.HEADER + WavIo.SAMPLE_RATE * 2L}")
            if (!foreground || existing != null || entry != null) return@withContext
            // Shared one-shot with the stop handler: on a normal finish this saves, and after an
            // abort it is already done and returns null (harmlessly) — whoever gets there first.
            entry = inFlight.save("pipeline")
            // The decoded WAV just moved into the entry — repoint the player (playhead carried).
            entry?.let { host.emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(it.wavFile).toString())) }
        }

        // Live mode: the reader loads now and reads the transcript while it is being recognized.
        val live = if (summarizeAfter) host.reader.startLiveReader().also { t ->
            // Resumed capture: the reader continues from its saved notes over the transcript so far.
            if (resume != null) { t.notesEntry = existing; t.seedUtts = prior }
        } else null
        try {
        val engine = try {
            host.createEngine(models)
        } catch (t: Throwable) {
            // The model files are present but the engine couldn't load them — an incomplete or
            // corrupt download. Remove them so a retry re-downloads a clean copy, and surface a
            // clear, retryable message instead of a raw native error in the transcript.
            runCatching { models.deleteAsr() }
            host.emitEvent(TranscriptEvent.Failed(ctx.getString(R.string.svc_asr_model_corrupt)))
            return null
        }
        engine.use {
            // One streaming pass transcribes AND diarizes: every snapshot carries speaker tags.
            // Long silences are shortened before the engine (it would spend real time on them) and
            // the timestamps mapped back onto the original audio.
            val skipper = SilenceSkipper()
            engine.transcribeLive(skipper.apply(chunks))
                .flowOn(Dispatchers.Default)
                .collect { e ->
                    when (e) {
                        is TranscriptEvent.UtteranceSnapshot -> {
                            val snap0 = snapConv.apply(e.copy(utterances = e.utterances.map {
                                val (s0, e0) = enroll.toOriginal(skipper.toOriginal(it.startSec), skipper.toOriginal(it.endSec))
                                it.copy(startSec = s0, endSec = e0)
                            }))
                            // Resumed run: the saved frozen prefix stays in front of the new audio's utterances.
                            val snap = if (prior.isEmpty()) snap0
                                else SeamStitcher.stitch(prior, seamSec, snap0.utterances, snap0.stable)
                                    .let { (all, stable) -> snap0.copy(utterances = all, stable = stable) }
                            utterances.clear(); utterances += snap.utterances
                            frozenCount = snap.stable
                            if (summarizeAfter && foreground && frozenCount > 0) {
                                val keep = utterances.take(frozenCount)
                                val progress = SessionLibrary.Progress(host.transcriptFingerprint(cfg), keep, keep.last().endSec, false, "")
                                inFlight.checkpoint = InFlightImport.Checkpoint(progress) { live?.latestCp }
                                // Once a minute of audio, on disk too: a kill does not run the stop handler.
                                if (recoverableCopy != null && progress.asrDoneSec - progressSavedSec >= 60) {
                                    ImportRecovery.saveProgress(ctx, recoverableCopy, progress)
                                    progressSavedSec = progress.asrDoneSec
                                }
                            }
                            host.emitEvent(snap)
                            host.reader.feedLive(live, snap)
                            // Recognition progress: how far the transcript reaches through the audio.
                            val end = utterances.lastOrNull()?.endSec ?: 0.0
                            if (totalDurationSec > 0) {
                                host.emitEvent(TranscriptEvent.Progress((end / totalDurationSec).toFloat().coerceIn(0f, 1f)))
                            }
                        }
                        else -> host.emitEvent(e)
                    }
                }
            engine.speakerCount?.let { diarized = utterances.toList() to it }
            asrFinished = true
        } // ASR native resources freed here, before the LLM is loaded.
        } finally {
            // Whatever happened — finished, failed, or aborted — the audio is on disk and belongs
            // in the library. promoteImport runs under NonCancellable; see it for why that matters.
            // This log distinguishes "the finally never ran" from "it ran and returned early",
            // which three failed fixes could not tell apart.
            Log.i(STOP_TAG, "runPipeline finally ENTERED")
            promoteImport()
            // An entry re-run from its kept source: the decoded WAV replaces the partial one once the
            // decoder reached the end; a run stopped before that leaves the entry (and source) as is.
            if (keptSource != null && existing != null) {
                if (decodedAll.get()) SessionLibrary.replaceImportAudio(existing, wav)?.let { u ->
                    entry = u
                    withContext(NonCancellable) { host.emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(u.wavFile).toString())) }
                } else runCatching { wav.delete() }
            }
            // Stop (or a failure) before the end: keep what was transcribed so far so the user can
            // resume instead of redoing it. Only the frozen prefix is safe — the tail is re-attributed.
            // The queue's pass 1 (summarizeAfter=false) has its own sidecar and is left alone.
            if (summarizeAfter && foreground) {
                // One section with the stop handler's bank (InFlightImport.endAsr): the ASR phase is over,
                // its checkpoint retires, and a later Stop (reader phase) cannot write it over this one.
                inFlight.endAsr {
                    (entry ?: existing ?: inFlight.savedEntry)?.let { e ->
                        val keep = if (asrFinished) utterances.toList() else utterances.take(frozenCount)
                        if (keep.isNotEmpty()) {
                            SessionLibrary.saveProgress(e, SessionLibrary.Progress(
                                host.transcriptFingerprint(cfg), keep, keep.last().endSec, asrFinished, ""))
                            live?.latestCp?.let { SessionLibrary.saveNotes(e, host.reader.readerModelId(), it) }
                        }
                        // The reading goes on after the ASR phase: each of its windows is kept with the
                        // entry from now on, so a Stop while it reads keeps the notes read so far.
                        live?.let { t -> if (t.notesEntry == null) t.notesEntry = e }
                    }
                }
                // Stopped before the end, the audio was banked by the stop handler: the screen follows
                // it to its entry (Resume, the player on the entry's audio rather than the moved file).
                if (entry == null && existing == null) inFlight.savedEntry?.let { e ->
                    withContext(NonCancellable) { host.emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(e.wavFile).toString())) }
                }
            }
            // The audio is in the library now (or there was none to save): the shared copy is no
            // longer the only one, so a later launch has nothing to recover.
            if (foreground) ImportRecovery.clear(ctx)
            Log.i(STOP_TAG, "runPipeline finally DONE entry=${entry?.id ?: "null"}")
        }

        // The decoded 16 kHz WAV is the player source now (per the streaming design), unless the
        // promote above already moved it into a library entry and repointed the player.
        if (entry == null) host.emitEvent(TranscriptEvent.RecordingSaved(Uri.fromFile(wav).toString()))
        if (!summarizeAfter && utterances.isNotEmpty()) {
            val tagged = diarized?.first ?: utterances
            host.emitEvent(TranscriptEvent.Complete(tagged, diarized?.second))
            return tagged to ReaderWorker.SummaryResult(null, null)
        }
        // The import was already promoted by the ASR phase's finally (see promoteImport). Nothing
        // to do here beyond keeping `entry` for attachResults below.

        val result = if (utterances.isEmpty()) {
            // No speech detected — a legitimate (empty) SUCCESS, not an error. An empty result (not
            // null) so the queue marks the entry DONE with an audio-only session, instead of leaving
            // it RECORDED and re-transcribing it on every 'Process all'. Real errors (no source /
            // corrupt model) return null above and stay retryable. A foreground run (import, recovered
            // capture) falls through to the attach below for the same reason: it used to return here,
            // and a silent recovered recording stayed "new" with a "Process pending (1)" that redid it.
            host.emitEvent(TranscriptEvent.Complete(emptyList(), speakerCount = null))
            host.reader.closeLive(live)
            emptyList<TranscriptEvent.Utterance>() to ReaderWorker.SummaryResult(null, null)
        } else finishPipeline(utterances, diarized, models, converter, live)

        // Embed the finished results into the entry (RECORDED → full self-describing session.m4a).
        // Non-fatal on failure: the session view still has the results, and the RECORDED entry above
        // is already a durable, re-runnable fallback.
        val libEntry = entry
        Log.i(STOP_TAG, "runPipeline attach: foreground=$foreground entry=${libEntry?.id} utts=${result.first.size} summary=${result.second.summary != null}")
        if (foreground && libEntry != null) {
            // NonCancellable: a new import supersedes this job (previousJob.cancel()) and used to land
            // during this ~40 s write, leaving the finished meeting RECORDED. The new job joins this one
            // before starting, so finishing the save only delays it.
            withContext(NonCancellable) { runCatching {
                // The session view may have flushed its own edits (speaker names, corrected lines)
                // into the entry while the summary was written: keep them rather than embed the
                // pipeline's unnamed, unedited transcript over them.
                val flushed = SessionLibrary.byId(ctx, libEntry.id)
                    ?.takeIf { it.status == SessionLibrary.Status.DONE && it.sessionFile.exists() }
                    ?.let { e -> PrimeMinutesSession.peek(e.sessionFile) }
                // The same transcript (the view's edits): its names as they are. A re-run's new transcript:
                // the previous run's names follow the speakers by time overlap (speaker ids change).
                val same = flushed != null && flushed.utterances.size == result.first.size &&
                    flushed.utterances.zip(result.first).all { (a, b) -> kotlin.math.abs(a.startSec - b.startSec) < 0.05 }
                val utts = if (same) flushed!!.utterances else result.first
                val names = when {
                    flushed == null -> emptyMap()
                    same -> flushed.speakerNames
                    else -> studio.primeminutes.core.people.SpeakerCarryOver.carry(
                        flushed.utterances.map { studio.primeminutes.core.people.SpeakerCarryOver.Turn(it.startSec, it.endSec, it.speaker) },
                        flushed.speakerNames,
                        result.first.map { studio.primeminutes.core.people.SpeakerCarryOver.Turn(it.startSec, it.endSec, it.speaker) })
                }
                val updated = SessionLibrary.attachResults(
                    ctx, libEntry, utts, names, result.second.summary, result.second.actions,
                    result.second.title, result.second.notes, cfg.asrModelId, cfg.asrBackend, cfg.llmModelId,
                )
                Log.i(STOP_TAG, "runPipeline attach -> ${updated?.id ?: "NULL (buildSession failed)"}")
                if (updated != null) {
                    host.emitEvent(TranscriptEvent.LibrarySaved(Uri.fromFile(updated.sessionFile).toString(), updated.title))
                    // Semantic index, like the other two save paths; without it a meeting imported
                    // while the app was open stayed unindexed until the next start's catch-up.
                    studio.primeminutes.core.embed.LibraryIndexer.request(ctx, updated.id)
                }
            }.onFailure { android.util.Log.w("primeminutes-library", "could not auto-save import", it) } }
            inFlight.wav = null   // fully attached; nothing left for a later stop to bank
        }
        return result
    }


    /** Final Complete (with speakers when diarization ran during the ASR phase) + summarization —
     *  shared by the file and recording paths. */
    private suspend fun finishPipeline(
        utterances: List<TranscriptEvent.Utterance>,
        diarized: Pair<List<TranscriptEvent.Utterance>, Int>?,
        models: ModelManager,
        converter: OpenCcConverter?,
        /** The live reading task, when it ran alongside ASR; otherwise the transcript is read now. */
        live: ReaderWorker.ReadTask?,
    ): Pair<List<TranscriptEvent.Utterance>, ReaderWorker.SummaryResult> {
        val tagged = diarized?.first ?: utterances
        host.emitEvent(TranscriptEvent.Complete(tagged, diarized?.second))
        val task = live ?: host.reader.submitRead(host.reader.ReadTask(currentGen(), converter, withTitle = true))
        task.complete(tagged)
        if (host.reader.liveReader === task) host.reader.liveReader = null
        val result = try {
            host.reader.awaitRead(task)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // The summary is an enhancement: a reader failure (download, OOM) keeps the transcript.
            Log.w("primeminutes-reader", "summarization failed", t)
            ReaderWorker.SummaryResult(null, null)
        }
        return tagged to result
    }
}
