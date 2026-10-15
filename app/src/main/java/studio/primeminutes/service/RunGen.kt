package studio.primeminutes.service

/** Carries a run's generation down its coroutine tree so events can be tagged with it. */
internal class RunGen(val gen: Int) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RunGen>
}

/** The current run's generation — capture this BEFORE handing a progress lambda to a
 *  non-suspend callback (downloads, diarization, extraction). Emitting UNTAGGED from those
 *  callbacks loses the tag: the Studio row's queue progress only consumes QUEUE_GEN events,
 *  so a drain's diarization/download phase showed a frozen 0% bar on the home screen (while
 *  the watched Session view happened to work), and a superseded run's untagged progress
 *  could still mutate a freshly-reset session. */
internal suspend fun currentGen(): Int =
    kotlin.coroutines.coroutineContext[RunGen]?.gen ?: TranscriptionService.UNTAGGED
