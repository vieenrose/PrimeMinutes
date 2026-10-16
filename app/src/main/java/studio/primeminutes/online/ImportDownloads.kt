package studio.primeminutes.online

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import studio.primeminutes.service.TranscriptionService

/**
 * Process-wide state of the podcast/YouTube import download. The download itself runs in
 * [TranscriptionService] (foreground, with a notification), so closing the importer sheet — or the
 * app — no longer cancels it. The sheets only start it; MainActivity observes [state].
 */
object ImportDownloads {
    sealed interface State {
        data object Idle : State
        /** [stageRes] is the phase label (resolving / downloading); [progress] null = indeterminate. */
        data class Running(val title: String?, val stageRes: Int, val progress: Float?) : State
        data class Ready(val uri: Uri, val title: String?) : State
        data class Failed(val message: String) : State
    }

    /** The work to run in the service: reports its phase and progress, returns the file and a title. */
    class Request(val title: String?, val work: suspend (stage: (Int) -> Unit, progress: (Float) -> Unit) -> Pair<Uri, String?>)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile internal var pending: Request? = null
    @Volatile internal var job: Job? = null

    val running: Boolean get() = _state.value is State.Running

    /** Returns false when a download is already running (one at a time). */
    fun start(ctx: Context, req: Request, firstStageRes: Int): Boolean {
        if (running) return false
        pending = req
        _state.value = State.Running(req.title, firstStageRes, null)
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_IMPORT_DOWNLOAD),
        )
        return true
    }

    /** Explicit user cancel: the `.part` is kept, so asking for the same item again resumes it. */
    fun cancel() { job?.cancel() }

    /** Wakes a download paused by a lost network (Resume button, the app back in front). */
    internal val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    fun resumeNow() { wake.trySend(Unit) }

    /** A failure waiting can fix: the network (an [java.io.IOException]), not a 404 or a refused item. */
    fun transient(e: Throwable): Boolean = e is java.io.IOException && e !is java.io.FileNotFoundException

    /** Called by the UI once it has handled [State.Ready] / [State.Failed]. */
    fun consume() { if (_state.value !is State.Running) _state.value = State.Idle }

    internal fun set(s: State) { _state.value = s }
}
