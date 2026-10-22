package studio.primeminutes.session

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.primeminutes.R
import studio.primeminutes.core.config.AppLanguage
import studio.primeminutes.core.config.ConfigStore
import studio.primeminutes.core.config.SummaryScript
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.core.session.RecentSessions
import studio.primeminutes.core.session.SessionAutosave
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import studio.primeminutes.service.RunRegistry
import studio.primeminutes.service.TranscriptionService
import studio.primeminutes.ui.AgentUiState

/**
 * What the session screen needs the Activity for: the service starts (they ask for the battery
 * exemption first), the player, and choosers. Attached while the screen is composed.
 */
interface SessionUi {
    fun startImport(uri: Uri, gen: Int)
    fun startRecording(gen: Int)
    fun stopRun()
    fun processQueue()
    /** Pause a playing track and show it paused. */
    fun pausePlayer()
    /** Show the player paused (its source is about to change). */
    fun showPaused()
    fun carryPlayhead(uri: Uri)
    fun startActivity(intent: Intent)
}

/**
 * The session screen's state holder: one [SessionState] in a StateFlow, changed only through
 * [SessionReducer]; this class runs the effects (service requests, file IO, feedback) and the
 * coroutines that end in a transition (opening a session, re-rendering the Chinese script).
 *
 * Composables read [state] and call the intent functions below; they keep only view state
 * (scroll position, the player, search-box matches, dialogs about app-level recovery).
 */
class SessionViewModel internal constructor(app: Application, testStrings: SessionStrings?) : AndroidViewModel(app) {

    /** What the ViewModel factory calls. */
    constructor(app: Application) : this(app, null)

    private val ctx get() = getApplication<Application>()

    /** Strings in the language chosen in Settings, like the screen's own (read on every call). */
    private val strings = testStrings ?: object : SessionStrings {
        private var code: String? = null
        private var res: android.content.res.Resources? = null
        private fun res(): android.content.res.Resources {
            val c = AppLanguage.load(ctx)
            if (res == null || c != code) { res = AppLanguage.resourcesFor(ctx, c); code = c }
            return res!!
        }
        override fun str(id: Int, vararg args: Any): String = res().getString(id, *args)
        override fun plural(id: Int, n: Int, vararg args: Any): String = res().getQuantityString(id, n, *args)
    }
    private val env = SessionEnv(strings, SessionLibrary.root(app))

