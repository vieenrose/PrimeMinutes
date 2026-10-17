package studio.primeminutes.session

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.llm.MeetingNotes
import studio.primeminutes.data.SpeakerName

/** Studio navigation: list-first stack — Studio (home) → Capture / Session, back returns home. */
enum class Screen { Studio, Capture, Session }

/** The summary, notes and title a re-summarize cleared, with the AI notes journal. */
data class ResummaryUndo(
    val summary: String?, val notes: MeetingNotes?, val title: String?,
    val titleEdited: Boolean, val journal: String?,
)

/**
 * Everything the session screen shows and edits: the open session (transcript, speaker names,
 * notes), the run that feeds it, the inline-edit cursors and the live view of the queue's current
 * item. Owned by [SessionViewModel]; changed only through [SessionReducer], so every transition is
 * a pure function of the previous state and testable on the JVM.
 *
 * The AI notes panels ([studio.primeminutes.ui.AgentUiState]) and the player stay outside: they
 * are driven by effects the reducer emits.
 */
data class SessionState(
    /** The user's settings (persisted). Seeded into [TranscriptionConfig.Holder] when a run starts. */
    val config: TranscriptionConfig,
    val screen: Screen = Screen.Studio,
    val status: String = "",
    /** Semantic error flag for the status pill's color (locale-independent, unlike parsing the text). */
    val statusIsError: Boolean = false,
    val title: String? = null,
    val summary: String? = null,
    val actionItems: String? = null,
    /** v2 structured notes, when the summarizer produced them (decisions / open questions / topics). */
    val meetingNotes: MeetingNotes? = null,
    val audioUri: Uri? = null,
    val running: Boolean = false,
    /** A final transcript exists (the Complete event): Re-run keys off this, not off !running. */
    val transcriptReady: Boolean = false,
    val progress: Float = 0f,
    /** Which models produced the OPEN session's text (llm, asr, asr backend) — kept apart from
     *  [config]: opening an old session must not switch the model the next run uses. */
    val sessionModels: Triple<String, String, String>? = null,
    // Dependency tree (audio → transcript → {summary → title, speaker names, action items}):
    /** A summary-input (LLM) setting changed → offer re-summarize when Settings closes. */
    val summaryStale: Boolean = false,
    /** The spoken-language pick changed → say so when Settings closes. */
    val transcriptStale: Boolean = false,
    /** The transcript was hand-edited → offer to re-summarize (its child). */
    val transcriptDirty: Boolean = false,
    /** The user renamed the title → don't regenerate it on re-summarize (script convert only). */
    val titleEdited: Boolean = false,
    /** Bumps on every new session; tags every event the service emits for the run it started, so a
     *  superseded run's late events are dropped. */
    val sessionGen: Int = 0,
    /** Bumped by every hand-edit; a script conversion begun before an edit must not overwrite it. */
    val editSeq: Int = 0,
    /** Edits not yet written back to the session's library entry. */
    val sessionDirty: Boolean = false,
    val utterances: List<TranscriptEvent.Utterance> = emptyList(),
    /** Leading utterances of the live transcript that won't change until the final snapshot. */
    val liveStable: Int = 0,
    val speakerNames: Map<Int, SpeakerName> = emptyMap(),
    /** A re-run's previous transcript and names: carried over by time overlap when the new one lands. */
    val carry: SpeakerCarry? = null,
    /** What a re-summarize replaced, put back if it is stopped before a new summary arrives. */
    val resummaryUndo: ResummaryUndo? = null,
    // --- Inline editing: which row / speaker / card is open. ---
    val editingIndex: Int = -1,
    val editingSpeakerId: Int? = null,
    /** The line whose chip was tapped: only that one turns into the name field. */
    val editingSpeakerLine: Int = -1,
    val editingTitle: Boolean = false,
    val editingSummary: Boolean = false,
    val editingActions: Boolean = false,
    // --- Cover / export / open. ---
    val coverEnabled: Boolean = true,
    val coverBitmap: Bitmap? = null,
    /** The cover is the one EMBEDDED in an opened session (never re-fingerprinted). */
    val coverFromSession: Boolean = false,
    /** A session .m4a is being built/written in the service. */
    val exporting: Boolean = false,
    /** A session file is being decoded for opening. */
    val opening: Boolean = false,
    val lastSaveUri: Uri? = null,
    /** The library entry backing the session, if any. */
    val libraryDir: File? = null,
    /** The session came from the mic / a library capture (⏭ "next talk" stays available). */
    val recordingRun: Boolean = false,
    /** ⏹ Stop & save: enqueue the capture once RecordingSaved confirms it is safe. */
    val pendingAutoProcess: Boolean = false,
    /** "Next talk": start the next recording once RecordingSaved confirms this one is safe. */
    val pendingNextTalk: Boolean = false,
    /** A defer-stopped run's terminal event is Complete (no summary follows). */
    val deferStopped: Boolean = false,
    /** The Session screen is a LIVE VIEW of the queue's current item. */
    val watchingQueue: Boolean = false,
    /** The Session screen shows a podcast / YouTube item still downloading (or failed): its download card. */
    val downloadEntryId: String? = null,
    val isRecording: Boolean = false,
    /** Mic input level while recording (0..1 in five steps). */
    val micLevel: Float = 0f,
    // --- Live view of the queue's current item, kept even while nobody watches. ---
    val queueItemId: String? = null,
    val queueLabel: String = "",
    val queueFraction: Float = 0f,
    val queueUtterances: List<TranscriptEvent.Utterance> = emptyList(),
    val queueTitle: String? = null,
    val queueSummary: String? = null,
    /** Bumped whenever the library rows may have changed (re-read off the main thread). */
    val recentsVersion: Int = 0,
    // --- Find-in-transcript and the sheets a new session closes. ---
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val showConfigSheet: Boolean = false,
    val showPodcastSheet: Boolean = false,
    val showAddSourceSheet: Boolean = false,
    val showYouTubeSheet: Boolean = false,
    val showExportSheet: Boolean = false,
)

/** The transcript and names a re-run replaces (see [studio.primeminutes.core.people.SpeakerCarryOver]). */
data class SpeakerCarry(val utterances: List<TranscriptEvent.Utterance>, val names: Map<Int, SpeakerName>)
