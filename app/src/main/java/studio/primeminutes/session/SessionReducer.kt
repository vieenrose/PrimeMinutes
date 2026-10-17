package studio.primeminutes.session

import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.io.File
import studio.primeminutes.R
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.llm.MeetingNotes
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.data.SpeakerEdits
import studio.primeminutes.data.SpeakerName
import studio.primeminutes.service.TranscriptionService

/** Localized strings for the reducer (the status line is text made when its event happens). */
interface SessionStrings {
    fun str(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String
}

/** What the reducer may read besides the state: strings, and where the library lives. */
class SessionEnv(val strings: SessionStrings, val libraryRoot: File) {
    fun str(@StringRes id: Int, vararg args: Any) = strings.str(id, *args)

    /** [SessionLibrary.entryDirOf] without a Context: the entry dir a file Uri lies in, if any. */
    fun entryDirOf(uri: Uri): File? =
        uri.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile }?.takeIf { it.parentFile == libraryRoot }
}

/** A transition's result: the new state and what to do about it, in order. */
data class Next(val state: SessionState, val effects: List<SessionEffect> = emptyList())

/** A session file that was read, ready to be shown (see [SessionReducer.sessionOpened]). */
data class OpenedSession(
    val source: Uri,
    val loaded: PrimeMinutesSession.Loaded,
    /** A home-screen rename (entry meta) outranks the title embedded in the file. */
    val metaTitle: String?,
    /** Where Save writes: the opened file itself when we hold WRITE on it. */
    val saveUri: Uri?,
    val cover: android.graphics.Bitmap?,
)

/**
 * The session screen's state machine: every change to [SessionState] — a pipeline event, a
 * hand-edit, starting/stopping a run, opening a session — as a pure function. No IO, no Android
 * services: what has to happen outside comes back as [SessionEffect]s. The comments carry the
 * reasons each rule exists (they used to sit beside the same code in MainActivity).
 */
object SessionReducer {

    /** A transition under construction: chained helpers share one state and one effect list. */
    private class Tx(val start: SessionState, val env: SessionEnv) {
        var s = start
        val fx = ArrayList<SessionEffect>()
        fun emit(e: SessionEffect) { fx += e }
        fun str(@StringRes id: Int, vararg args: Any) = env.str(id, *args)
        fun next(): Next {
            // A run starting clears the error colour of the previous one's status.
            if (!start.running && s.running && s.statusIsError) s = s.copy(statusIsError = false)
            return Next(s, fx.toList())
        }
    }

    private inline fun tx(s: SessionState, env: SessionEnv, block: Tx.() -> Unit): Next = Tx(s, env).apply(block).next()

    fun initial(config: TranscriptionConfig, strings: SessionStrings): SessionState =
        SessionState(config = config, status = strings.str(R.string.empty_status))

    // ---------------------------------------------------------------------------------------------
    // Starting, stopping and leaving sessions
    // ---------------------------------------------------------------------------------------------

    /** Flush the open session's edits back into its library entry (see [SessionEffect.Persist]). */
    fun persistEdits(state: SessionState, env: SessionEnv): Next = tx(state, env) { persist() }

    private fun Tx.persist() {
        // No-op unless the session is library-bound, dirty, and idle: a running pipeline writes its
        // own results at completion, and the service ignores requests for deleted entries.
        if (!s.sessionDirty || s.running || s.isRecording) return
        val id = s.libraryDir?.name ?: return
        if (s.utterances.isEmpty()) return
        emit(SessionEffect.Persist(
            entryId = id, audioUri = s.audioUri, utterances = s.utterances, speakerNames = s.speakerNames,
            summary = s.summary, actionItems = s.actionItems, title = s.title, notes = s.meetingNotes?.render(),
            asrModelId = s.sessionModels?.second ?: s.config.asrModelId,
            asrBackend = s.sessionModels?.third ?: s.config.asrBackend,
            llmModelId = s.sessionModels?.first ?: s.config.llmModelId,
        ))
        s = s.copy(sessionDirty = false)
    }

    /**
     * ONE place to tear down the open session and every pending-intent flag — launchAudio,
     * beginRecording and row-deletion all reset through here.
     */
    fun clearSession(state: SessionState, env: SessionEnv): Next = tx(state, env) { clear() }