    private val _state = MutableStateFlow(
        SessionReducer.initial(ConfigStore.load(app).also { TranscriptionConfig.Holder.config = it }, strings),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()
    /** The current state (not the last one composed): intent functions decide on this. */
    val now: SessionState get() = _state.value

    /** The meeting-reading agent's live state (status, streamed reply, notes, activity log). */
    val agent = AgentUiState()
    /** The queue drain's agent, folded even while nobody watches: opening a queued item mid-summary
     *  shows the whole trace so far. */
    val queueAgent = AgentUiState()
    /** The one snackbar host (the Session scaffold shows it). */
    val snackbar = SnackbarHostState()

    var ui: SessionUi? = null

    init {
        studio.primeminutes.core.people.PeopleStore.init(app)
        studio.primeminutes.core.people.VoiceStore.init(app)
        studio.primeminutes.core.people.VoiceStore.catchUp(app)
        viewModelScope.launch { studio.primeminutes.core.people.PeopleStore.changed.collect { relinkFromStore(it) } }
        viewModelScope.launch {
            studio.primeminutes.core.people.PeopleStore.doc.map { d -> d.people.associate { it.id to it.name } }
                .distinctUntilChanged().collect { relinkFromStore(null) }
        }
        viewModelScope.launch {
            // Subscribe, then catch up on what the service already did (see RunRegistry.attach):
            // events up to the snapshot come from its replay, the ones after from the bus.
            var caughtUp = Long.MAX_VALUE
            RunRegistry.events
                .onSubscription { caughtUp = attach(RunRegistry.attach()) }
                .collect { st ->
                    if (st.seq <= caughtUp) return@collect
                    apply(SessionReducer.onEvent(now, env, st.gen, st.event, RunRegistry.currentQueueItemId))
                }
        }
        viewModelScope.launch {
            // A run stopped outside this screen (the notification's Stop, the system's time limit)
            // sends no terminal event: notice its gen leaving the service's work instead. The gen
            // must have been seen working first (a start takes a moment to reach the service), and
            // a short grace lets the run's last events, emitted just before, land first.
            var seen = Int.MIN_VALUE
            RunRegistry.jobs.collectLatest { j ->
                val gen = now.sessionGen
                if (gen in j.activeSessionGens) { seen = gen; return@collectLatest }
                if (seen != gen) return@collectLatest
                delay(RUN_END_GRACE_MS)
                if (now.sessionGen == gen && gen !in RunRegistry.jobs.value.activeSessionGens) {
                    apply(SessionReducer.runEnded(now, env))
                }
            }
        }
        viewModelScope.launch {
            // A watched queue item has no generation of its own in the service's work: it settles when
            // the queue has nothing left going on (the notification's Stop, or the drain ending without
            // a terminal event for it), after the same grace for the last events.
            combine(
                RunRegistry.jobs.map { it.queueWorking }.distinctUntilChanged(),
                _state.map { it.watchingQueue && it.running }.distinctUntilChanged(),
            ) { working, watching -> watching && !working }
                .distinctUntilChanged()
                .collectLatest { idle ->
                    if (!idle) return@collectLatest
                    delay(RUN_END_GRACE_MS)
                    if (!RunRegistry.jobs.value.queueWorking) apply(SessionReducer.queueEnded(now, env))
                }
        }
    }

    /**
     * Re-attach to the service's work in progress (the app reopened, or this screen recreated, while
     * a run goes on): the latest session run that still works and has a source — a recording, an
     * import, a reading of one — and the queue item being processed. Their events are replayed
     * through the reducer onto a session tagged with that run's generation; the user stays on
     * Studio, whose banners lead back to it. Returns the last event number replayed.
     */
    private fun attach(snap: RunRegistry.Snapshot): Long {
        // Number this screen's runs above any the service has seen, so a gen can never collide.
        var s = now.copy(sessionGen = maxOf(now.sessionGen, snap.highestGen + 1))
        val jobs = snap.jobs
        // A re-summarize is shown when it is of a library session (it carries that session).
        val run = jobs.pipeline?.takeIf {
            jobs.pipelineActive && it.gen >= 0 && (it.kind != RunRegistry.Kind.SUMMARIZE || it.resummary != null)
        }
        val gen = jobs.activeSessionGens.sortedDescending().firstOrNull { g ->
            run?.gen == g || snap.sessions[g].orEmpty().any {
                it is TranscriptEvent.Utterance || it is TranscriptEvent.UtteranceSnapshot || it is TranscriptEvent.Complete
            }
        }
        val replayFx = ArrayList<SessionEffect>()
        if (gen != null) {
            val own = run?.takeIf { it.gen == gen }
            val recording = own?.kind == RunRegistry.Kind.RECORDING
            val src = own?.audioUri?.let(Uri::parse)
            val dir = src?.let(env::entryDirOf)
            // A stop already asked of this recording: its capture is winding down.
            val stopping = jobs.captureStop?.takeIf { recording && it.gen == gen }
            s = s.copy(
                sessionGen = gen, running = true, transcriptReady = false, progress = 0f,
                status = strings.str(if (recording && stopping == null) R.string.status_recording else R.string.status_starting),
                audioUri = src, libraryDir = dir, recordingRun = recording || dir != null,
                isRecording = recording && jobs.recordingActive && stopping == null,
                deferStopped = stopping?.nextTalk == true,
            )
            // A re-summarize: the session it re-reads, as it was when it started.
            own?.resummary?.let { r ->
                s = s.copy(
                    utterances = r.utterances, speakerNames = r.speakerNames, transcriptReady = true,
                    libraryDir = File(env.libraryRoot, r.entryId), recordingRun = true,
                    // A title being rewritten is left empty for the new one (titles fill, never overwrite).
                    title = r.title.takeIf { !own.resummaryTitle }, titleEdited = !own.resummaryTitle && !r.title.isNullOrBlank(),
                    actionItems = r.actionItems,
                    sessionModels = Triple(r.llmModelId ?: s.config.llmModelId, r.asrModelId ?: s.config.asrModelId, r.asrBackend ?: s.config.asrBackend),
                )
            }
            for (e in snap.sessions[gen].orEmpty()) {
                val n = SessionReducer.onEvent(s, env, gen, e, null)
                s = n.state; replayFx += n.effects
            }
        }
        snap.queueItemId?.let { item ->
            for (e in snap.queue) {
                val n = SessionReducer.onEvent(s, env, TranscriptionService.QUEUE_GEN, e, item)
                s = n.state; replayFx += n.effects
            }
        }
        _state.value = s
        // Only the AI notes panels are rebuilt: the rest of what those events once asked for
        // (toasts, retries, the player) already happened, or belongs to a screen that is gone.
        replayFx.filter {
            it is SessionEffect.AgentApply || it is SessionEffect.AgentReset || it is SessionEffect.AgentRestore ||
                it is SessionEffect.QueueAgentApply || it is SessionEffect.QueueAgentReset
        }.forEach(::run)
        return snap.seq
    }

    /** When the recording this screen shows started (re-attached ones included), for its timer. */
    fun recordingStartedAt(): Long? = RunRegistry.jobs.value.pipeline
        ?.takeIf { it.kind == RunRegistry.Kind.RECORDING && it.gen == now.sessionGen && it.startedAtMs > 0 }?.startedAtMs

    // ---------------------------------------------------------------------------------------------
    // Transitions
    // ---------------------------------------------------------------------------------------------

    private fun apply(next: Next) {
        val before = _state.value
        _state.value = next.state
        syncSpeakerLinks(before, next.state)
        next.effects.forEach(::run)
        autoPersist()
    }

    private fun update(f: (SessionState) -> SessionState) {
        val before = _state.value
        _state.value = f(before)
        syncSpeakerLinks(before, _state.value)
        autoPersist()
    }

    private var autoPersistJob: kotlinx.coroutines.Job? = null

    /**
     * Every edit of a library meeting is saved within [AUTO_PERSIST_MS] (the edit journal makes the
     * save cheap): a kill right after "Apply" no longer loses it. Edits made during the wait ride along.
     */
    private fun autoPersist() {
        val s = _state.value
        if (!s.sessionDirty || s.libraryDir == null || s.running || s.isRecording) return
        if (autoPersistJob?.isActive == true) return
        autoPersistJob = viewModelScope.launch { kotlinx.coroutines.delay(AUTO_PERSIST_MS); persistEdits() }
    }

    /**
     * A library meeting's names changed by hand (rename, link, merge, move): its speaker sidecar follows
     * at once, so the People screen, the library filter and Ask see it before the session file is rebuilt.
     * Not while a run of it goes on (the run saves its own result) nor when another session was opened.
     */
    private fun syncSpeakerLinks(a: SessionState, b: SessionState) {
        if (a.speakerNames == b.speakerNames || b.sessionGen != a.sessionGen || b.running || b.isRecording) return
        val dir = b.libraryDir ?: return
        if (b.utterances.isEmpty()) return
        val names = b.speakerNames
        viewModelScope.launch(Dispatchers.IO) { studio.primeminutes.core.people.PeopleStore.saveNames(dir, names) }
    }

    // ---------------------------------------------------------------------------------------------
    // People (docs/design/speaker-tags.md): the rename sheet's choices
    // ---------------------------------------------------------------------------------------------

    /** [speaker] is [personId] of the directory (an undoable relabel, like a merge). */
    fun linkPerson(speaker: Int, personId: String) {
        val p = studio.primeminutes.core.people.PeopleStore.doc.value.byId(personId) ?: return
        linkUndoable(speaker, p.id, p.name)
        viewModelScope.launch(Dispatchers.IO) {
            studio.primeminutes.core.people.PeopleStore.touch(ctx, p.id)
            studio.primeminutes.core.people.VoiceLinks.confirmed(ctx, now.libraryDir?.name, speaker, p.id)
        }
    }

    /** [speaker] is someone new, named [name]: a new person of the directory. */
    fun linkNewPerson(speaker: Int, name: String) {
        val n = name.trim().ifBlank { return }
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { studio.primeminutes.core.people.PeopleStore.create(ctx, n) }
            linkUndoable(speaker, p.id, p.name)
            withContext(Dispatchers.IO) { studio.primeminutes.core.people.VoiceLinks.confirmed(ctx, now.libraryDir?.name, speaker, p.id) }
        }
    }

