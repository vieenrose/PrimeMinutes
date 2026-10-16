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
    private val keepSource: (SessionLibrary.Entry, File) -> Unit = SessionLibrary::keepImportSource,
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

    /** A foreground import that owns no library entry yet starts: publish its work WAV. */
    fun begin(wav: File, durationSec: Int, source: File? = null) {
        this.wav = wav
        this.durationSec = durationSec
        this.source = source
        decoded = false
        saved.set(false)
    }

    /**
     * Save the in-flight import's decoded audio into the library, at most once.
     *
     * Non-suspending on purpose — [SessionLibrary.promoteRecording] is a file rename — so the stop
     * handler can call it inline and be sure it completed before the service dies.
     */
    fun save(reason: String): SessionLibrary.Entry? {
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
        val entry = runCatching { promote(wav, bankedSeconds(wav, durationSec)) }
            .onFailure { Log.w(STOP_TAG, "saveInFlightImport($reason) failed", it) }
            .getOrNull()
        Log.i(STOP_TAG, "saveInFlightImport($reason) -> ${entry?.id ?: "NULL"}")
        if (entry != null) {
            this.wav = null; savedEntry = entry
            // Stopped before the decoder reached the end: the entry holds only a prefix of the file.
            // Its source goes with it, so "Resume" can decode the rest (instead of finishing the prefix).
            val src = source
            if (!decoded && src != null && src.isFile) keepSource(entry, src)
        }
        source = null
        return entry
    }

    /**
     * ACTION_STOP: bank the audio and, next to it, the resume checkpoint, synchronously, while the
     * service is still alive. The decoded WAV is already on disk and its writer is flushed by
     * AudioDecoder's `use`, so what is saved is every second transcribed so far.
     */
    fun bankOnStop(readerModelId: () -> String) {
        save("stop")?.let { e ->
            checkpoint?.let { c ->
                SessionLibrary.saveProgress(e, c.progress)
                c.notes()?.let { SessionLibrary.saveNotes(e, readerModelId(), it) }
            }
        }
        checkpoint = null
    }

    companion object {
        /**
         * Duration of what is actually on disk: a Stop mid-decode banks only the decoded part of the
         * import, so the entry must not claim the source's full length (the player and the row would
         * show minutes of audio that is not there).
         */
        fun bankedSeconds(wav: File, probed: Int): Int {
            val onDisk = ((wav.length() - WavIo.HEADER) / (WavIo.SAMPLE_RATE * 2L)).toInt()
            return if (probed > 0) minOf(probed, onDisk) else onDisk
        }
    }
}