    private fun Tx.clear() {
        persist()   // don't drop edits when another session/recording takes over
        emit(SessionEffect.ClearAutosave)
        emit(SessionEffect.AgentReset(whole = false))
        // Also PAUSE the player, not just the flag: when the next run reuses the SAME audioUri
        // (Re-transcribe) the player is not rebuilt, and the old audio would keep playing.
        emit(SessionEffect.PausePlayer)
        s = s.copy(
            utterances = emptyList(), speakerNames = emptyMap(), editingIndex = -1, editingSpeakerId = null,
            liveStable = 0,
            sessionModels = null,   // a new run is made with the current settings
            editingTitle = false, editingSummary = false, editingActions = false,
            // Every non-recording run through here SUPERSEDES the service job, so a backgrounded
            // capture is genuinely over; beginRecording re-sets isRecording right after.
            isRecording = false, micLevel = 0f,
            title = null, summary = null, actionItems = null, meetingNotes = null,
            searchActive = false, searchQuery = "",
            sessionDirty = false, statusIsError = false,
            coverEnabled = true,
            showPodcastSheet = false, showConfigSheet = false,
            showAddSourceSheet = false, showYouTubeSheet = false, showExportSheet = false,
            lastSaveUri = null, coverBitmap = null, coverFromSession = false,
            summaryStale = false, transcriptStale = false, transcriptDirty = false, titleEdited = false,
            sessionGen = s.sessionGen + 1,
            watchingQueue = false, pendingNextTalk = false, pendingAutoProcess = false, downloadEntryId = null,
            // deferStopped must reset here: a ⏹ Stop&save's terminal event goes to the QUEUE, so a
            // stale true would tear a later recording's run down before its summary phase.
            deferStopped = false, exporting = false,
        )
    }

    /**
     * Start a run from any audio Uri (SAF pick, share, podcast download): reset session + go.
     * [sourceTitle] — the name the audio arrived with (podcast episode, YouTube video) — is pinned
     * like a user edit so the summarizer's guess never replaces it.
     */
    fun launchAudio(state: SessionState, env: SessionEnv, uri: Uri, sourceTitle: String? = null): Next =
        tx(state, env) { launch(uri, sourceTitle) }

    private fun Tx.launch(uri: Uri, sourceTitle: String?) {
        emit(SessionEffect.ApplyConfig(s.config))   // apply settings to this run
        clear()
        val dir = env.entryDirOf(uri)   // non-null when re-running a library capture
        // A downloaded podcast / YouTube item processed from its kept source is an import, not a capture
        // (no ⏭ Next talk; the queue must not supersede it).
        val downloaded = uri.lastPathSegment == SessionLibrary.IMPORT_SOURCE
        s = s.copy(
            libraryDir = dir, recordingRun = dir != null && !downloaded,
            screen = Screen.Session,   // watch the import/transcription live
            running = true, transcriptReady = false, progress = 0f, status = str(R.string.status_starting), audioUri = uri,
        )
        // AFTER clear(), which resets both — otherwise the reset wipes the inherited name.
        sourceTitle?.takeIf { it.isNotBlank() }?.let { s = s.copy(title = it.trim(), titleEdited = true) }
        emit(SessionEffect.StartImport(uri, s.sessionGen))
    }

    /** Live recording (mic → streaming ASR; the notes are written as it goes). */
    fun beginRecording(state: SessionState, env: SessionEnv): Next = tx(state, env) { record() }

    private fun Tx.record() {
        if (s.isRecording) return   // double-tap / racing next-talk: one live capture at a time
        emit(SessionEffect.ApplyConfig(s.config))
        clear()
        s = s.copy(
            audioUri = null, libraryDir = null, recordingRun = true, running = true, transcriptReady = false,
            isRecording = true, progress = 0f, screen = Screen.Capture, status = str(R.string.status_recording),
        )
        emit(SessionEffect.StartRecording(s.sessionGen))
    }

    /** "Next talk": end this capture with deferred processing, then roll into the next one. */
    fun nextTalk(state: SessionState, env: SessionEnv): Next = tx(state, env) {
        if (s.isRecording) {
            s = s.copy(pendingNextTalk = true, deferStopped = true, isRecording = false, status = str(R.string.status_saved_for_later))
            emit(SessionEffect.StopRecordingDefer)
        } else if (s.running) {
            // Post-stop processing of an auto-saved capture: skip the wait — the new recording
            // supersedes the old job; the talk stays RECORDED and the queue picks it up.
            record()
        }
    }

    /**
     * Stop. Recording → stop always defers: the capture is saved and finished in place (the queue
     * holds it as a safety net). Anything else CANCELS the run: the service emits no terminal
     * event, so `running` is cleared here.
     */
    fun stop(state: SessionState, env: SessionEnv): Next = tx(state, env) {
        if (s.isRecording) {
            s = s.copy(pendingAutoProcess = true, isRecording = false, status = str(R.string.status_processing), screen = Screen.Session)
            emit(SessionEffect.StopRecording)
        } else {
            emit(SessionEffect.StopRun)
            s = s.copy(running = false, status = str(R.string.status_stopped))
            // A stopped re-summarize keeps the summary it was replacing (it is still on disk).
            s.resummaryUndo?.takeIf { s.summary == null }?.let { u ->
                s = s.copy(summary = u.summary, meetingNotes = u.notes, title = u.title, titleEdited = u.titleEdited)
                emit(SessionEffect.AgentReset(whole = null))
                u.journal?.let { emit(SessionEffect.AgentRestore(it)) }
            }
            s = s.copy(resummaryUndo = null)
        }
    }