    /** Back to "Speaker n": the person stays in the directory. */
    fun clearSpeakerName(speaker: Int) {
        val was = now.speakerNames[speaker]?.person
        linkUndoable(speaker, null, "")
        if (was != null) viewModelScope.launch(Dispatchers.IO) { studio.primeminutes.core.people.VoiceLinks.unlinked(ctx, now.libraryDir?.name, speaker, was) }
    }

    private fun linkUndoable(speaker: Int, person: String?, name: String) {
        val before = now.utterances to now.speakerNames
        update { SessionReducer.linkSpeaker(it, speaker, person, name) }
        run(SessionEffect.OfferSpeakerUndo(R.string.people_linked, now.sessionGen, before, now.utterances, nameOnly = true))
    }

    /** A directory edit rewrote this meeting's sidecar, or renamed a person shown here. */
    private fun relinkFromStore(ids: Set<String>?) {
        val s = now
        val dir = s.libraryDir ?: return
        if (s.running || s.isRecording || s.utterances.isEmpty()) return
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                if (ids != null && dir.name in ids) studio.primeminutes.core.people.PeopleStore.readLinks(dir)?.let(studio.primeminutes.core.people.PeopleStore::resolve)
                else studio.primeminutes.core.people.PeopleStore.resolve(studio.primeminutes.core.people.PeopleStore.linksOf(s.speakerNames))
            } ?: return@launch
            if (now.sessionGen != s.sessionGen) return@launch
            // The names changed while this resolved (the user just named a speaker, which may also
            // have created a person and so triggered this): resolve again from the current names,
            // never write a stale snapshot over the user's choice.
            if (now.speakerNames != s.speakerNames) { relinkFromStore(ids); return@launch }
            update { SessionReducer.namesRelinked(it, names) }
        }
    }

    fun persistEdits() = apply(SessionReducer.persistEdits(now, env))
    fun launchAudio(uri: Uri, sourceTitle: String? = null, carry: SpeakerCarry? = null) = apply(SessionReducer.launchAudio(now, env, uri, sourceTitle, carry))
    fun beginRecording() = apply(SessionReducer.beginRecording(now, env))
    fun nextTalk() = apply(SessionReducer.nextTalk(now, env))
    fun stop() = apply(SessionReducer.stop(now, env))
    fun leaveToStudio() = apply(SessionReducer.leaveToStudio(now, env))
    fun deleteEntries(victims: List<SessionLibrary.Entry>) = apply(SessionReducer.deleteEntries(now, env, victims))
    fun enqueueAndStart(ids: List<String>) = apply(SessionReducer.enqueueAndStart(now, env, ids))
    fun reSummarize() = apply(SessionReducer.reSummarize(now, env, agent.journalText()))
    fun regenerateStaleChildren() = apply(SessionReducer.regenerateStaleChildren(now, env, agent.journalText()))
    fun reassignLine(index: Int, target: Int) = apply(SessionReducer.reassignLine(now, env, index, target))
    fun mergeSpeaker(from: Int, into: Int) = apply(SessionReducer.mergeSpeaker(now, env, from, into))
    fun moveBoundary(seam: Int, split: Int) = apply(SessionReducer.moveBoundary(now, env, seam, split))
    fun undoEdit() { update { SessionReducer.undoEdit(it) }; persistEdits() }
    fun redoEdit() { update { SessionReducer.redoEdit(it) }; persistEdits() }
    fun splitLine(index: Int, at: Int, speaker: Int) = apply(SessionReducer.splitLine(now, env, index, at, speaker))
    fun configChanged(cfg: TranscriptionConfig) = apply(SessionReducer.configChanged(now, env, cfg))
    fun languageChanged() = update { SessionReducer.languageChanged(it, env) }

    fun openDownload(e: SessionLibrary.Entry) = apply(SessionReducer.openDownload(now, env, e))

    /** A downloaded podcast / YouTube item: process its kept source, into the item itself (ImportPipeline). */
    fun processDownloaded(e: SessionLibrary.Entry) =
        launchAudio(Uri.fromFile(File(e.dir, SessionLibrary.IMPORT_SOURCE)), e.title)

    fun watchQueueItem(e: SessionLibrary.Entry) {
        // Summary pass: the ASR pass finished earlier, so its live buffer is empty — show the
        // transcript it left on disk instead of a blank screen.
        val pending = if (now.queueUtterances.isEmpty()) SessionLibrary.peekPendingTranscript(e) else null
        apply(SessionReducer.watchQueueItem(now, env, e, pending))
    }

    // --- Hand-edits ---
    fun beginLineEdit(index: Int) = update { SessionReducer.beginLineEdit(it, index) }
    fun cancelLineEdit() = update { SessionReducer.cancelLineEdit(it) }
    fun saveLineText(index: Int, text: String) = update { SessionReducer.saveLineText(it, index, text) }
    fun beginSpeakerEdit(speaker: Int, line: Int) = update { SessionReducer.beginSpeakerEdit(it, speaker, line) }
    fun cancelSpeakerEdit() = update { SessionReducer.cancelSpeakerEdit(it) }
    fun commitSpeakerName(speaker: Int, name: String) = update { SessionReducer.commitSpeakerName(it, speaker, name) }
    fun saveTitle(t: String) = update { SessionReducer.saveTitle(it, t) }
    fun saveSummary(t: String) = update { SessionReducer.saveSummary(it, t) }
    fun saveActions(t: String) = update { SessionReducer.saveActions(it, t) }
    fun editTitle(on: Boolean) = update { it.copy(editingTitle = on) }
    fun editSummary(on: Boolean) = update { it.copy(editingSummary = on) }
    fun editActions(on: Boolean) = update { it.copy(editingActions = on) }

    // --- Plain view switches the session owns (a new session resets them). ---
    fun showScreen(screen: Screen) = update { it.copy(screen = screen) }
    fun setStatus(status: String) = update { it.copy(status = status) }
    fun setExporting(on: Boolean) = update { it.copy(exporting = on) }
    fun clearTranscriptDirty() = update { it.copy(transcriptDirty = false) }
    fun clearTranscriptStale() = update { it.copy(transcriptStale = false) }
    fun clearSummaryStale() = update { it.copy(summaryStale = false) }
    fun bumpRecents() = update { it.copy(recentsVersion = it.recentsVersion + 1) }
    fun setSearch(active: Boolean, query: String = now.searchQuery) = update { it.copy(searchActive = active, searchQuery = query) }
    fun setSearchQuery(q: String) = update { it.copy(searchQuery = q) }
    fun showConfigSheet(on: Boolean) = update { it.copy(showConfigSheet = on) }
    fun showPodcastSheet(on: Boolean) = update { it.copy(showPodcastSheet = on) }
    fun showAddSourceSheet(on: Boolean) = update { it.copy(showAddSourceSheet = on) }
    fun showYouTubeSheet(on: Boolean) = update { it.copy(showYouTubeSheet = on) }
    fun showExportSheet(on: Boolean) = update { it.copy(showExportSheet = on) }
    fun closeSheets() = update {
        it.copy(showConfigSheet = false, showPodcastSheet = false, showAddSourceSheet = false, showYouTubeSheet = false, showExportSheet = false)
    }

    // ---------------------------------------------------------------------------------------------
    // Coroutine-backed intents
    // ---------------------------------------------------------------------------------------------

    /** Monotonic ticket for [openSessionUri] loads (main-thread only). */
    private var openTicket = 0

    /** Open a session file (a library row, a shared/opened .m4a): recover it, or transcribe plain audio. */
    fun openSessionUri(uri: Uri) {
        // Two tickets: the open() below suspends for seconds on a big file, and applying a STALE
        // load afterwards would hijack whatever the user opened/started meanwhile (a live recording
        // included). sessionGen invalidates it when any session-changing action ran; openTicket
        // orders rapid open-vs-open so the LATEST tap wins even if it loads first.
        val myOpen = ++openTicket
        val gen = now.sessionGen
        update { it.copy(opening = true) }
        viewModelScope.launch {
            try {
                // A row whose edits are still being written must not be read mid-way: the old file
                // would come back, and later be saved over the edits.
                SessionLibrary.entryDirOf(ctx, uri)?.name?.let { TranscriptionService.awaitPersist(it) }
                if (openTicket != myOpen || now.sessionGen != gen) return@launch
                val loaded = runCatching { PrimeMinutesSession.open(ctx, uri) }.getOrNull()
                if (openTicket != myOpen || now.sessionGen != gen) return@launch   // superseded — drop
                if (loaded == null) {
                    // Stale entry (file moved / grant revoked) → drop it from Recent and say so.
                    RecentSessions.remove(ctx, uri.toString()); bumpRecents()
                    notify(strings.str(R.string.session_open_failed)); return@launch
                }
                if (!loaded.recovered) {
                    // A plain audio file with no embedded session → transcribe it as a normal source.
                    launchAudio(Uri.fromFile(loaded.audio)); return@launch
                }
                // Persist the grant so this session survives a reboot in the Recent list.
                if (uri.scheme == "content") runCatching {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // If we also hold WRITE on the opened file, Save overwrites IT; otherwise a fresh location.
                val saveUri = uri.takeIf {
                    it.scheme == "content" && runCatching {
                        ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); true
                    }.getOrDefault(false)
                }
                // Not autosaved itself — but clear any PRIOR autosave so a later kill doesn't resurrect it.
                SessionAutosave.clear(ctx)
                // A home-screen rename lives only in the entry's meta until the next persist: it must
                // outrank the (stale) title embedded inside session.m4a.
                val metaTitle = SessionLibrary.entryDirOf(ctx, uri)?.let { dir ->
                    withContext(Dispatchers.IO) { SessionLibrary.byId(ctx, dir.name)?.title }
                }
                // Bounded decode of the EMBEDDED cover: an opened session is an untrusted file.
                val cover = loaded.coverJpeg?.let { decodeBoundedBitmap(it, 1024) }
                apply(SessionReducer.sessionOpened(now, env, OpenedSession(uri, loaded, metaTitle, saveUri, cover)))
                RecentSessions.add(ctx, uri.toString(), loaded.title ?: "", System.currentTimeMillis()); bumpRecents()
            } finally {
                // Clear only if still the current open — a newer one owns the flag otherwise.
                if (openTicket == myOpen) update { it.copy(opening = false) }
            }
        }
    }

    /**
     * Continue a stopped run from its checkpoint. ASR already finished → hand the saved transcript
     * to the queue so only reading + summary run; otherwise re-run the capture with
     * [TranscriptionService.resumeRequested] so ASR continues after the last frozen utterance.
     */
    fun resumeEntry(e: SessionLibrary.Entry) {
        val p = SessionLibrary.loadProgress(e)
        if (p == null) { launchAudio(Uri.fromFile(e.wavFile)); return }
        if (p.asrComplete) {
            SessionLibrary.savePendingTranscript(e, p.utterances, p.fingerprint)
            // The notes of a reading stopped midway stay: the queue's reader continues from them.
            SessionLibrary.clearProgress(e, keepNotes = true)
            enqueueAndStart(listOf(e.id))
        } else {
            TranscriptionService.resumeRequested = true
            launchAudio(Uri.fromFile(e.wavFile))
        }
    }

    /** Re-transcribe IN PLACE: a library session re-runs its own entry's recording. */
    fun reTranscribe() {
        // The title is kept (pinned like a user's), except the "No speech detected" a silent run gave
        // the entry: pinned, it would win over the title of the run that now finds speech.
        val dir = now.libraryDir; val src = now.audioUri ?: return
        val keepTitle = studio.primeminutes.core.library.SpeechCheck.keptTitle(now.title)
        val carry = SpeakerCarry(now.utterances, now.speakerNames).takeIf { it.names.isNotEmpty() }
        viewModelScope.launch {
            // A manual re-run starts over: a stale checkpoint must not be picked up as progress.
            dir?.let { d -> withContext(Dispatchers.IO) { SessionLibrary.byId(ctx, d.name)?.let(SessionLibrary::clearProgress) } }
            // Its audio was opened from a cache copy, which the pipeline would save as a NEW entry
            // (a duplicate row) — so give the entry back its recording.wav and run on that.
            val target = dir?.let { d ->
                withContext(Dispatchers.IO) {
                    val wav = File(d, SessionLibrary.WAV_NAME)
                    if (!wav.exists()) runCatching { studio.primeminutes.core.audio.AudioDecoder.decodeToWav16k(ctx, src, wav) { _, _ -> } }
                    wav.takeIf { it.exists() && it.length() > 44 }
                }
            }
            launchAudio(target?.let { Uri.fromFile(it) } ?: src, keepTitle, carry)
        }
    }

    /** The ⋮ Re-summarize: the reader starts over (saved notes cleared), then one re-summarize. */
    fun reSummarizeFromMenu() {
        val d = now.libraryDir
        viewModelScope.launch {
            d?.let { withContext(Dispatchers.IO) { SessionLibrary.byId(ctx, it.name)?.let(SessionLibrary::clearNotes) } }
            regenerateStaleChildren()
        }
    }

    /** Build+write (or share) the session file in the service, so it finishes even if the app closes. */
    fun stageSessionExport(share: Boolean, uri: Uri?, format: PrimeMinutesSession.Format) =
        apply(SessionReducer.stageExport(now, env, share, uri, format))

    /** Monotonic tickets for [applyChineseScript]: only the latest conversion applies. */
    private var scriptSeq = 0

    /**
     * Re-render every Chinese text node into [newScript] via OpenCC — the cheap path for a pure
     * Traditional↔Simplified switch (no LLM). Applied to ALL nodes, user-edited ones included:
     * conversion preserves wording. [transcriptOnly]: leave generated text alone.
     */
    fun applyChineseScript(newScript: ChineseScript, transcriptOnly: Boolean = false) {
        val s0 = now
        val gen = s0.sessionGen
        val seq = ++scriptSeq
        val edit0 = s0.editSeq
        // Snapshot the inputs NOW (a consistent view), convert off-main, then apply on-main only if
        // still current — no newer conversion, same session, no edit meanwhile.
        val utts0 = s0.utterances
        val title0 = s0.title; val summary0 = s0.summary; val actions0 = s0.actionItems
        val names0 = s0.speakerNames
        val notes0 = agent.notes.toList()
        viewModelScope.launch {
            val cc = withContext(Dispatchers.IO) { OpenCcConverter.get(ctx, newScript) }
            // The transcript uses the CONSERVATIVE s2t (no phrase-level TW localisation) so an in-place
            // re-render matches what a fresh transcription would have produced; generated text keeps
            // the localising converter.
            val ccTranscript = withContext(Dispatchers.IO) {
                if (newScript == ChineseScript.TRADITIONAL) OpenCcConverter.getTranscriptTraditional(ctx)
                else OpenCcConverter.getTranscriptSimplified(ctx)
            }
            val newUtts = withContext(Dispatchers.Default) { utts0.map { it.copy(text = ccTranscript.convert(it.text)) } }
            val newTitle = title0?.takeIf { !transcriptOnly }?.let { withContext(Dispatchers.Default) { cc.convert(it) } } ?: title0
            val newSummary = summary0?.takeIf { !transcriptOnly }?.let { withContext(Dispatchers.Default) { cc.convert(it) } } ?: summary0
            val newActions = actions0?.takeIf { !transcriptOnly }?.let { withContext(Dispatchers.Default) { cc.convert(it) } } ?: actions0
            val newNames = if (transcriptOnly) names0 else
                withContext(Dispatchers.Default) { names0.mapValues { (_, n) -> n.copy(name = cc.convert(n.name)) } }
            // AI notes are generated text too (the reader writes in the summary's script).
            val newNotes = if (transcriptOnly) notes0 else
                withContext(Dispatchers.Default) { notes0.map { it.copy(text = cc.convert(it.text)) } }
            if (seq != scriptSeq || gen != now.sessionGen || edit0 != now.editSeq) return@launch   // superseded / changed / edited
            val changed = newUtts != utts0 || newTitle != title0 || newSummary != summary0 ||
                newActions != actions0 || newNames != names0 || newNotes != notes0
            update { SessionReducer.scriptConverted(it, newUtts, newTitle, newSummary, newActions, newNames, changed) }
            if (newNotes != notes0 && agent.notes.size == newNotes.size) for (i in newNotes.indices) agent.notes[i] = newNotes[i]
        }
    }

    /** Snackbar on the Session screen, Toast elsewhere (the one SnackbarHost lives in the Session scaffold). */
    fun notify(msg: String) {
        if (now.screen == Screen.Session) viewModelScope.launch { snackbar.showSnackbar(msg) }
        else Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------------------------------

    private fun run(e: SessionEffect) {
        when (e) {
            // Without a screen attached (between a recreation's dispose and compose), the service is
            // asked directly; only the one-time battery-exemption prompt needs the Activity.
            is SessionEffect.StartImport -> ui?.startImport(e.uri, e.gen) ?: service(true) {
                if (e.uri.scheme == "content") runCatching {
                    ctx.contentResolver.takePersistableUriPermission(e.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                putExtra(TranscriptionService.EXTRA_AUDIO_URI, e.uri.toString()).putExtra(TranscriptionService.EXTRA_RUN_GEN, e.gen)
            }
            is SessionEffect.StartRecording -> ui?.startRecording(e.gen) ?: service(true) {
                setAction(TranscriptionService.ACTION_RECORD).putExtra(TranscriptionService.EXTRA_RUN_GEN, e.gen)
            }
            SessionEffect.StopRun -> ui?.stopRun() ?: service(false) { setAction(TranscriptionService.ACTION_STOP) }
            // The service queues the capture once it is saved, and publishes the request: neither
            // depends on this screen still being there when the capture is finished.
            SessionEffect.StopRecording -> service(false) {
                setAction(TranscriptionService.ACTION_STOP_RECORDING).putExtra(TranscriptionService.EXTRA_QUEUE_ON_SAVE, true)
            }
            SessionEffect.StopRecordingDefer -> service(false) {
                setAction(TranscriptionService.ACTION_STOP_RECORDING_DEFER).putExtra(TranscriptionService.EXTRA_QUEUE_ON_SAVE, true)
            }
            SessionEffect.ProcessQueue -> ui?.processQueue() ?: service(true) { setAction(TranscriptionService.ACTION_PROCESS_QUEUE) }
            is SessionEffect.StartSummarize -> {
                // The request rides the holder, not an Intent extra (Binder 1 MB limit → crash on long meetings).
                val ticket = TranscriptionService.pendingSummarize.post(TranscriptionService.SummarizeRequest(e.text, e.save?.let { p ->
                    TranscriptionService.PersistRequest(
                        entryId = p.entryId, audioUri = p.audioUri, utterances = p.utterances, speakerNames = p.speakerNames,
                        summary = p.summary, actionItems = p.actionItems, title = p.title, notes = p.notes,
                        asrModelId = p.asrModelId, asrBackend = p.asrBackend, llmModelId = p.llmModelId,
                    )
                }))
                ContextCompat.startForegroundService(ctx, Intent(ctx, TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_SUMMARIZE)
                    .putExtra(TranscriptionService.EXTRA_WITH_TITLE, e.withTitle)
                    .putExtra(TranscriptionService.EXTRA_RUN_GEN, e.gen)
                    .putExtra(TranscriptionService.EXTRA_REQUEST, ticket))
            }
            is SessionEffect.ApplyConfig -> TranscriptionConfig.Holder.config = e.config
            is SessionEffect.SaveConfig -> {
                ConfigStore.save(ctx, e.config)
                // A new recognizer latency: fetch it in the background (consent permitting); the installed one keeps working.
                studio.primeminutes.core.models.ModelPrefetch.maybeStart(ctx)
            }
            // Durable within ms (the edit journal); the session.m4a is rebuilt once the edits settle.
            is SessionEffect.Persist -> studio.primeminutes.service.EditSaver.submit(ctx, TranscriptionService.PersistRequest(
                entryId = e.entryId, audioUri = e.audioUri, utterances = e.utterances, speakerNames = e.speakerNames,
                summary = e.summary, actionItems = e.actionItems, title = e.title, notes = e.notes ?: agent.journalText(),
                asrModelId = e.asrModelId, asrBackend = e.asrBackend, llmModelId = e.llmModelId,
            ))
            is SessionEffect.Export -> {
                val ticket = TranscriptionService.pendingExport.post(TranscriptionService.ExportRequest(
                    share = e.share, saveUri = e.saveUri, audioUri = e.audioUri, utterances = e.utterances,
                    speakerNames = e.speakerNames, summary = e.summary, actionItems = e.actionItems, title = e.title,
                    notes = e.notes ?: agent.journalText(),
                    asrModelId = e.asrModelId, asrBackend = e.asrBackend, llmModelId = e.llmModelId,
                    coverEnabled = e.coverEnabled,
                    fileName = PrimeMinutesSession.suggestFileName(e.title, e.format.ext), format = e.format,
                ))
                ContextCompat.startForegroundService(ctx, Intent(ctx, TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_EXPORT)
                    .putExtra(TranscriptionService.EXTRA_REQUEST, ticket))
            }
            SessionEffect.ClearAutosave -> SessionAutosave.clear(ctx)
            SessionEffect.PausePlayer -> ui?.pausePlayer()
            SessionEffect.ShowPaused -> ui?.showPaused()
            is SessionEffect.CarryPlayhead -> ui?.carryPlayhead(e.uri)
            is SessionEffect.AgentReset -> { agent.reset(); e.whole?.let { agent.wholeTranscript = it } }
            is SessionEffect.AgentRestore -> {
                agent.restore(e.journal)
                // The saved reading's guardrail counts, for the panel's DEV line.
                now.libraryDir?.let { d -> viewModelScope.launch { agent.guard = withContext(Dispatchers.IO) { SessionLibrary.loadReaderGuard(d) } } }
            }
            is SessionEffect.AgentApply -> agent.apply(e.event)
            is SessionEffect.QueueAgentReset -> { queueAgent.reset(); queueAgent.wholeTranscript = e.whole }
            is SessionEffect.QueueAgentApply -> queueAgent.apply(e.event)
            is SessionEffect.EnqueueAndStart -> viewModelScope.launch {
                withContext(Dispatchers.IO) { ProcessingQueue.enqueue(ctx, e.ids) }
                // Starting the drain supersedes the single service job: fine for library-backed runs
                // (their audio is saved) but it would DESTROY a foreground import's progress — so only
                // enqueue then; the service resumes the queue when the import completes.
                val s = now
                val importRunning = s.running && !s.watchingQueue && !s.recordingRun && !s.isRecording
                if (!importRunning) ui?.processQueue()
                bumpRecents()
            }
            is SessionEffect.DeleteEntries -> viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    e.victims.forEach { v ->
                        ProcessingQueue.remove(ctx, v.id)
                        SessionLibrary.discard(ctx, v.wavFile)   // the whole directory: a partial download goes too
                        // After the discard: the download loop, seeing the directory gone, moves on to the next item.
                        studio.primeminutes.online.ImportDownloads.cancel(v.id)
                    }
                }
                bumpRecents()
            }
            is SessionEffect.ApplyScript -> applyChineseScript(SummaryScript.scriptFor(e.config.summaryScript, ctx))
            is SessionEffect.Toast -> Toast.makeText(ctx, e.message, Toast.LENGTH_LONG).show()
            is SessionEffect.Notify -> notify(e.message)
            is SessionEffect.Snackbar -> viewModelScope.launch { snackbar.showSnackbar(e.message) }
            is SessionEffect.OfferResummaryUndo -> viewModelScope.launch {
                val res = snackbar.showSnackbar(
                    message = strings.str(R.string.resummary_done), actionLabel = strings.str(R.string.undo),
                    duration = SnackbarDuration.Long,
                )
                if (res == SnackbarResult.ActionPerformed) apply(SessionReducer.undoResummary(now, env, e.undo, e.gen))
            }
            is SessionEffect.OfferSpeakerUndo -> viewModelScope.launch {
                val res = snackbar.showSnackbar(
                    message = strings.str(e.done), actionLabel = strings.str(R.string.undo), duration = SnackbarDuration.Long,
                )
                if (res == SnackbarResult.ActionPerformed) update { SessionReducer.undoSpeakerEdit(it, e.gen, e.before, e.after) }
                else if (!e.nameOnly) update { SessionReducer.speakerEditKept(it, e.gen, e.after) }
            }
            is SessionEffect.OfferRetry -> viewModelScope.launch {
                val res = snackbar.showSnackbar(message = e.message, actionLabel = strings.str(R.string.retry), duration = SnackbarDuration.Long)
                // Drop the action if the session moved on in the meantime.
                if (res == SnackbarResult.ActionPerformed && now.sessionGen == e.gen) launchAudio(e.source)
            }
            is SessionEffect.ExportFinished -> exportFinished(e)
        }
    }

    /** Send [build]'s intent to the service; [foreground]: it starts a foreground run. */
    private fun service(foreground: Boolean, build: Intent.() -> Intent) {
        val i = Intent(ctx, TranscriptionService::class.java).build()
        runCatching { if (foreground) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i) }
    }

    private fun exportFinished(f: SessionEffect.ExportFinished) {
        val e = f.done
        if (e.share) {
            val shareUri = e.sharePath.takeIf { it.isNotEmpty() }?.let {
                runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(it)) }.getOrNull()
            }
            if (shareUri == null) {
                notify(strings.str(R.string.session_share_failed) + e.reason.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty())
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = PrimeMinutesSession.Format.M4A.mime
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_SUBJECT, f.title ?: strings.str(R.string.app_name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ui?.startActivity(Intent.createChooser(send, strings.str(R.string.session_share_m4a)))
            }
        } else {
            val label = f.saveUri?.let { documentLabel(ctx, it) } ?: ""
            val msg = when (e.outcome) {
                "FULL" -> strings.str(R.string.session_saved_as, label)
                "PARTIAL" -> strings.str(R.string.session_saved_partial, label)
                else -> strings.str(R.string.session_save_failed) + e.reason.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            }
            // A saved session is a Recent too (the CreateDocument grant is persistable).
            if (e.outcome == "FULL" || e.outcome == "PARTIAL") f.saveUri?.let { u ->
                if (u.scheme == "content") runCatching {
                    ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                RecentSessions.add(ctx, u.toString(), f.title ?: "", System.currentTimeMillis()); bumpRecents()
            }
            // The user may be on Studio/Capture when the result lands: Toast there.
            if (f.screen != Screen.Session) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            else viewModelScope.launch { snackbar.showSnackbar(msg) }
        }
    }

    companion object {
        /** How long a run's gen must stay gone from the service's work before the screen settles it. */
        private const val RUN_END_GRACE_MS = 1_500L
        /** Wait between an edit and its save: a burst of edits is one write. */
        private const val AUTO_PERSIST_MS = 300L

        /** Human-facing name of a SAF document (post-conflict, so it reflects any "(1)" the system added). */
        fun documentLabel(context: android.content.Context, uri: Uri): String =
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()

        /**
         * Decode a (possibly untrusted) JPEG/PNG with its longest side capped at [maxDim] px, so a
         * hostile embedded cover with enormous declared dimensions can't OOM-crash the app.
         */
        fun decodeBoundedBitmap(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / sample > maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            return runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
        }
    }
}
