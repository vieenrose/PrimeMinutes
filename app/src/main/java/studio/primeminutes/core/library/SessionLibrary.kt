package studio.primeminutes.core.library

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.session.RecentSessions
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.data.SpeakerName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app-owned session library: every finished recording is auto-saved here the moment the mic
 * stops — clean stop, "Stop" cancellation, or crash recovery — so a capture can never be lost by
 * a stray tap or a process kill. Deletion is the only explicit act; saving never is.
 *
 * Layout: one directory per entry under `filesDir/library/<id>/`:
 *  - `recording.wav` — the raw finalized capture (present until the entry is embedded + pruned)
 *  - `session.m4a`   — the self-describing [PrimeMinutesSession] file (audio + transcript + summary +
 *                      title), written by [attachResults] when the pipeline finishes
 *  - `meta.json`     — tiny sidecar (`{id,title,createdAt,durationSec,status}`) so a future
 *                      library list renders without parsing audio tags
 *
 * Entries surface through [RecentSessions] for now (a `file://` row on the home screen): a
 * RECORDED entry opens as plain audio (→ transcribe), a DONE one recovers the full session. A
 * dedicated library screen replaces that surfacing later; this store is already its source of truth.
 */
object SessionLibrary {
    private const val TAG = "SessionLibrary"
    private const val META = "meta.json"

    // meta.json is written from BOTH the UI (rename, on Dispatchers.IO) and the service pipeline
    // (attachResults/promoteRecording, on Default) in read-modify-write sequences — without
    // exclusion a rename during attachResults is lost, or a concurrent reader sees torn JSON
    // (readMeta then returns null and the caller treats the entry as corrupt). One process-wide
    // reentrant lock over every meta read/write and each read-modify-write pair.
    private val metaLock = Any()

    // One session.m4a build at a time per entry: the import's auto-save and the open session's edit
    // flush (leaving the session view) both rebuild it in the entry dir through the same temp names,
    // and the second deleted the first's temp mid-write (NoSuchFileException .audio_tmp.m4a, seen on
    // a 45-min import). The later call now waits and writes last.
    private val buildLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Runs [block] under the entry's build lock: the session file and its derived `index.bin`
     *  ([studio.primeminutes.core.embed.SessionIndex]) never change under each other. */
    suspend fun <T> withBuildLock(entryId: String, block: suspend () -> T): T =
        buildLocks.getOrPut(entryId) { kotlinx.coroutines.sync.Mutex() }.withLock { block() }
    const val WAV_NAME = "recording.wav"
    const val SESSION_NAME = "session.m4a"

    enum class Status { RECORDED, DONE }

    data class Entry(
        val id: String,
        val dir: File,
        val title: String?,
        val createdAt: Long,
        val durationSec: Int,
        val status: Status,
        /** Processed, but no speech was found ([SpeechCheck]): shown as such, never as "Done". */
        val noSpeech: Boolean = false,
        /** A podcast / YouTube item whose audio is still to be downloaded (or whose download failed). */
        val download: PendingDownload? = null,
        /** Downloaded, not processed yet: its audio is the kept source only (no recording.wav). */
        val sourceOnly: Boolean = false,
        /** When the meeting was captured (epoch ms): the live recording's start, an import's media creation date.
         *  [UNKNOWN_START]: an import whose file carries no date (the mail then omits the time). Null: an entry saved
         *  before the field, read as [createdAt] (its save time) as before. Separate from [createdAt], which ids derive from. */
        val captureStart: Long? = null,
    ) {
        val wavFile: File get() = File(dir, WAV_NAME)
        val sessionFile: File get() = File(dir, SESSION_NAME)

        /** Best playable audio: the embedded session when built, else the raw capture. */
        val audioFile: File get() = sessionFile.takeIf { it.exists() } ?: wavFile
    }

    fun root(context: Context) = File(context.filesDir, "library")

    /** [Entry.captureStart] of an import whose file has no creation date. */
    const val UNKNOWN_START = -1L

    /** The meeting's start for display (the mail's date and time): the capture start, the save time of an entry
     *  older than the field, null when unknown (an import without a media date). */
    fun meetingStart(e: Entry): Long? = when (val c = e.captureStart) {
        null -> e.createdAt
        UNKNOWN_START -> null
        else -> c
    }