    /**
     * The service no longer runs anything for this session's run, yet no terminal event came: it
     * was stopped from outside the screen (the notification's Stop, the system's time limit). The
     * session settles like a Stop from the screen, without asking the service again.
     */
    fun runEnded(state: SessionState, env: SessionEnv): Next = tx(state, env) {
        if (!(s.running || s.isRecording) || s.watchingQueue) return@tx
        // A failed reading said why on the status line (and sent no terminal event): keep that.
        val failed = s.status.startsWith(str(R.string.svc_summary_failed))
        s = s.copy(
            running = false, isRecording = false, micLevel = 0f,
            status = if (failed) s.status else str(R.string.status_stopped),
            pendingNextTalk = false, pendingAutoProcess = false, deferStopped = false,
        )
        // The booth's timer and buttons are dead now.
        if (s.screen == Screen.Capture) s = s.copy(screen = Screen.Studio)
        s.resummaryUndo?.takeIf { s.summary == null }?.let { u ->
            s = s.copy(summary = u.summary, meetingNotes = u.notes, title = u.title, titleEdited = u.titleEdited)
            emit(SessionEffect.AgentReset(whole = null))
            u.journal?.let { emit(SessionEffect.AgentRestore(it)) }
        }
        s = s.copy(resummaryUndo = null)
    }

    /**
     * The queue item this screen watches gets nothing more: the drain was stopped from outside the
     * screen (the notification's Stop) or ended, and no reading of the queue is left. No terminal
     * event came for it, so the view settles like [runEnded] (which leaves watched items alone: their
     * runs are the queue's, not this session's generation).
     */
    fun queueEnded(state: SessionState, env: SessionEnv): Next = tx(state, env) {
        if (!s.watchingQueue || !s.running) return@tx
        val failed = s.status.startsWith(str(R.string.svc_summary_failed))
        s = s.copy(running = false, watchingQueue = false, status = if (failed) s.status else str(R.string.status_stopped))
    }

    /**
     * Back to Studio (system Back or the top bar). Leaving a watched queue item must release the
     * borrowed `running`, or Studio paints a phantom "processing" banner that never clears.
     */
    fun leaveToStudio(state: SessionState, env: SessionEnv): Next = tx(state, env) {
        if (s.watchingQueue) s = s.copy(running = false)
        if (s.screen == Screen.Session) persist()
        s = s.copy(watchingQueue = false, downloadEntryId = null, screen = Screen.Studio)
    }

    /**
     * Live view of the queue's current item: adopt its buffered results and keep streaming.
     * [pendingTranscript] is what the ASR pass left on disk, shown when the live buffer is empty
     * (the summary pass of an item transcribed earlier).
     */
    fun watchQueueItem(state: SessionState, env: SessionEnv, entry: SessionLibrary.Entry, pendingTranscript: List<TranscriptEvent.Utterance>?): Next = tx(state, env) {
        emit(SessionEffect.ShowPaused)
        s = s.copy(
            utterances = s.queueUtterances.ifEmpty { pendingTranscript.orEmpty() },
            speakerNames = emptyMap(), editingIndex = -1, editingSpeakerId = null,
            editingTitle = false, editingSummary = false, editingActions = false,
            title = s.queueTitle, summary = s.queueSummary, actionItems = null, meetingNotes = null,
            searchActive = false, searchQuery = "",
            coverEnabled = true, coverBitmap = null, coverFromSession = false, lastSaveUri = null,
            summaryStale = false, transcriptStale = false, transcriptDirty = false, titleEdited = false,
            sessionDirty = false,
            sessionGen = s.sessionGen + 1,   // stale events from any prior session run are dropped
            // NOT a recording run: a view that merely WATCHES a drain must not offer ⏭ next talk.
            libraryDir = entry.dir, recordingRun = false,
            audioUri = Uri.fromFile(entry.wavFile),   // the synced player works while it processes
            transcriptReady = false, running = true, progress = s.queueFraction,
            status = s.queueLabel.ifBlank { str(R.string.status_processing) },
            watchingQueue = true, downloadEntryId = null, screen = Screen.Session,
        )
    }

