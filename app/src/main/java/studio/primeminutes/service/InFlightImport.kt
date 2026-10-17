package studio.primeminutes.service

import android.util.Log
import studio.primeminutes.core.audio.WavIo
import studio.primeminutes.core.audio.patchWavHeader
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.reader.ReaderCheckpoint
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The foreground import in flight, as the service's stop handler needs to see it: the decoded work
 * WAV, its resume checkpoint, and the entry it was banked into.
 *
 * Exists because the pipeline coroutine CANNOT be relied on to save it. Measured on a Boox:
 * ACTION_STOP's cancelAndJoin times out after the full 15 s and the job ends
 * jobActive=false jobCompleted=false — cancelled but stuck mid-unwind inside a native ASR call
 * that never observes cancellation. Its `finally` therefore never runs, which is why three
 * successive fixes that lived in that finally all did nothing. The stop handler owns the save
 * instead: it runs on the main thread and always completes. [ImportPipeline] writes this state,
 * the stop handler and the pipeline's own finally both call [save]; whoever gets there first wins.
 */
internal class InFlightImport(
    /** Keep the import's source with the entry it was banked into ([SessionLibrary.keepImportSource]). */
    private val keepSource: (SessionLibrary.Entry, File) -> Boolean = SessionLibrary::keepImportSource,
    /** Move it into an existing entry instead ([SessionLibrary.promoteInto]): a downloaded item. */
    private val promoteInto: (SessionLibrary.Entry, File, Int, Boolean) -> SessionLibrary.Entry? = SessionLibrary::promoteInto,
    /** Move the finalized WAV into a new library entry ([SessionLibrary.promoteRecording]). */
    private val promote: (wav: File, durationSec: Int) -> SessionLibrary.Entry?,
) {
    /** The in-flight import's resume checkpoint: frozen prefix + the reader's notes so far. */
    class Checkpoint(val progress: SessionLibrary.Progress, val notes: () -> ReaderCheckpoint?)

    /** The decoded work WAV of the import currently in flight, or null. */
    var wav: File? = null
    /** The source's probed length (0 = unknown). */
    var durationSec = 0
    /** The import copy being decoded (filesDir/audio), or null when there is none to keep. */
    var source: File? = null
    /** The library item whose kept source is being decoded (a downloaded podcast / YouTube item): the
     *  audio goes into it, not into a new entry. */
    var into: SessionLibrary.Entry? = null
    /** The decoder reached the end of [source]: [wav] holds the whole file. */
    @Volatile var decoded = false
    /** One-shot: the stop handler and the pipeline both try to save; whoever gets there first wins. */
    private val saved = AtomicBoolean(false)

    /** The entry the stop handler / pipeline just banked the in-flight import into (see [save]). */
    @Volatile var savedEntry: SessionLibrary.Entry? = null

    /**
     * Refreshed on every ASR snapshot. The pipeline's own `finally` never runs on Stop (native ASR
     * does not observe cancellation), so the stop handler banks this next to the audio instead.
     */
    @Volatile var checkpoint: Checkpoint? = null

    /**
     * The library entry a foreground run over an existing entry (Resume, re-transcribe) writes into:
     * its audio is durable already, but the transcript it adds is not — [bankOnStop] keeps it there.
     */
    @Volatile var owner: SessionLibrary.Entry? = null

    /** A foreground import that owns no library entry yet starts: publish its work WAV. */
    @Synchronized fun begin(wav: File, durationSec: Int, source: File? = null, into: SessionLibrary.Entry? = null) {
        this.wav = wav
        this.durationSec = durationSec
        this.source = source
        this.into = into
        decoded = false
        saved.set(false)
        owner = null
        checkpoint = null
    }

    /** A foreground run over the library entry [entry] starts (nothing to promote: only progress to bank). */
    @Synchronized fun beginOwned(entry: SessionLibrary.Entry) {
        wav = null; source = null; into = null; decoded = false
        owner = entry
        checkpoint = null
    }

    /**
     * The ASR phase is over (the pipeline's `finally`): [write] saves its final progress, and the
     * checkpoint retires in the same section, so a Stop during the reading that follows cannot bank the
     * older, partial checkpoint over it.
     */
    @Synchronized fun endAsr(write: () -> Unit) {
        checkpoint = null
        owner = null
        write()
    }

    /**
     * Save the in-flight import's decoded audio into the library, at most once.
     *
     * Non-suspending on purpose — [SessionLibrary.promoteRecording] is a file rename — so the stop
     * handler can call it inline and be sure it completed before the service dies.
     */
    @Synchronized fun save(reason: String): SessionLibrary.Entry? {
        val wav = wav ?: return null
        val min = WavIo.HEADER + WavIo.SAMPLE_RATE * 2L
        if (!wav.exists() || wav.length() <= min) {
            Log.i(STOP_TAG, "saveInFlightImport($reason): nothing to save, len=${if (wav.exists()) wav.length() else -1}")
            return null
        }
        if (!saved.compareAndSet(false, true)) return null   // the other side already saved it
        // The decoder was still streaming into this file, so its header is the placeholder: finalize it
        // for what is on disk, or the entry is not a canonical WAV and the session .m4a cannot be built.
        runCatching { patchWavHeader(wav, (wav.length() - WavIo.HEADER) / 2) }
        val target = into
        if (target != null) {
            // A downloaded item: its source already sits in the entry (kept until fully decoded).
            val e = promoteInto(target, wav, if (decoded) bankedSeconds(wav, durationSec) else durationSec, decoded)
            Log.i(STOP_TAG, "saveInFlightImport($reason) into ${target.id} -> ${e?.id ?: "NULL"} decoded=$decoded")
            if (e != null) { this.wav = null; savedEntry = e }
            source = null
            return e
        }
        val entry = runCatching { promote(wav, bankedSeconds(wav, durationSec)) }
            .onFailure { Log.w(STOP_TAG, "saveInFlightImport($reason) failed", it) }
            .getOrNull()
        Log.i(STOP_TAG, "saveInFlightImport($reason) -> ${entry?.id ?: "NULL"}")
        if (entry != null) {
            this.wav = null; savedEntry = entry
            // Stopped before the decoder reached the end: the entry holds only a prefix of the file.
            // Its source goes with it, so "Resume" can decode the rest (instead of finishing the prefix).
            // The row then shows the meeting's length (the source's), not the decoded prefix: Resume covers
            // the whole file, and a 7:38 meeting listed as 5:35 read as lost audio.
            val src = source
            if (!decoded && src != null && src.isFile && keepSource(entry, src) && durationSec > entry.durationSec) {
                SessionLibrary.setDuration(entry, durationSec)?.let { savedEntry = it; source = null; return it }
            }
        }
        source = null
        return entry
    }

    /**
     * ACTION_STOP: bank the audio and, next to it, the resume checkpoint, synchronously, while the
     * service is still alive. The decoded WAV is already on disk and its writer is flushed by
     * AudioDecoder's `use`, so what is saved is every second transcribed so far.
     */
    @Synchronized fun bankOnStop(readerModelId: () -> String) {
        // A new import's audio is promoted into a new entry; a run over an existing entry has one.
        val e = save("stop") ?: owner
        val c = checkpoint
        if (e != null && c != null) {
            SessionLibrary.saveProgress(e, c.progress)
            c.notes()?.let { SessionLibrary.saveNotes(e, readerModelId(), it) }
            Log.i(STOP_TAG, "banked ${e.id}: ${c.progress.utterances.size} lines to ${c.progress.asrDoneSec.toInt()} s")
        }
        // Once: a repeated Stop (the notification's, the screen's) finds nothing left to bank.
        checkpoint = null
        owner = null
    }

    companion object {
        /**
         * Duration of what is actually on disk: a Stop mid-decode banks only the decoded part of the
         * import, so the entry must not claim the source's full length (the player and the row would
         * show minutes of audio that is not there). An entry that keeps its import source for Resume
         * lists the source's length instead ([save]).
         */
        fun bankedSeconds(wav: File, probed: Int): Int {
            val onDisk = ((wav.length() - WavIo.HEADER) / (WavIo.SAMPLE_RATE * 2L)).toInt()
            return if (probed > 0) minOf(probed, onDisk) else onDisk
        }
    }
}
