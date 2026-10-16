package studio.primeminutes.service

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import studio.primeminutes.R
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.ProcessingQueue
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

/**
 * The processing-queue drain, split out of [TranscriptionService] behind the same [PipelineHost]:
 * pass 1 runs [ImportPipeline] over each queued library entry (ASR + speaker tags only, transcript
 * kept in a sidecar), pass 2 hands every transcribed entry to the AI notes worker, which saves and
 * dequeues it. One instance per service; it runs inside the service's QUEUE_GEN-tagged job.
 */
internal class QueueRunner(
    private val host: PipelineHost,
    private val importPipeline: ImportPipeline,
) {
    private val ctx: Context get() = host.context

    /**
     * Drain the [ProcessingQueue]: run the full pipeline over each queued library entry, embed the
     * results ([SessionLibrary.attachResults]), and remove it. Serial by design (the models are the
     * bottleneck). An item is removed only after it finished (or failed terminally), so a kill
     * mid-item resumes it on the next drain; a cancellation (new run superseding this one) leaves
     * the remainder queued for later.
     */
    suspend fun run() {
        // The Holder contract is "the UI sets the config when it starts a run" — but a queue drain
        // can start with no UI run ever having happened in this process (fresh process, auto-start
        // after ⏹). Load the persisted settings so target language / script conversion / model
        // choices apply to queue items exactly like foreground runs. (Regression: queue transcripts
        // ignored the user's zh-Hant target and came out simplified with default-locale summaries.)
        TranscriptionConfig.Holder.config = studio.primeminutes.core.config.ConfigStore.load(ctx)
        // NOTE: queueDraining is main-owned — set in onStartCommand / the resume hop, cleared in
        // the teardown hop — so the main-thread guard can never race a Default-thread write. (The
        // old set-it-here had a window: a second ACTION_PROCESS_QUEUE arriving before this line ran
        // passed the guard and superseded the first drain, redoing its in-flight item.)
        try {
        // Two-pass drain: pass 1 transcribes+diarizes EVERY queued item (ASR models only), pass 2
        // loads the LLM ONCE and summarizes them all — one LLM load per drain instead of one per
        // item (~10s each on Boox), still never co-residing the ASR models with the LLM. Between
        // the passes each item's transcript is durable in a library sidecar, so a process kill
        // resumes summarize-only. The outer loop catches items enqueued mid-drain.
        val cfgAll = TranscriptionConfig.Holder.config
        // Anything transcription-affecting invalidates a leftover sidecar from an older drain.
        val fingerprint = host.transcriptFingerprint(cfgAll)
        var lastLap: List<String>? = null
        while (true) {
            val ids = ProcessingQueue.ids(ctx)
            if (ids.isEmpty()) break
            // No item left the queue AND nothing new arrived since the last lap → every remaining
            // item is stuck (e.g. sidecar write failing on a full disk). Break instead of spinning.
            if (ids == lastLap) break
            lastLap = ids

            // --- Pass 1: ASR + diarization per item; transcript → sidecar; item stays queued. ---
            for (id in ids) {
                val entry = SessionLibrary.byId(ctx, id)
                if (entry == null || entry.status == SessionLibrary.Status.DONE || !entry.wavFile.exists()) {
                    ProcessingQueue.remove(ctx, id)   // stale/already-done → drop and move on
                    continue
                }
                if (SessionLibrary.loadPendingTranscript(entry, fingerprint) != null) continue   // resume: ASR already done
                RunRegistry.queueItem(id)
                host.updateNotification(ctx.getString(R.string.svc_processing_queue, entry.title ?: SessionLibrary.defaultTitle(ctx, entry.createdAt), ProcessingQueue.size(ctx)))
                // Track decode temp files this item creates so they're reclaimed per-item (a long
                // queue would otherwise stack one decoded WAV copy per entry in filesDir/audio).
                val audioDir = File(ctx.filesDir, "audio")
                val before = audioDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                try {
                    val res = importPipeline.run(Uri.fromFile(entry.wavFile).toString(), summarizeAfter = false)
                    if (res == null) {
                        ProcessingQueue.remove(ctx, id)   // terminal (no source / corrupt model) — parity with the old drain
                        continue
                    }
                    // The user may have DELETED this entry while it processed — don't resurrect it.
                    if (SessionLibrary.byId(ctx, id) != null) {
                        SessionLibrary.savePendingTranscript(entry, res.first, fingerprint)
                    } else ProcessingQueue.remove(ctx, id)
                } catch (ce: CancellationException) {
                    throw ce   // superseded/stopped: keep the item queued for the next drain
                } catch (t: Throwable) {
                    // A terminally failed item must not wedge the queue — drop it and continue; its
                    // capture stays safe (RECORDED) in the library for a manual retry. Surface it
                    // via a NOTIFICATION (the user may be elsewhere), not an UNTAGGED Status.
                    Log.w("TranscriptionService", "queue item $id failed terminally", t)
                    host.notifyItemFailed(entry.title ?: SessionLibrary.defaultTitle(ctx, entry.createdAt))
                    ProcessingQueue.remove(ctx, id)
                } finally {
                    RunRegistry.queueItem(null)
                    audioDir.listFiles()?.forEach { if (it.name !in before) runCatching { it.delete() } }
                }
            }

            // --- Pass 2: the AI notes worker reads each item with a sidecar, in order, and saves +
            // dequeues it. Awaited one by one; a superseding run cancels only this wait — the worker
            // still finishes the item it is reading. ---
            val toSummarize = ProcessingQueue.ids(ctx)
            if (toSummarize.isEmpty()) continue
            for (id in toSummarize) {
                val entry = SessionLibrary.byId(ctx, id)
                if (entry == null || entry.status == SessionLibrary.Status.DONE || !entry.wavFile.exists()) {
                    ProcessingQueue.remove(ctx, id)
                    continue
                }
                val utterances = SessionLibrary.loadPendingTranscript(entry, fingerprint)
                    ?: continue   // no sidecar (its pass-1 was cut short): leave queued, next outer lap redoes ASR
                RunRegistry.queueItem(id)
                host.updateNotification(ctx.getString(R.string.svc_processing_queue, entry.title ?: SessionLibrary.defaultTitle(ctx, entry.createdAt), ProcessingQueue.size(ctx)))
                try {
                    val task = host.reader.ReadTask(TranscriptionService.QUEUE_GEN, host.outputConverter(cfgAll), withTitle = true, entry = entry)
                    task.complete(utterances)
                    runCatching { host.reader.awaitRead(host.reader.submitRead(task)) }
                        .onFailure { if (it is CancellationException) throw it }
                } finally {
                    RunRegistry.queueItem(null)
                }
            }
        }
        } finally {
            // Drain over (queue empty, last item failed terminally, or superseded): nudge the UI's
            // QUEUE_GEN branch so it re-reads currentQueueItemId (null by now — the per-item finally
            // ran first) and clears the Studio row's "Processing" chip. The success path's
            // LibrarySaved already does this, but the failure and cancellation paths emit nothing
            // else, leaving the row stuck "Processing" with no worker running. tryEmit because this
            // must also fire from a CANCELLED coroutine (supersede); the flow has a 256 buffer.
            // (queueDraining itself is cleared on MAIN in the teardown hop.)
            TranscriptionService.events.tryEmit(TranscriptionService.QUEUE_GEN to TranscriptEvent.Progress(0f))
        }
    }
}