    /**
     * A podcast / YouTube item still downloading (or whose download failed) was opened: its screen shows
     * the download (state, Resume / Retry / Delete) until the audio is there and processing starts, which
     * turns it into the live view. Like [watchQueueItem], it does not stop a run of the screen's own.
     */
    fun openDownload(state: SessionState, env: SessionEnv, entry: SessionLibrary.Entry): Next = tx(state, env) {
        if (s.screen == Screen.Session) persist()
        emit(SessionEffect.ShowPaused)
        s = s.copy(
            utterances = emptyList(), speakerNames = emptyMap(), editingIndex = -1, editingSpeakerId = null,
            editingTitle = false, editingSummary = false, editingActions = false,
            title = entry.title, summary = null, actionItems = null, meetingNotes = null,
            searchActive = false, searchQuery = "",
            coverEnabled = true, coverBitmap = null, coverFromSession = false, lastSaveUri = null,
            summaryStale = false, transcriptStale = false, transcriptDirty = false, titleEdited = false,
            sessionDirty = false, sessionGen = s.sessionGen + 1,
            libraryDir = entry.dir, recordingRun = false, audioUri = null,
            transcriptReady = false, running = false, progress = 0f, status = "",
            watchingQueue = false, downloadEntryId = entry.id, screen = Screen.Session,
        )
    }

    /** A session file was read: show it (it replaces whatever was open). */
    fun sessionOpened(state: SessionState, env: SessionEnv, o: OpenedSession): Next = tx(state, env) {
        val loaded = o.loaded
        val title = o.metaTitle ?: loaded.title
        // Restore the structured sections, or clear them: the PREVIOUS session's must not stay.
        val notes = loaded.notes?.let { MeetingNotes.parse(it) }
        // Or the AI notes journal: rebuild the notes card.
        if (notes == null) loaded.notes?.let { emit(SessionEffect.AgentRestore(it)) }
        emit(SessionEffect.ShowPaused)
        s = s.copy(
            lastSaveUri = o.saveUri,
            utterances = loaded.utterances, speakerNames = loaded.speakerNames,
            editingIndex = -1, editingSpeakerId = null,
            summaryStale = false, transcriptStale = false, transcriptDirty = false,
            sessionGen = s.sessionGen + 1,
            title = title, summary = loaded.summary, actionItems = loaded.actionItems, meetingNotes = notes,
            // A saved title is intentional: a sticky edit re-summarize won't overwrite (but not the
            // "No speech detected" a silent run named the entry after).
            titleEdited = studio.primeminutes.core.library.SpeechCheck.keptTitle(title) != null,
            // Attribute the text to the models that ACTUALLY produced it, not the current default.
            sessionModels = Triple(
                loaded.llmModelId ?: s.config.llmModelId,
                loaded.asrModelId ?: s.config.asrModelId,
                loaded.asrBackend ?: s.config.asrBackend,
            ),
            coverBitmap = o.cover, coverFromSession = o.cover != null, coverEnabled = true,
            running = false, transcriptReady = true, progress = 0f,
            // This path hand-rolls its reset: close the cross-session flags too.
            pendingNextTalk = false, pendingAutoProcess = false, sessionDirty = false,
            audioUri = Uri.fromFile(loaded.audio),
            // A reopened library session keeps its entry binding (derived from the SOURCE uri).
            libraryDir = env.entryDirOf(o.source),
            watchingQueue = false, downloadEntryId = null,
            screen = Screen.Session,
            status = if (loaded.utterances.isEmpty()) ""   // the tabs say "no speech" themselves
                else env.strings.plural(R.plurals.status_session_loaded, loaded.utterances.size, loaded.utterances.size),
        )
    }

    /** Delete library entries: tear the OPEN session down first if it is among them. */
    fun deleteEntries(state: SessionState, env: SessionEnv, victims: List<SessionLibrary.Entry>): Next = tx(state, env) {
        if (victims.isEmpty()) return@tx
        if (victims.any { it.dir == s.libraryDir }) {
            // Its own run has nothing left to save into: stop it.
            if (s.running && !s.watchingQueue && !s.isRecording) emit(SessionEffect.StopRun)
            clear()
            s = s.copy(audioUri = null, libraryDir = null, recordingRun = false, running = false, transcriptReady = false, status = "")
        }
        emit(SessionEffect.DeleteEntries(victims))
    }

    fun enqueueAndStart(state: SessionState, env: SessionEnv, ids: List<String>): Next = tx(state, env) {
        if (ids.isNotEmpty()) emit(SessionEffect.EnqueueAndStart(ids))
    }

    /**
     * Save (to [uri]) or share the session file: built in the service so it finishes even if the
     * app is closed. A save remembers its target, so the next Save overwrites the same file.
     */
    fun stageExport(state: SessionState, env: SessionEnv, share: Boolean, uri: Uri?, format: PrimeMinutesSession.Format): Next = tx(state, env) {
        if (!share) s = s.copy(lastSaveUri = uri)
        emit(SessionEffect.Export(
            share = share, saveUri = uri, audioUri = s.audioUri, utterances = s.utterances, speakerNames = s.speakerNames,
            summary = s.summary, actionItems = s.actionItems, title = s.title, notes = s.meetingNotes?.render(),
            asrModelId = s.sessionModels?.second ?: s.config.asrModelId,
            asrBackend = s.sessionModels?.third ?: s.config.asrBackend,
            llmModelId = s.sessionModels?.first ?: s.config.llmModelId,
            coverEnabled = s.coverEnabled, format = format,
        ))
        s = s.copy(exporting = true)
    }