    /** Display title for an untitled entry — capture date + time. Replaced by the LLM title or a
     *  user rename when one arrives. Formatted for [locale]: the interface language, which can
     *  differ from the device's (a zh-TW interface on an English phone showed "Oct 14, 09:27"
     *  between "上午9:27" rows). */
    fun defaultTitle(createdAt: Long, locale: Locale): String {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd HHmm")
        return SimpleDateFormat(pattern, locale).format(Date(createdAt))
    }
    /** A short date ("10月18日" / "Oct 18"), with the time when [withTime], in the interface
     *  language — DateUtils.formatDateTime follows the device's instead. */
    fun shortDate(context: Context, ms: Long, withTime: Boolean = false): String {
        val locale = studio.primeminutes.core.config.AppLanguage.uiLocale(context)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (withTime) "MMMd jmm" else "MMMd")
        return SimpleDateFormat(pattern, locale).format(Date(ms))
    }

    fun defaultTitle(context: Context, createdAt: Long): String =
        defaultTitle(createdAt, studio.primeminutes.core.config.AppLanguage.uiLocale(context))

    /** The library entry directory owning [uri] (a `file://` inside an entry), or null. */
    fun entryDirOf(context: Context, uri: Uri): File? =
        uri.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile }
            ?.takeIf { it.parentFile == root(context) }

    /** Rename an entry (user title edit or the LLM title arriving): updates the meta sidecar and
     *  the entry's RecentSessions row label. The on-disk file names never change — export/share
     *  already derive their file name from the title. */
    fun rename(context: Context, dir: File, title: String) {
        val t = title.trim().ifBlank { return }
        // Read-modify-write under the lock: interleaving with attachResults' own re-read/write
        // would otherwise lose one of the two updates.
        val entry = synchronized(metaLock) {
            val e = readMeta(dir) ?: return
            if (e.title == t) return
            runCatching { writeMeta(e.copy(title = t)) }
                .onFailure { Log.w(TAG, "could not rename library entry", it) }
            e
        }
        RecentSessions.add(context, Uri.fromFile(entry.audioFile).toString(), t, System.currentTimeMillis())
        // The title is part of the meeting's semantic index: rebuild it (no-op without the model).
        if (entry.status == Status.DONE) studio.primeminutes.core.embed.LibraryIndexer.request(context, entry.id)
    }

    /**
     * Move a finalized capture WAV into a new library entry (status RECORDED) and surface it in
     * RecentSessions. Called from the recording pipeline's `finally` — it must be cheap (a rename;
     * copy only across filesystems, which can't happen from filesDir/audio) and never throw.
     * Returns null on failure, leaving [wav] where it was (the caller keeps using its path).
     */
    fun promoteRecording(context: Context, wav: File, durationSec: Int, captureStart: Long? = null): Entry? = runCatching {
        val createdAt = System.currentTimeMillis()
        val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(createdAt))
        var dir = File(root(context), base)
        var n = 2
        while (dir.exists()) dir = File(root(context), "$base-${n++}")
        dir.mkdirs()
        val dest = File(dir, WAV_NAME)
        if (!wav.renameTo(dest)) {
            wav.copyTo(dest, overwrite = true)
            wav.delete()
        }
        // A capture saved as it stops (or recovered after a kill) began when its file was named (recording_<ms>.wav),
        // else durationSec before now, unless the caller knows better.
        val start = captureStart ?: Regex("""^recording_(\d{12,})\.wav$""").find(wav.name)?.groupValues?.get(1)?.toLongOrNull()
            ?: (createdAt - durationSec * 1000L)
        val entry = Entry(dir.name, dir, title = null, createdAt = createdAt, durationSec = durationSec, status = Status.RECORDED, captureStart = start)
        writeMeta(entry)
        // Discoverable immediately: a home-screen row that re-opens (→ transcribes) the raw capture.
        RecentSessions.add(context, Uri.fromFile(dest).toString(), defaultTitle(context, createdAt), createdAt)
        // Reclaim raw WAVs of entries whose session file is already built (their player sources are
        // no longer in use — starting a new recording reset the UI session).
        pruneWavs(context, excludeId = entry.id)
        entry
    }.onFailure { Log.w(TAG, "could not promote recording into library", it) }.getOrNull()

    /**
     * Embed the finished pipeline results into [entry] as a self-describing `session.m4a` (audio +
     * transcript + summary + title) and mark it DONE. The raw WAV is kept for now — it is still the
     * open session's player source — and reclaimed by [pruneWavs] on the next recording.
     */
    suspend fun attachResults(
        context: Context,
        entry: Entry,
        utterances: List<TranscriptEvent.Utterance>,
        speakerNames: Map<Int, SpeakerName>,
        summary: String?,
        actionItems: String?,
        title: String?,
        /** v2 structured notes in wire form, or null. See PrimeMinutesSession.Loaded.notes. */
        notes: String?,
        asrModelId: String?,
        asrBackend: String?,
        llmModelId: String?,
        // Audio to embed. Defaults to the raw capture; the edits-persist path passes the open
        // session's audio instead when the WAV was already pruned (session.m4a-only entries).
        audio: Uri? = null,
    ): Entry? = withBuildLock(entry.id) {
        // A new result outranks the edits pending before it (and not those made while it builds).
        attachLocked(context, entry, Content(utterances, speakerNames, summary, actionItems, title, notes, asrModelId, asrBackend, llmModelId),
            audio ?: Uri.fromFile(entry.wavFile), EditJournal.nextRev(entry.dir))
    }

    /** What a session file holds besides its audio. */
    data class Content(
        val utterances: List<TranscriptEvent.Utterance>,
        val speakerNames: Map<Int, SpeakerName>,
        val summary: String?,
        val actionItems: String?,
        val title: String?,
        val notes: String?,
        val asrModelId: String?,
        val asrBackend: String?,
        val llmModelId: String?,
    ) {
        fun manifest(rev: Long) = PrimeMinutesSession.manifest(utterances, speakerNames, summary, actionItems, title, notes, asrModelId, asrBackend, llmModelId, rev)
        companion object {
            fun of(l: PrimeMinutesSession.Loaded) = Content(l.utterances, l.speakerNames, l.summary, l.actionItems, l.title, l.notes, l.asrModelId, l.asrBackend, l.llmModelId)
        }
    }

    /**
     * An edit of a DONE entry, durable in a few ms ([EditJournal]): what the meeting screen saves on
     * every change. The m4a follows later ([foldEdits]). False when the entry is gone or the write failed.
     */
    fun saveEdits(context: Context, entryId: String, c: Content): Boolean {
        val entry = byId(context, entryId) ?: return false   // deleted while open: no ghost row
        return runCatching {
            EditJournal.write(entry.dir, c.manifest(EditJournal.nextRev(entry.dir)))
        }.onFailure { Log.w(TAG, "could not save the edits of $entryId", it) }.isSuccess
    }

    /** The audio a rebuild of [entry]'s session file starts from: its own session.m4a (only the tags are
     *  rewritten), else the raw capture, else [fallback]. */
    private suspend fun rebuildSource(entry: Entry, fallback: Uri?): Uri? = when {
        entry.sessionFile.isFile && PrimeMinutesSession.hasEmbeddedSession(entry.sessionFile) -> Uri.fromFile(entry.sessionFile)
        entry.wavFile.isFile -> Uri.fromFile(entry.wavFile)
        entry.sessionFile.isFile -> Uri.fromFile(entry.sessionFile)
        else -> fallback
    }

    /**
     * Writes [entryId]'s pending edits into its session.m4a and drops them (unless edited again
     * meanwhile). A kill at any point loses nothing: the build renames into place, and the edits are
     * removed only once the m4a holds them. The updated entry, or null when there was nothing to fold.
     */
    suspend fun foldEdits(context: Context, entryId: String, fallbackAudio: Uri? = null): Entry? = withBuildLock(entryId) {
        val entry = byId(context, entryId) ?: return@withBuildLock null
        val pending = EditJournal.read(entry.dir) ?: run { EditJournal.deleteUpTo(entry.dir, 0L); return@withBuildLock null }
        val rev = pending.optLong(EditJournal.REV, 0L)
        val embedded = entry.sessionFile.takeIf { it.isFile }?.let { PrimeMinutesSession.embeddedManifest(it) }
        if (EditJournal.newer(pending, embedded) == null) {
            // Already in the m4a (a kill between its rename and this cleanup), or superseded by a newer result.
            EditJournal.deleteUpTo(entry.dir, embedded?.optLong(EditJournal.REV, 0L) ?: 0L)
            return@withBuildLock null
        }
        val c = runCatching { Content.of(PrimeMinutesSession.loaded(pending, entry.sessionFile)) }
            .onFailure { Log.w(TAG, "unreadable edits of $entryId, kept", it) }.getOrNull() ?: return@withBuildLock null
        val src = rebuildSource(entry, fallbackAudio) ?: return@withBuildLock null
        val t0 = System.currentTimeMillis()
        attachLocked(context, entry, c, src, rev)?.also {
            Log.i(TAG, "folded the edits of $entryId (rev $rev) into the session file in ${System.currentTimeMillis() - t0} ms")
        }
    }

    /** Ids of the entries with edits still to fold (a kill before the fold; folded at the next launch). */
    fun pendingEdits(context: Context): List<String> =
        root(context).listFiles()?.filter { EditJournal.exists(it) }?.map { it.name } ?: emptyList()

    /**
     * [entry]'s session file as it should be shared, at [dest]: a copy when no edit is pending, else
     * rebuilt with the pending edits (the entry's own file is folded later, as usual). Null on failure.
     */
    suspend fun shareableCopy(context: Context, entry: Entry, dest: File): File? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val src = entry.audioFile
        val pending = if (src == entry.sessionFile) EditJournal.read(entry.dir) else null
        val embedded = pending?.let { PrimeMinutesSession.embeddedManifest(src) }
        val edits = EditJournal.newer(pending, embedded)
        if (edits == null) return@withContext runCatching { src.copyTo(dest, overwrite = true) }.getOrNull()
        val c = runCatching { Content.of(PrimeMinutesSession.loaded(edits, src)) }.getOrNull()
            ?: return@withContext runCatching { src.copyTo(dest, overwrite = true) }.getOrNull()
        withBuildLock(entry.id) {
            PrimeMinutesSession.buildSession(
                context, dest.parentFile!!, Uri.fromFile(src), c.utterances, c.speakerNames, c.summary, c.actionItems,
                c.title, c.notes, c.asrModelId, c.asrBackend, c.llmModelId, fileName = dest.name,
                format = PrimeMinutesSession.Format.M4A, editRev = edits.optLong(EditJournal.REV, 0L),
            )?.file
        }
    }

    /** Builds [entry]'s session file from [c] at revision [rev] (caller holds the entry's build lock). */
    private suspend fun attachLocked(context: Context, entry: Entry, c: Content, audio: Uri, rev: Long): Entry? {
        val built = PrimeMinutesSession.buildSession(
            context, entry.dir, audio, c.utterances, c.speakerNames,
            c.summary, c.actionItems, c.title, c.notes, c.asrModelId, c.asrBackend, c.llmModelId,
            coverEnabled = true, fileName = SESSION_NAME, format = PrimeMinutesSession.Format.M4A, editRev = rev,
        ) ?: return null
        val utterances = c.utterances
        // Edits written before this build are in it now; those written while it ran stay pending.
        val latest = EditJournal.deleteUpTo(entry.dir, rev)
        // The meeting's named speakers and their people (the sidecar the directory reads) — unless a
        // newer edit is pending: the screen already wrote ITS names, which these would overwrite.
        if (latest) studio.primeminutes.core.people.PeopleStore.saveNames(entry.dir, c.speakerNames)
        studio.primeminutes.core.people.VoiceStore.request(context, entry.id)   // voice vectors (only when recognition is on)
        clearProgress(entry)   // the run is complete: nothing left to resume
        // A user-given name (set at capture time or via rename) outranks the LLM title — batch
        // processing must never rename "Talk 3 — Dr. Smith" to whatever the model invents. Re-read
        // the meta rather than trusting [entry]: a rename made WHILE this item processed would
        // otherwise be clobbered by the stale snapshot taken at drain start. The re-read + write
        // is one atomic section (metaLock) so a rename can't slip between them and be lost.
        val updated = synchronized(metaLock) {
            val u = entry.copy(
                // A silent capture finished "Done" with a date for a title, indistinguishable from a
                // real meeting in the list: name it after what happened.
                // A run that found no speech named the entry "No speech detected": a later run of its
                // audio that does find speech replaces that with its own title.
                title = SpeechCheck.keptTitle(byId(context, entry.id)?.title) ?: SpeechCheck.keptTitle(entry.title)
                    ?: c.title?.trim()?.ifBlank { null }
                    ?: context.getString(studio.primeminutes.R.string.status_no_speech).takeIf { utterances.isEmpty() },
                status = Status.DONE,
                noSpeech = !SpeechCheck.hasSpeech(utterances.map { it.text }),
            )
            runCatching { writeMeta(u) }.onFailure { Log.w(TAG, "could not update library meta", it) }
            u
        }
        // Replace the raw-capture Recent row with the finished session (different uri AND title, so
        // RecentSessions' own dedup wouldn't collapse them).
        RecentSessions.remove(context, Uri.fromFile(entry.wavFile).toString())
        RecentSessions.add(
            context, Uri.fromFile(updated.sessionFile).toString(),
            updated.title ?: defaultTitle(context, updated.createdAt), System.currentTimeMillis(),
        )
        return updated
    }

    /** A single entry by id, or null if missing/corrupt. */
    fun byId(context: Context, id: String): Entry? = readMeta(File(root(context), id))

    // --- pending transcript sidecar (queue drain, pass 1 → pass 2) ---
    //
    // The batch drain transcribes+diarizes EVERY queued item first (ASR models only), then loads
    // the LLM once and summarizes them all — never both model families resident on small
    // devices, and only one LLM load per drain instead of one per item. Between the passes each
    // item's transcript lives in this sidecar, so a process kill resumes summarize-only instead of
    // redoing ASR. The fingerprint (ASR model + language + target + diarization) invalidates a
    // sidecar written under settings the user has since changed — pass 1 then re-transcribes.
    private const val PENDING_TRANSCRIPT = "pending_transcript.json"

    fun savePendingTranscript(entry: Entry, utterances: List<TranscriptEvent.Utterance>, fingerprint: String) {
        runCatching {
            val arr = org.json.JSONArray()
            utterances.forEach { arr.put(PrimeMinutesSession.utteranceToJson(it)) }
            val o = JSONObject().put("fingerprint", fingerprint).put("utterances", arr)
            File(entry.dir, PENDING_TRANSCRIPT).writeTextAtomic(o.toString())
        }.onFailure { Log.w(TAG, "could not persist pending transcript for ${entry.id}", it) }
    }

    /** The sidecar's utterances, or null when absent, corrupt, or written under different settings
     *  ([fingerprint] mismatch — the stale file is deleted so pass 1 re-transcribes). */
    fun loadPendingTranscript(entry: Entry, fingerprint: String): List<TranscriptEvent.Utterance>? {
        val f = File(entry.dir, PENDING_TRANSCRIPT)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optString("fingerprint") != fingerprint) {
                runCatching { f.delete() }
                return null
            }
            val arr = o.getJSONArray("utterances")
            List(arr.length()) { i -> PrimeMinutesSession.utteranceFromJson(arr.getJSONObject(i), fallbackIndex = i) }
        }.onFailure {
            Log.w(TAG, "corrupt pending transcript in ${entry.id}", it)
            runCatching { f.delete() }
        }.getOrNull()
    }

    /** The sidecar's utterances whatever settings wrote it — for DISPLAY only (watching a queued
     *  item whose ASR pass already finished); null when absent or unreadable. */
    fun peekPendingTranscript(entry: Entry): List<TranscriptEvent.Utterance>? {
        val f = File(entry.dir, PENDING_TRANSCRIPT)
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("utterances")
            List(arr.length()) { i -> PrimeMinutesSession.utteranceFromJson(arr.getJSONObject(i), fallbackIndex = i) }
        }.getOrNull()
    }

    // --- interrupted-run checkpoint (Stop / kill mid-processing → "Resume") ---
    //
    // Separate from PENDING_TRANSCRIPT on purpose: that sidecar means "ASR finished" and lets the
    // queue skip straight to summarizing, so a partial transcript must never be written there. This
    // one holds the FROZEN prefix only (utterances that can no longer change) plus how far the audio
    // got, and the reading agent's notes written so far. Present = the entry is resumable.
    private const val PROGRESS = "pending_progress.json"

    /** [asrDoneSec] = end of the last frozen utterance (where ASR resumes); [asrComplete] = ASR had
     *  finished, so only the reading/summary is left. [notes] = the agent journal, rendered. */
    data class Progress(
        val fingerprint: String,
        val utterances: List<TranscriptEvent.Utterance>,
        val asrDoneSec: Double,
        val asrComplete: Boolean,
        val notes: String,
    )

    fun saveProgress(entry: Entry, p: Progress) {
        runCatching {
            val arr = org.json.JSONArray()
            p.utterances.forEach { arr.put(PrimeMinutesSession.utteranceToJson(it)) }
            val o = JSONObject().put("fingerprint", p.fingerprint).put("utterances", arr)
                .put("asrDoneSec", p.asrDoneSec).put("asrComplete", p.asrComplete).put("notes", p.notes)
            val tmp = File(entry.dir, "$PROGRESS.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(entry.dir, PROGRESS))) { File(entry.dir, PROGRESS).delete(); tmp.renameTo(File(entry.dir, PROGRESS)) }
        }.onFailure { Log.w(TAG, "could not persist progress for ${entry.id}", it) }
    }

    /** The checkpoint whatever settings wrote it (callers compare [Progress.fingerprint]); null when
     *  absent or unreadable. */
    fun loadProgress(entry: Entry): Progress? {
        val f = File(entry.dir, PROGRESS)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("utterances")
            Progress(
                o.optString("fingerprint"),
                List(arr.length()) { i -> PrimeMinutesSession.utteranceFromJson(arr.getJSONObject(i), fallbackIndex = i) },
                o.optDouble("asrDoneSec", 0.0), o.optBoolean("asrComplete", false), o.optString("notes"),
            )
        }.onFailure { Log.w(TAG, "corrupt progress in ${entry.id}", it) }.getOrNull()
    }

    private const val NOTES = "pending_notes.json"

    /** The reader's checkpoint (journal + how many transcript lines it covers), atomic. */
    /** The reader's off-protocol counts of the meeting's last reading (model guardrail, DEV
     *  diagnostics): a sidecar next to the meta, rewritten by each reading. */
    const val READER_GUARD = "reader_guard.json"

    fun saveReaderGuard(dir: File, g: studio.primeminutes.core.reader.ReaderGuard, llmModelId: String) {
        runCatching { File(dir, READER_GUARD).writeTextAtomic(g.toJson().put("model", llmModelId).put("at", System.currentTimeMillis()).toString()) }
            .onFailure { Log.w(TAG, "could not save the reader guard counts", it) }
    }

    fun loadReaderGuard(dir: File): studio.primeminutes.core.reader.ReaderGuard? =
        runCatching { studio.primeminutes.core.reader.ReaderGuard.fromJson(JSONObject(File(dir, READER_GUARD).readText())) }.getOrNull()

    fun saveNotes(entry: Entry, llmModelId: String, cp: studio.primeminutes.core.reader.ReaderCheckpoint) {
        runCatching {
            val arr = org.json.JSONArray()
            cp.journal.forEach { arr.put(JSONObject().put("id", it.id).put("window", it.window).put("ts", it.ts).put("tag", it.tag ?: JSONObject.NULL).put("text", it.text)) }
            val o = JSONObject().put("llm", llmModelId).put("window", cp.window).put("offered", cp.offered).put("digest", cp.digest).put("journal", arr)
            val tmp = File(entry.dir, "$NOTES.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(entry.dir, NOTES))) { File(entry.dir, NOTES).delete(); tmp.renameTo(File(entry.dir, NOTES)) }
        }.onFailure { Log.w(TAG, "could not persist notes for ${entry.id}", it) }
    }

    /** The reader checkpoint written with [llmModelId] (another model's notes are not reused). */
    fun loadNotes(entry: Entry, llmModelId: String): studio.primeminutes.core.reader.ReaderCheckpoint? {
        val f = File(entry.dir, NOTES)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optString("llm") != llmModelId) return@runCatching null
            val a = o.getJSONArray("journal")
            val journal = List(a.length()) { i ->
                val n = a.getJSONObject(i)
                studio.primeminutes.core.reader.Note(n.getInt("id"), n.getInt("window"), n.getString("ts"), if (n.isNull("tag")) null else n.getString("tag"), n.getString("text"))
            }
            studio.primeminutes.core.reader.ReaderCheckpoint(journal, o.getInt("window"), o.getInt("offered"), o.optLong("digest", 0L))
        }.onFailure { Log.w(TAG, "corrupt notes in ${entry.id}", it) }.getOrNull()
    }

    fun clearNotes(entry: Entry) {
        runCatching { File(entry.dir, NOTES).delete() }
    }

    fun hasProgress(entry: Entry): Boolean = File(entry.dir, PROGRESS).exists()

    /** [keepNotes]: the transcript moves on (Resume hands it to the queue) but the reading continues
     *  from the notes read so far. */
    fun clearProgress(entry: Entry, keepNotes: Boolean = false) {
        runCatching { File(entry.dir, PROGRESS).delete() }
        if (!keepNotes) clearNotes(entry)
    }

    fun clearPendingTranscript(entry: Entry) {
        runCatching { File(entry.dir, PENDING_TRANSCRIPT).delete() }
    }

    /** All library entries, newest first. Corrupt/foreign directories are skipped, never deleted. */
    fun list(context: Context): List<Entry> =
        root(context).listFiles()?.mapNotNull { dir -> readMeta(dir) }?.sortedByDescending { it.createdAt }
            ?: emptyList()

    /**
     * Explicit user deletion. [file] is any file inside an entry (e.g. the recovered WAV the
     * recovery dialog holds) — removes the whole entry; a non-library file is just deleted.
     */
    fun discard(context: Context, file: File) {
        val dir = file.parentFile
        runCatching {
            if (dir != null && dir.parentFile == root(context)) {
                RecentSessions.remove(context, Uri.fromFile(File(dir, WAV_NAME)).toString())
                RecentSessions.remove(context, Uri.fromFile(File(dir, SESSION_NAME)).toString())
                dir.deleteRecursively()
                // Its people links went with it; its voice samples go too (docs/design/speaker-tags.md §2.6).
                studio.primeminutes.core.people.PeopleStore.onMeetingDeleted(context, dir.name)
            } else file.delete()
        }.onFailure { Log.w(TAG, "could not discard $file", it) }
    }

    /** Delete the raw WAV of every DONE entry (its session.m4a holds the same audio), except
     *  [excludeId] — the entry whose WAV the current UI session may still be playing. */
    private fun pruneWavs(context: Context, excludeId: String) {
        root(context).listFiles()?.forEach { dir ->
            if (dir.name == excludeId) return@forEach
            val wav = File(dir, WAV_NAME)
            if (wav.exists() && File(dir, SESSION_NAME).exists()) runCatching { wav.delete() }
        }
    }

    // --- import stopped mid-decode: its source stays with the entry ---
    //
    // A Stop during an import banks only the audio decoded so far into recording.wav. The source
    // file (the app's import copy) is moved next to it, so a resume decodes the WHOLE file again and
    // the meeting ends up covering all of it; without it, "Resume" only finished the decoded prefix.
    const val IMPORT_SOURCE = "import_source"

    /** The kept source of an import stopped mid-decode, or null (the entry's WAV is complete). */
    fun importSource(entry: Entry): File? = File(entry.dir, IMPORT_SOURCE).takeIf { it.isFile && it.length() > 0 }

    /** Move [src] (the import copy under filesDir/audio) into [entry] as its kept source; false when it
     *  could not be moved. Never throws. */
    fun keepImportSource(entry: Entry, src: File): Boolean =
        runCatching {
            val dest = File(entry.dir, IMPORT_SOURCE)
            if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() }
            Log.i(TAG, "kept the import source of ${entry.id} (${dest.length()} B)")
        }.onFailure { Log.w(TAG, "could not keep the import source of ${entry.id}", it) }.isSuccess

    /** Set [entry]'s listed length (meta durationSec); the updated entry, or null on failure. */
    fun setDuration(entry: Entry, sec: Int): Entry? = runCatching {
        synchronized(metaLock) { (readMeta(entry.dir) ?: entry).copy(durationSec = sec).also { writeMeta(it) } }
    }.onFailure { Log.w(TAG, "could not set the duration of ${entry.id}", it) }.getOrNull()

    /**
     * A resumed import decoded its kept source to the end: [wav] (finalized, 16 kHz mono) replaces the
     * entry's partial recording.wav, the duration follows it, and the source is no longer needed.
     */
    fun replaceImportAudio(entry: Entry, wav: File): Entry? = runCatching {
        val dest = entry.wavFile
        if (!wav.renameTo(dest)) { wav.copyTo(dest, overwrite = true); wav.delete() }
        val sec = ((dest.length() - studio.primeminutes.core.audio.WavIo.HEADER).coerceAtLeast(0) / (studio.primeminutes.core.audio.WavIo.SAMPLE_RATE * 2L)).toInt()
        val updated = synchronized(metaLock) {
            val cur = readMeta(entry.dir) ?: entry
            cur.copy(durationSec = sec).also { writeMeta(it) }
        }
        File(entry.dir, IMPORT_SOURCE).delete()
        Log.i(TAG, "import ${entry.id} completed from its kept source: $sec s")
        updated
    }.onFailure { Log.w(TAG, "could not complete the audio of ${entry.id}", it) }.getOrNull()

    // --- podcast / YouTube item created before its download ---
    //
    // The item exists from the moment the link or episode is submitted: `download.json` says what to fetch
    // (and, once it failed for good, why), the partial file downloads into the entry's own directory, and
    // the finished file becomes the entry's kept import source. A kill, a lost network or a failure leaves
    // the item in the library, where it can be resumed, retried or deleted (deleting its directory removes
    // the partial file too).
    const val DOWNLOAD = "download.json"

    /** What an item still has to download. [kind]: "podcast" (direct audio URL), "youtube" or "peertube" (video page URL). */
    data class PendingDownload(val kind: String, val url: String, val error: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("kind", kind).put("url", url).apply { error?.let { put("error", it) } }
        companion object {
            const val PODCAST = "podcast"
            const val YOUTUBE = "youtube"
            const val PEERTUBE = "peertube"
            fun fromJson(o: JSONObject) = PendingDownload(o.getString("kind"), o.getString("url"), o.optString("error").ifBlank { null })
        }
    }

    /** A new entry for an item to download, titled [title] (null: named once known). Null on failure. */
    fun createDownloadEntry(context: Context, title: String?, d: PendingDownload, durationSec: Int = 0): Entry? = runCatching {
        val createdAt = System.currentTimeMillis()
        val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(createdAt))
        var dir = File(root(context), base)
        var n = 2
        while (dir.exists()) dir = File(root(context), "$base-${n++}")
        dir.mkdirs()
        File(dir, DOWNLOAD).writeTextAtomic(d.toJson().toString())
        // A podcast / YouTube item: no meeting time to show.
        val entry = Entry(dir.name, dir, title?.trim()?.ifBlank { null }, createdAt, durationSec, Status.RECORDED, download = d, captureStart = UNKNOWN_START)
        writeMeta(entry)
        entry
    }.onFailure { Log.w(TAG, "could not create the download item", it) }.getOrNull()

    fun saveDownload(entry: Entry, d: PendingDownload) {
        runCatching { File(entry.dir, DOWNLOAD).writeTextAtomic(d.toJson().toString()) }
            .onFailure { Log.w(TAG, "could not save the download state of ${entry.id}", it) }
    }

    /**
     * The download of [entry] landed in [file]: it becomes the entry's kept source (what processing
     * decodes), the pending download is cleared and the listed length follows the file. The updated entry.
     */
    fun downloadDone(entry: Entry, file: File, durationSec: Int): Entry? {
        if (!keepImportSource(entry, file)) return null
        runCatching { File(entry.dir, DOWNLOAD).delete() }
        return (if (durationSec > 0) setDuration(entry, durationSec) else null) ?: byId(entry.dir.parentFile!!, entry.id)
    }

    private fun byId(root: File, id: String): Entry? = readMeta(File(root, id))

    /**
     * The decoded WAV of a downloaded item's processing (its kept source decoded) moves into the item:
     * like [promoteRecording], into the existing entry instead of a new one. When [decodedAll], the WAV
     * holds the whole source, which is then no longer needed. Never throws; null on failure.
     */
    fun promoteInto(entry: Entry, wav: File, durationSec: Int, decodedAll: Boolean): Entry? = runCatching {
        val dest = File(entry.dir, WAV_NAME)
        if (!wav.renameTo(dest)) { wav.copyTo(dest, overwrite = true); wav.delete() }
        if (decodedAll) File(entry.dir, IMPORT_SOURCE).delete()
        synchronized(metaLock) {
            val cur = readMeta(entry.dir) ?: entry
            // Stopped mid-decode: the row keeps the source's length (Resume decodes all of it), see InFlightImport.save.
            cur.copy(durationSec = if (decodedAll || durationSec > cur.durationSec) durationSec else cur.durationSec, status = Status.RECORDED)
                .also { writeMeta(it) }
        }.let { readMeta(entry.dir) ?: it }
    }.onFailure { Log.w(TAG, "could not move the decoded audio into ${entry.id}", it) }.getOrNull()

    private fun writeMeta(entry: Entry) = synchronized(metaLock) {
        val o = JSONObject()
            .put("id", entry.id)
            .put("createdAt", entry.createdAt)
            .put("durationSec", entry.durationSec)
            .put("status", entry.status.name)
            .put("noSpeech", entry.noSpeech)
        entry.title?.let { o.put("title", it) }
        entry.captureStart?.let { o.put("captureStart", it) }
        File(entry.dir, META).writeTextAtomic(o.toString())
    }

    private fun readMeta(dir: File): Entry? = synchronized(metaLock) {
        val f = File(dir, META)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val title = if (o.has("title")) o.getString("title") else null
            val status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.RECORDED)
            Entry(
                id = o.optString("id", dir.name),
                dir = dir,
                title = title,
                createdAt = o.getLong("createdAt"),
                durationSec = o.optInt("durationSec"),
                status = status,
                // Meta written before the flag: a silent meeting was a DONE entry titled "No speech detected".
                noSpeech = if (o.has("noSpeech")) o.getBoolean("noSpeech")
                    else status == Status.DONE && title in SpeechCheck.LEGACY_TITLES,
                download = File(dir, DOWNLOAD).takeIf { it.isFile }?.let { f ->
                    runCatching { PendingDownload.fromJson(JSONObject(f.readText())) }.getOrNull()
                },
                sourceOnly = status == Status.RECORDED && File(dir, IMPORT_SOURCE).isFile && !File(dir, WAV_NAME).exists(),
                captureStart = if (o.has("captureStart")) o.getLong("captureStart") else null,
            )
        }.onFailure { Log.w(TAG, "corrupt library meta in ${dir.name}", it) }.getOrNull()
    }
}
