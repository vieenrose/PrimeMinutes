package studio.primeminutes.session

import android.net.Uri
import androidx.annotation.StringRes
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.data.SpeakerName

/**
 * What a [SessionReducer] transition asks the outside world to do, as data. [SessionViewModel]
 * runs them in order, right after publishing the new state; the ones that need the Activity (the
 * service starts with their battery-exemption prompt, the player, choosers) go to the screen.
 */
sealed interface SessionEffect {
    // --- The service (through the Activity: it asks for the battery exemption first). ---
    data class StartImport(val uri: Uri, val gen: Int) : SessionEffect
    data class StartRecording(val gen: Int) : SessionEffect
    data object StopRun : SessionEffect
    data object StopRecording : SessionEffect
    data object StopRecordingDefer : SessionEffect
    data object ProcessQueue : SessionEffect
    /** ACTION_SUMMARIZE over [text] (the formatted transcript), tagged [gen]. [save]: the library
     *  session it re-summarizes, which the service updates with the result itself. */
    data class StartSummarize(val text: String, val withTitle: Boolean, val gen: Int, val save: Persist? = null) : SessionEffect

    /** Put [config] in the process-wide holder the service reads when a run starts. */
    data class ApplyConfig(val config: TranscriptionConfig) : SessionEffect
    data class SaveConfig(val config: TranscriptionConfig) : SessionEffect

    /** Write the open session's edits back into its library entry. [notes] null → the AI notes journal. */
    data class Persist(
        val entryId: String, val audioUri: Uri?,
        val utterances: List<TranscriptEvent.Utterance>, val speakerNames: Map<Int, SpeakerName>,
        val summary: String?, val actionItems: String?, val title: String?, val notes: String?,
        val asrModelId: String?, val asrBackend: String?, val llmModelId: String?,
    ) : SessionEffect

    /** Build and write (or share) the session file in the service. [notes] null → the AI notes journal. */
    data class Export(
        val share: Boolean, val saveUri: Uri?, val audioUri: Uri?,
        val utterances: List<TranscriptEvent.Utterance>, val speakerNames: Map<Int, SpeakerName>,
        val summary: String?, val actionItems: String?, val title: String?, val notes: String?,
        val asrModelId: String?, val asrBackend: String?, val llmModelId: String?,
        val coverEnabled: Boolean, val format: studio.primeminutes.core.session.PrimeMinutesSession.Format,
    ) : SessionEffect

    data object ClearAutosave : SessionEffect

    // --- The player (owned by the screen). ---
    /** Pause a playing track and show it paused. */
    data object PausePlayer : SessionEffect
    /** Only show it paused (the track is about to change). */
    data object ShowPaused : SessionEffect
    /** Carry the playhead and play-state to [uri], the same audio in another file. */
    data class CarryPlayhead(val uri: Uri) : SessionEffect

    // --- The AI notes panels. [whole]: set wholeTranscript too (null: leave it). ---
    data class AgentReset(val whole: Boolean?) : SessionEffect
    data class AgentRestore(val journal: String) : SessionEffect
    data class AgentApply(val event: AgentEvent) : SessionEffect
    data class QueueAgentReset(val whole: Boolean) : SessionEffect
    data class QueueAgentApply(val event: AgentEvent) : SessionEffect

    // --- The library. ---
    /** Add [ids] to the queue and start the drain unless a foreground import must not be superseded. */
    data class EnqueueAndStart(val ids: List<String>) : SessionEffect
    data class DeleteEntries(val victims: List<SessionLibrary.Entry>) : SessionEffect
    /** Re-render the session's Chinese text in the script the settings now ask for. */
    data class ApplyScript(val config: TranscriptionConfig) : SessionEffect

    // --- Feedback. ---
    data class Toast(val message: String) : SessionEffect
    /** Snackbar on the Session screen, Toast elsewhere. */
    data class Notify(val message: String) : SessionEffect
    data class Snackbar(val message: String) : SessionEffect
    /** "Re-summarized" with Undo: put [undo] back if still on session [gen] and idle. */
    data class OfferResummaryUndo(val undo: ResummaryUndo, val gen: Int) : SessionEffect
    /** A speaker merge/move with Undo: restore [before] if nothing changed since ([after]). */
    data class OfferSpeakerUndo(
        @StringRes val done: Int, val gen: Int,
        val before: Pair<List<TranscriptEvent.Utterance>, Map<Int, SpeakerName>>,
        val after: List<TranscriptEvent.Utterance>,
    ) : SessionEffect
    /** A failed run's error with Retry (same source) while still on session [gen]. */
    data class OfferRetry(val message: String, val source: Uri, val gen: Int) : SessionEffect
    /** The service finished an export: share chooser or "saved as" feedback. */
    data class ExportFinished(val done: TranscriptEvent.ExportDone, val saveUri: Uri?, val title: String?, val screen: Screen) : SessionEffect
}