    // ---------------------------------------------------------------------------------------------
    // Re-runs
    // ---------------------------------------------------------------------------------------------

    /**
     * Re-run only the summary on the current transcript (no re-ASR). The title regenerates too,
     * unless the user hand-renamed it. [journal]: the AI notes journal it replaces (for Undo).
     */
    fun reSummarize(state: SessionState, env: SessionEnv, journal: String?, regenerateTitle: Boolean = !state.titleEdited): Next = tx(state, env) {
        resummarize(journal, regenerateTitle)
    }

    private fun Tx.resummarize(journal: String?, regenerateTitle: Boolean) {
        if (s.running || s.utterances.isEmpty()) return
        // A library session: the service writes the result into its entry itself (the screen may be
        // gone when the reading ends), and a screen opened meanwhile shows the run from this.
        val save = s.libraryDir?.takeIf { !s.watchingQueue }?.let { dir ->
            SessionEffect.Persist(
                entryId = dir.name, audioUri = s.audioUri, utterances = s.utterances, speakerNames = s.speakerNames,
                summary = null, actionItems = s.actionItems,
                // The title stays when it is not regenerated, and is the fallback when it is.
                title = s.title, notes = null,
                asrModelId = s.sessionModels?.second ?: s.config.asrModelId,
                asrBackend = s.sessionModels?.third ?: s.config.asrBackend,
                llmModelId = s.config.llmModelId,
            )
        }
        emit(SessionEffect.ApplyConfig(s.config))
        emit(SessionEffect.AgentReset(whole = true))
        s = s.copy(
            sessionModels = s.sessionModels?.copy(first = s.config.llmModelId),
            resummaryUndo = ResummaryUndo(s.summary, s.meetingNotes, s.title, s.titleEdited, journal),
            summary = null,
            // Stale decisions/open/topics would otherwise stay beside a summary they don't describe.
            meetingNotes = null,
        )
        if (regenerateTitle) s = s.copy(title = null, titleEdited = false)
        s = s.copy(running = true, progress = 0f, status = str(R.string.status_starting))   // transcript persists
        emit(SessionEffect.StartSummarize(
            studio.primeminutes.core.llm.TranscriptFormat.format(s.utterances, s.speakerNames.mapValues { it.value.name }),
            withTitle = regenerateTitle, gen = s.sessionGen, save = save,
        ))
    }

    /** One re-summarize regenerates what a transcript edit or a settings change made stale. */
    fun regenerateStaleChildren(state: SessionState, env: SessionEnv, journal: String?): Next = tx(state, env) {
        if (s.running) return@tx
        if (!s.summary.isNullOrBlank() || s.actionItems != null) resummarize(journal, !s.titleEdited)
    }

    /** The Undo of a finished re-summarize: only on the same session and while idle. */
    fun undoResummary(state: SessionState, env: SessionEnv, u: ResummaryUndo, gen: Int): Next = tx(state, env) {
        if (s.sessionGen != gen || s.running) return@tx
        s = s.copy(summary = u.summary, meetingNotes = u.notes, title = u.title, titleEdited = u.titleEdited, sessionDirty = true)
        emit(SessionEffect.AgentReset(whole = null))
        u.journal?.let { emit(SessionEffect.AgentRestore(it)) }
    }

    // ---------------------------------------------------------------------------------------------
    // Hand-edits
    // ---------------------------------------------------------------------------------------------

    fun beginLineEdit(s: SessionState, index: Int) = s.copy(editingIndex = index, editingSpeakerId = null)
    fun cancelLineEdit(s: SessionState) = s.copy(editingIndex = -1)

    fun saveLineText(s: SessionState, index: Int, text: String): SessionState {
        if (text.isEmpty() || index !in s.utterances.indices) return s
        val utts = s.utterances.toMutableList().also { it[index] = it[index].copy(text = text) }
        // Transcript changed → its summary AND action-items children are now stale.
        val dirtyChildren = !s.summary.isNullOrBlank() || s.actionItems != null
        return s.copy(
            utterances = utts, editingIndex = -1, editSeq = s.editSeq + 1, sessionDirty = true,
            transcriptDirty = s.transcriptDirty || dirtyChildren,
        )
    }

    fun beginSpeakerEdit(s: SessionState, speaker: Int, line: Int) =
        s.copy(editingSpeakerId = speaker, editingSpeakerLine = line, editingIndex = -1)

    fun cancelSpeakerEdit(s: SessionState) = s.copy(editingSpeakerId = null)

