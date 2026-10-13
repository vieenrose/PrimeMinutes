package studio.voxsum.core.audio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Crash recovery for live recordings. A meeting captured in the foreground service is streamed to a
 * WAV on disk and checkpointed every few seconds ([AudioRecorder] / [WavWriter.checkpoint]); this
 * tracks *which* file is currently being recorded so that if the process is killed mid-meeting (OEM
 * auto-freeze, low-memory kill, the user swiping the app away), the next launch can recover it.
 *
 * Contract:
 *  - [markStarted] writes a marker naming the in-progress WAV when recording begins.
 *  - [clear] removes it — called from a `finally` around the capture, so it runs on a clean stop AND
 *    on user cancellation, but **not** if the process is killed.
 *  - [pending] therefore returns a file only when a kill left the marker behind. It repairs the WAV
 *    header (the streamed data is all on disk; only the 44-byte header wasn't finalized) and hands
 *    back a playable file the app can offer to finish transcribing.
 */
object RecordingRecovery {
    private const val MARKER = "recording.inprogress"
    private const val TAG = "RecordingRecovery"

    private fun marker(context: Context) = File(context.filesDir, MARKER)

    fun markStarted(context: Context, wav: File) {
        runCatching { marker(context).writeText(wav.absolutePath) }
            .onFailure { Log.w(TAG, "could not write recovery marker", it) }
    }

    fun clear(context: Context) {
        runCatching { marker(context).delete() }
    }

    /**
     * If a recording was interrupted by a process kill, repair its header and return the playable
     * WAV; otherwise return null. Cleans up the marker (and an empty/stale file) when there is nothing
     * to recover. A file with fewer than a second of audio is treated as noise and discarded.
     */
    fun pending(context: Context): File? {
        val m = marker(context)
        if (!m.exists()) return null
        val wav = runCatching { File(m.readText().trim()) }.getOrNull()
        // A too-short capture (header only, or < 1 s of PCM) isn't worth recovering.
        val minBytes = WavIo.HEADER + WavIo.SAMPLE_RATE * 2L
        if (wav == null || !wav.exists() || wav.length() < minBytes) {
            clear(context)
            wav?.let { runCatching { it.delete() } }
            return null
        }
        val samples = (wav.length() - WavIo.HEADER) / 2
        return runCatching { patchWavHeader(wav, samples); wav }
            .onFailure { Log.w(TAG, "could not repair interrupted recording", it) }
            .getOrNull()
    }

    /** Whole seconds of audio in a recovered WAV, from its on-disk length. */
    fun seconds(wav: File): Int =
        ((wav.length() - WavIo.HEADER).coerceAtLeast(0) / 2 / WavIo.SAMPLE_RATE).toInt()
}

/**
 * The same safety net for a shared / opened file: its import copy (filesDir/audio/shared_*) is the
 * only copy until the pipeline saves the decoded audio into the library, which happens when the
 * run ends. A process kill before that (swipe-away, low memory) used to lose the file silently and
 * the cold-start sweep deleted the copy. The marker names the copy and its display name; the
 * service clears it once the import is saved (or there was nothing to save).
 */
object ImportRecovery {
    private const val MARKER = "import.inprogress"

    private fun marker(context: Context) = File(context.filesDir, MARKER)

    fun markStarted(context: Context, copy: File, name: String) {
        runCatching { marker(context).writeText(copy.absolutePath + "\n" + name.replace('\n', ' ')) }
    }

    fun clear(context: Context) {
        runCatching { marker(context).delete() }
        runCatching { progressFile(context).delete() }
    }

    /**
     * The frozen transcript of the in-flight import, refreshed about once a minute of audio while it is
     * transcribed: a kill then resumes after it ("finish the interrupted import") instead of redoing the
     * whole file. Tied to [copy]; [clear] drops it with the marker.
     */
    private const val PROGRESS = "import.progress.json"
    private fun progressFile(context: Context) = File(context.filesDir, PROGRESS)

    fun saveProgress(context: Context, copy: File, p: studio.voxsum.core.library.SessionLibrary.Progress) {
        runCatching {
            val arr = org.json.JSONArray()
            p.utterances.forEach { arr.put(studio.voxsum.core.session.VoxsumSession.utteranceToJson(it)) }
            val o = org.json.JSONObject().put("copy", copy.absolutePath).put("fingerprint", p.fingerprint)
                .put("utterances", arr).put("asrDoneSec", p.asrDoneSec)
            val tmp = File(context.filesDir, "$PROGRESS.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(progressFile(context))) { progressFile(context).delete(); tmp.renameTo(progressFile(context)) }
        }.onFailure { Log.w("ImportRecovery", "could not persist import progress", it) }
    }

    /** The saved progress of [copy]'s import, or null (none, another file, unreadable). */
    fun loadProgress(context: Context, copy: File): studio.voxsum.core.library.SessionLibrary.Progress? = runCatching {
        val f = progressFile(context).takeIf { it.exists() } ?: return null
        val o = org.json.JSONObject(f.readText())
        if (o.optString("copy") != copy.absolutePath) return null
        val arr = o.getJSONArray("utterances")
        studio.voxsum.core.library.SessionLibrary.Progress(
            o.optString("fingerprint"),
            List(arr.length()) { i -> studio.voxsum.core.session.VoxsumSession.utteranceFromJson(arr.getJSONObject(i), fallbackIndex = i) },
            o.optDouble("asrDoneSec", 0.0), false, "",
        )
    }.getOrNull()

    /** The import copy the sweep must keep, if any. */
    fun keptPath(context: Context): String? =
        runCatching { marker(context).takeIf { it.exists() }?.readLines()?.firstOrNull()?.trim() }.getOrNull()

    /** An import a kill interrupted: its copy and display name; null (marker cleared) otherwise. */
    fun pending(context: Context): Pair<File, String>? {
        val lines = runCatching { marker(context).takeIf { it.exists() }?.readLines() }.getOrNull() ?: return null
        val copy = lines.firstOrNull()?.trim()?.let(::File)
        if (copy == null || !copy.exists() || copy.length() == 0L) { clear(context); return null }
        return copy to (lines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: copy.name)
    }
}
