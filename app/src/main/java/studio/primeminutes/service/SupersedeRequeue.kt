package studio.primeminutes.service

import android.content.Context
import android.util.Log
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary

/**
 * A meeting being processed in the foreground (an import, a Resume) when a new file is imported:
 * the new import supersedes it (one pipeline at a time), its audio and frozen transcript are banked
 * into its library entry as before, and the entry goes back to the HEAD of the [ProcessingQueue].
 * The service resumes the queue after the new import ends, so the interrupted meeting is processed
 * next, automatically — it used to stay "not processed" until the user noticed. The queue is the
 * persistent one, so the requeue also survives a process kill.
 *
 * Interrupt-and-requeue rather than queueing the new import behind the current one: a shared file is
 * only a transient copy with a live session screen waiting on it, while the interrupted meeting is
 * already durable in the library — the queue is built for exactly that kind of item.
 */
internal object SupersedeRequeue {
    /** Whether the run being superseded is one whose meeting must come back: a foreground file run the user did not stop. */
    fun applies(prev: RunRegistry.Run?, prevActive: Boolean, userStopped: Boolean): Boolean =
        prev != null && prevActive && !userStopped && prev.kind == RunRegistry.Kind.IMPORT

    /** Requeue [entry] at the head of the queue unless it is finished or gone; true when queued. */
    fun requeue(context: Context, entry: SessionLibrary.Entry?): Boolean {
        val e = entry?.let { SessionLibrary.byId(context, it.id) } ?: return false
        if (e.status == SessionLibrary.Status.DONE || !e.wavFile.exists()) return false
        ProcessingQueue.enqueueFront(context, e.id)
        Log.i(STOP_TAG, "superseded run: ${e.id} back at the head of the queue")
        return true
    }
}