    fun commitSpeakerName(s: SessionState, speaker: Int, name: String): SessionState = s.copy(
        speakerNames = if (name.isBlank()) s.speakerNames - speaker else s.speakerNames + (speaker to SpeakerName(name, confidence = "user")),
        sessionDirty = true, editingSpeakerId = null,
    )

    /** Speaker corrections are pure relabels (ids kept, so labels stay put). */
    fun applySpeakerEdit(s: SessionState, result: Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>>): SessionState =
        s.copy(
            utterances = result.first, speakerNames = result.second,
            editingIndex = -1, editingSpeakerId = null,
            sessionDirty = true,   // a merge/move was never saved: nothing marked the session changed
        )

    private fun Tx.speakerEditUndoable(result: Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>>, @StringRes done: Int) {
        val before = s.utterances to s.speakerNames
        s = applySpeakerEdit(s, result)
        emit(SessionEffect.OfferSpeakerUndo(done, s.sessionGen, before, result.first))
    }

    /** Move one line to another speaker (undoable). */
    fun reassignLine(state: SessionState, env: SessionEnv, index: Int, target: Int): Next = tx(state, env) {
        speakerEditUndoable(SpeakerEdits.reassign(s.utterances, s.speakerNames, index, target), R.string.speaker_line_moved)
    }

    /** Merge a speaker into another (undoable: one tap rewrites every line of that speaker). */
    fun mergeSpeaker(state: SessionState, env: SessionEnv, from: Int, into: Int): Next = tx(state, env) {
        speakerEditUndoable(SpeakerEdits.merge(s.utterances, s.speakerNames, from, into), R.string.speaker_merged)
    }

    /** The Undo of a speaker edit: only if nothing changed the transcript since. */
    fun undoSpeakerEdit(s: SessionState, gen: Int, before: Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>>, after: List<TranscriptEvent.Utterance>): SessionState =
        if (s.sessionGen == gen && s.utterances == after) applySpeakerEdit(s, before) else s

    fun saveTitle(s: SessionState, title: String) =
        s.copy(title = title, editingTitle = false, titleEdited = true, editSeq = s.editSeq + 1, sessionDirty = true)

    fun saveSummary(s: SessionState, summary: String) =
        s.copy(summary = summary, editingSummary = false, editSeq = s.editSeq + 1, sessionDirty = true)

    fun saveActions(s: SessionState, actions: String) =
        s.copy(actionItems = actions, editingActions = false, editSeq = s.editSeq + 1, sessionDirty = true)

