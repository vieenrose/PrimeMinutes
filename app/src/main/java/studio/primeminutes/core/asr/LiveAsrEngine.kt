package studio.primeminutes.core.asr

import kotlinx.coroutines.flow.Flow
import studio.primeminutes.core.events.TranscriptEvent

/**
 * What the import and recording pipelines need from a streaming speech engine: one pass that
 * transcribes and tags speakers, emitting replace-all [TranscriptEvent.UtteranceSnapshot]s, then the
 * speaker count once the stream has finished. [NemoStreamEngine] is the shipped one; the seam lets
 * the pipelines run on the JVM against a scripted engine.
 */
interface LiveAsrEngine : AutoCloseable {
    fun transcribeLive(chunks: Flow<FloatArray>): Flow<TranscriptEvent>
    /** Distinct speakers in the final snapshot; null until the stream has finished. */
    val speakerCount: Int?
}