    /** An in-place Chinese-script re-render finished (still current: same session, no edit since). */
    fun scriptConverted(
        s: SessionState, utterances: List<TranscriptEvent.Utterance>, title: String?, summary: String?,
        actionItems: String?, names: Map<Int, SpeakerName>, changed: Boolean,
    ): SessionState {
        val utts = s.utterances.toMutableList()
        for (i in utterances.indices) if (i < utts.size) utts[i] = utterances[i]
        return s.copy(
            utterances = utts, title = title, summary = summary, actionItems = actionItems,
            speakerNames = s.speakerNames + names,
            sessionDirty = s.sessionDirty || changed,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------

    fun configChanged(state: SessionState, env: SessionEnv, newCfg: TranscriptionConfig): Next = tx(state, env) {
        val old = s.config
        s = s.copy(config = newCfg)
        emit(SessionEffect.SaveConfig(newCfg))
        // Keep the service-visible config in sync IMMEDIATELY (a queue already draining reads it).
        emit(SessionEffect.ApplyConfig(newCfg))
        // A Chinese-script change (繁體↔简体) is ONLY a re-render: OpenCC converts every text node
        // in place, with no LLM. Nothing is ever marked stale by it.
        if (newCfg.summaryScript != old.summaryScript && s.utterances.isNotEmpty()) emit(SessionEffect.ApplyScript(newCfg))
        // A summarizer model change needs a re-run of the summary (and its action items).
        if ((!s.summary.isNullOrBlank() || s.actionItems != null) && newCfg.llmModelId != old.llmModelId) {
            s = s.copy(summaryStale = true)
        }
    }

    /** The UI language changed: an idle status line made in the old language is rebuilt (or dropped). */
    fun languageChanged(s: SessionState, env: SessionEnv): SessionState =
        if (!s.running && !s.statusIsError) s.copy(status = if (s.utterances.isEmpty()) env.str(R.string.empty_status) else "")
        else s

    // ---------------------------------------------------------------------------------------------
    // Pipeline events
    // ---------------------------------------------------------------------------------------------

    /**
     * One event from the service. [gen] is the run generation it is tagged with; [queueItemId] the
     * library entry the queue drain processes right now (read when the event arrives).
     */
    fun onEvent(state: SessionState, env: SessionEnv, gen: Int, e: TranscriptEvent, queueItemId: String?): Next = tx(state, env) {
        // Queue-drain events drive the Studio list's per-row status AND the live buffers; they only
        // touch the open session when the user is WATCHING that item.
        if (gen == TranscriptionService.QUEUE_GEN) {
            if (queueItemId != null && queueItemId != s.queueItemId) {
                // A new item started — reset the live buffers to it, and stop forwarding: item B's
                // transcript must not stream into item A's open session view.
                // The watched item's view keeps what it showed, but nothing feeds it any more: it is
                // not running (left `running`, it stayed "in progress" for good, and Studio painted a
                // phantom processing banner, since it no longer counted as a watched queue item).
                if (s.watchingQueue) s = s.copy(running = false)
                s = s.copy(
                    watchingQueue = false, queueUtterances = emptyList(), queueTitle = null, queueSummary = null,
                    queueItemId = queueItemId, queueFraction = 0f,
                )
                emit(SessionEffect.QueueAgentReset(whole = true))
            }
            when (e) {
                is TranscriptEvent.Status -> s = s.copy(queueLabel = e.message)
                is TranscriptEvent.Progress -> s = s.copy(queueFraction = e.fraction)
                is TranscriptEvent.DownloadProgress -> s = s.copy(queueLabel = e.label, queueFraction = e.fraction)
                is TranscriptEvent.Utterance -> s = s.copy(queueUtterances = s.queueUtterances + e)
                is TranscriptEvent.UtteranceSnapshot -> s = s.copy(queueUtterances = e.utterances)
                is TranscriptEvent.Title -> s = s.copy(queueTitle = e.title)
                is TranscriptEvent.Partial -> s = s.copy(queueSummary = if (e.reset) "" else (s.queueSummary ?: "") + e.chunk)
                is TranscriptEvent.SummaryComplete -> s = s.copy(queueSummary = e.summary)
                is TranscriptEvent.Agent -> emit(SessionEffect.QueueAgentApply(e.event))
                else -> Unit
            }
            if (queueItemId == null) s = s.copy(queueItemId = null, queueFraction = 0f)
            // Not watching → the list row is the only consumer.
            if (!s.watchingQueue) return@tx
        }
        // A superseded run's still-buffered events must not mutate the current session (untagged
        // events — e.g. export — always apply; QUEUE_GEN reaches here only in watch mode).
        else if (gen != TranscriptionService.UNTAGGED && gen != s.sessionGen) return@tx
        sessionEvent(gen, e)
    }

    private fun Tx.sessionEvent(gen: Int, e: TranscriptEvent) {
        when (e) {
            is TranscriptEvent.Status -> s = s.copy(status = e.message)
            is TranscriptEvent.Utterance -> s = s.copy(utterances = s.utterances + e)
            is TranscriptEvent.UtteranceSnapshot -> s = s.copy(utterances = e.utterances, liveStable = e.stable)
            // Progress drives the BAR only; each phase sets its own status. `running` guards a late
            // event after completion.
            is TranscriptEvent.Progress -> if (s.running) s = s.copy(progress = e.fraction)
            is TranscriptEvent.MicLevel -> s = s.copy(micLevel = e.level)
            // Only while a run is active — a buffered download event just after Stop must not re-stick the UI.
            is TranscriptEvent.DownloadProgress -> if (s.running) s = s.copy(progress = e.fraction, status = e.label)
            is TranscriptEvent.Complete -> {
                // A defer-stopped recording's terminal event is Complete (no summary phase follows).
                if (s.deferStopped) s = s.copy(deferStopped = false, running = false)
                // Preserve in-flight text edits keyed by START TIME, not index: diarization can split
                // and re-index utterances, so an index map would reapply edits to shifted lines.
                val edited = s.utterances.associate { it.startSec to it.text }
                val merged = e.utterances.map { inc -> edited[inc.startSec]?.let { inc.copy(text = it) } ?: inc }
                s = s.copy(
                    utterances = merged, editingIndex = -1, editingSpeakerId = null,
                    transcriptReady = true,   // final transcript exists → Re-run becomes available
                    status = e.speakerCount?.let { str(R.string.status_transcript_lines_speakers, merged.size, it) }
                        ?: str(R.string.status_transcript_lines, merged.size),
                )
                // An empty transcript returns WITHOUT summarizing: no SummaryComplete will clear `running`.
                if (!studio.primeminutes.core.library.SpeechCheck.hasSpeech(merged.map { it.text })) s = s.copy(running = false, status = str(R.string.status_no_speech))
            }
            is TranscriptEvent.Title -> {
                // FILL, never OVERWRITE: a title already on screen (podcast/YouTube name, the library
                // entry's own, the user's) is better than the summarizer's guess.
                if (s.title.isNullOrBlank()) {
                    s = s.copy(title = e.title)
                    if (s.libraryDir != null && !s.watchingQueue) s = s.copy(sessionDirty = true)
                }
            }
            is TranscriptEvent.RecordingSaved -> recordingSaved(Uri.parse(e.uri))
            // The finished session replaced the raw-capture row.
            is TranscriptEvent.LibrarySaved -> {
                s = s.copy(recentsVersion = s.recentsVersion + 1, queueItemId = null, queueFraction = 0f)
                // Playing this entry's raw WAV → swap to the durable session.m4a (same audio, playhead
                // carried): the WAV is reclaimed later and would strand the player on a deleted file.
                val savedUri = Uri.parse(e.uri)
                val entryDir = env.entryDirOf(savedUri)
                if (entryDir != null && s.audioUri?.let { env.entryDirOf(it) } == entryDir) {
                    emit(SessionEffect.CarryPlayhead(savedUri))
                    s = s.copy(audioUri = savedUri)
                }
            }
            is TranscriptEvent.Partial -> s = s.copy(summary = if (e.reset) "" else (s.summary ?: "") + e.chunk)
            is TranscriptEvent.SummaryComplete -> {
                // A finished re-summarize replaced the old summary (possibly hand-edited): offer it back.
                s.resummaryUndo?.takeIf { !it.summary.isNullOrBlank() && s.screen == Screen.Session }?.let { u ->
                    emit(SessionEffect.OfferResummaryUndo(u, s.sessionGen))
                }
                s = s.copy(resummaryUndo = null, summary = e.summary, status = str(R.string.status_done), running = false)
                if (s.libraryDir != null && !s.watchingQueue) s = s.copy(sessionDirty = true)
            }
            // Queue events were already folded into the queue's panel (the view shows that one).
            is TranscriptEvent.Agent -> if (gen != TranscriptionService.QUEUE_GEN) emit(SessionEffect.AgentApply(e.event))
            is TranscriptEvent.ActionItemsComplete -> {
                s = s.copy(actionItems = e.text.ifBlank { "-" }, status = str(R.string.status_done), running = false)
                if (s.libraryDir != null && !s.watchingQueue) s = s.copy(sessionDirty = true)
            }
            is TranscriptEvent.NotesComplete -> s = s.copy(meetingNotes = e.notes)
            is TranscriptEvent.Failed -> {
                val msg = str(R.string.status_error, e.error)
                // pendingNextTalk: the capture wasn't saved → don't roll into a new recording.
                s = s.copy(pendingNextTalk = false, pendingAutoProcess = false, isRecording = false)
                // The snackbar host lives in the Session scaffold: elsewhere a Toast reaches the user.
                if (s.screen != Screen.Session) emit(SessionEffect.Toast(msg))
                s = s.copy(status = msg, statusIsError = true, running = false)
                // The capture is over: leave the booth, whose timer and buttons are dead now.
                if (s.screen == Screen.Capture) s = s.copy(screen = Screen.Studio)
                // One-tap Retry for the same source, only in the Session it belongs to.
                val src = s.audioUri
                if (s.screen == Screen.Session && src != null) emit(SessionEffect.OfferRetry(msg, src, s.sessionGen))
            }
            is TranscriptEvent.ExportDone -> {
                s = s.copy(exporting = false)
                emit(SessionEffect.ExportFinished(e, s.lastSaveUri, s.title, s.screen))
            }
            else -> Unit
        }
    }

    private fun Tx.recordingSaved(newUri: Uri) {
        // End-of-ASR source swap (original file → decoded WAV of the SAME audio): keep the player
        // where it was. (For a live recording audioUri was null: the first player starts at 0.)
        if (s.audioUri != null && newUri != s.audioUri) emit(SessionEffect.CarryPlayhead(newUri))
        s = s.copy(audioUri = newUri)
        // Capture ended without the screen's own button (the notification's "Stop & save").
        if (s.isRecording && s.screen == Screen.Capture && !s.pendingNextTalk) s = s.copy(screen = Screen.Session)
        val savedDir = env.entryDirOf(newUri)
        // Auto-saved into the library: refresh the home list and bind the session to its entry.
        s = s.copy(isRecording = false, micLevel = 0f, libraryDir = savedDir ?: s.libraryDir, recentsVersion = s.recentsVersion + 1)
        // ⏹ Stop & save / ⏭ Next talk: the service queued the capture before saying it is saved
        // (EXTRA_QUEUE_ON_SAVE), so that holds even when this screen is gone by then.
        if (s.pendingAutoProcess) s = s.copy(pendingAutoProcess = false)
        // "Next talk": the previous capture is confirmed safe — roll straight into the next
        // recording, but only while the user is STILL in the capture flow.
        if (s.pendingNextTalk) {
            s = s.copy(pendingNextTalk = false)
            if (s.screen == Screen.Capture) record()
        }
    }
}
