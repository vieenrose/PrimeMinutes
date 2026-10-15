package studio.primeminutes

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A just-written session.m4a can need a moment before MediaPlayer will prepare it. */
private const val PREPARE_ATTEMPTS = 4
private const val PREPARE_RETRY_MS = 250L

/**
 * The Session screen's synced player (android MediaPlayer; no extra dep), split out of
 * MainActivity: the player, its playhead and transport state, the off-main-thread prepare with
 * retries, recovery after errors and stalls, and the playhead carried across a source swap. The
 * screen drives it from its effects ([rebuild] on a new source, [poll] while playing,
 * [pauseOffScreen]) and reads its state. [visible] says whether the Session screen is on top:
 * playback never starts on a screen without transport controls.
 */
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val visible: () -> Boolean,
) {
    var player by mutableStateOf<MediaPlayer?>(null)
    var enhancer by mutableStateOf<LoudnessEnhancer?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableIntStateOf(0)
    var durationMs by mutableIntStateOf(0)
    var volume by mutableFloatStateOf(1f)
    var muted by mutableStateOf(false)
    var dragMs by mutableStateOf<Int?>(null)
    /** Underrun: waiting for the buffer to refill. */
    var buffering by mutableStateOf(false)

    /** The source of the live player. */
    private var uri: Uri? = null
    // When the playback source is swapped to the decoded WAV at end-of-ASR (same audio, new file), carry
    // the position + play-state across the player rebuild so the cursor doesn't snap back to 0.
    private var pendingSeekMs: Int? = null
    private var resumeAfterSwap = false
    private val mutex = Mutex()

    // Prepare a MediaPlayer OFF the main thread: setDataSource() on a content:// uri and prepare() are
    // synchronous blocking calls, and under pipeline codec/CPU contention (the foreground pipeline
    // decoding the same audio on a 2-core device) they can block for seconds — on the UI thread that is
    // a freeze / input-dispatch ANR. Serialized via the mutex so a re-prepare (error or seek recovery)
    // can't collide with another transition on the same player. Returns whether it ended up prepared;
    // updates durationMs and optionally seeks.
    private suspend fun prepare(p: MediaPlayer, uri: Uri, seekTo: Int?): Boolean = mutex.withLock {
        // Coalesce queued recoveries: while this call waited on the mutex, an earlier recovery (or
        // the poll loop's gentle retry) may have already brought p back to life — reset()ing a
        // player that is playing again would kill live playback and jump the cursor.
        if (runCatching { p.isPlaying }.getOrDefault(false)) {
            android.util.Log.i("primeminutes-play", "PREPARE skipped: already playing (seekTo=$seekTo LOST)")
            return@withLock true
        }
        // RETRY, and never drop the carried playhead. A source swap at end-of-run points the player
        // at a session.m4a the pipeline has only just finished writing, and prepare() on it throws
        // IllegalStateException if it catches the file mid-settle. Measured on a Boox: the swap
        // carried pos=166224 correctly, prepare() threw, the seek never ran, and playback later
        // restarted from 0 — which is the whole "cursor jumps back to the start when processing
        // finishes" report. A single prepare was the defect; the earlier fix guarded the poll loop,
        // which was a real but different bug.
        var lastErr: Throwable? = null
        repeat(PREPARE_ATTEMPTS) { attempt ->
            val ok = runCatching {
                withContext(Dispatchers.IO) { p.reset(); p.setDataSource(context, uri); p.prepare() }
                durationMs = p.duration
                seekTo?.let { p.seekTo(it.coerceIn(0, durationMs)) }
                android.util.Log.i("primeminutes-play", "PREPARED dur=$durationMs sought=$seekTo try=$attempt")
                true
            }.onFailure { lastErr = it }.getOrDefault(false)
            if (ok) return@withLock true
            delay(PREPARE_RETRY_MS)
        }
        android.util.Log.w("primeminutes-play", "PREPARE failed after $PREPARE_ATTEMPTS tries", lastErr)
        // Hand the position back so a later recovery resumes HERE rather than at zero.
        pendingSeekMs = seekTo
        false
    }

    // Retire a player without racing its in-flight prepare: sever the state reference NOW (main),
    // release under the SAME mutex prepare holds — release() on an instance that another
    // coroutine has inside native prepare() is the classic MediaPlayer crash.
    fun retire() {
        enhancer?.release(); enhancer = null
        val p = player ?: return
        player = null
        scope.launch { mutex.withLock { runCatching { p.release() } } }
    }

    /** A new source (or none): retire the old player and build one for [audioUri]. */
    fun rebuild(audioUri: Uri?) {
        retire()
        uri = audioUri
        // Consume the carry-over from a source swap (null on a genuinely new session → start at 0).
        val seekTo = pendingSeekMs; val resume = resumeAfterSwap
        pendingSeekMs = null; resumeAfterSwap = false
        android.util.Log.i("primeminutes-play", "REBUILD seekTo=$seekTo resume=$resume uri=${audioUri?.lastPathSegment}")
        durationMs = 0; positionMs = seekTo ?: 0; dragMs = null; buffering = false
        audioUri?.let { uri ->
            val mp = MediaPlayer()
            // Honor the hoisted mute flag: mute keeps `volume` at its pre-mute value (so unmute can
            // restore it), so a rebuilt player (source swap, new session) must re-apply the 0 —
            // otherwise audio comes back audible under a mute button that still shows muted.
            val effVol = if (muted) 0f else volume
            mp.setVolume(effVol, effVol)
            mp.setOnCompletionListener { isPlaying = false }
            // A mid-stream decode error otherwise leaves the player in ERROR state, where every later
            // start() fails with native error -38 ("played, stopped, can't play anymore"). Re-prepare
            // off the main thread (serialized), resuming at the current position — never from 0.
            mp.setOnErrorListener { p, _, _ ->
                val resumeAt = positionMs
                isPlaying = false
                scope.launch { prepare(p, uri, resumeAt) }
                true
            }
            // Loudness normalization: starts at 0; the screen measures the track and sets the
            // per-file gain that lifts a quiet recording to a comfortable level.
            runCatching { enhancer = LoudnessEnhancer(mp.audioSessionId).apply { enabled = true } }
            player = mp
            // prepare() can fail if the pipeline is still decoding the same audio (codec/CPU
            // contention); the player self-heals on the next play tap via [resumeOrRecover], so a
            // failed prepare here just leaves durationMs at 0 (no UI block either way).
            scope.launch {
                // Prepare at the carried-over position; resume playing if it was playing before the
                // swap — but only if the Session screen is STILL on top when the async prepare lands
                // (the end-of-ASR / LibrarySaved swaps can fire while the user is on Studio, and
                // resuming there would start audio on a screen with no transport controls at all)
                // AND mp is still the live player (another swap may have retired it meanwhile).
                if (prepare(mp, uri, seekTo) && resume && visible() && player === mp) {
                    runCatching { mp.start() }.onSuccess { isPlaying = true }
                }
            }
        }
    }

    // Robustly (re)start playback. If start() fails — the player errored or stalled while the pipeline
    // was decoding the same audio (codec/CPU contention, buffer underrun) — reset + re-prepare and
    // retry, so playback self-heals instead of staying stuck.
    fun resumeOrRecover(seekMs: Int?) {
        val p = player ?: return
        val uri = uri ?: return
        val started = runCatching { seekMs?.let { p.seekTo(it.coerceIn(0, durationMs)) }; p.start() }.isSuccess
        if (started) { isPlaying = true; buffering = false; return }
        // Stop the poll loop from calling start() on this player while the IO re-prepare resets it (the
        // MediaPlayer is not thread-safe) — mirror onError, which also clears isPlaying first.
        isPlaying = false
        // The fast start() failed — the player errored, or isn't prepared yet (initial prepare still
        // running, or the pipeline was decoding the same audio). Re-prepare OFF the main thread
        // (serialized via prepare), resuming at the requested point or current position — never
        // from 0 — and start, so playback self-heals without ever freezing the UI thread.
        val resumeAt = seekMs ?: positionMs
        scope.launch {
            // Re-check after the async prepare: the session may have been cleared/swapped (p
            // retired) or the user may have left the Session screen — starting then would play
            // orphaned audio with no visible transport.
            if (prepare(p, uri, resumeAt) && player === p && visible()) {
                runCatching { p.start() }.onSuccess { isPlaying = true; buffering = false }
            }
        }
    }

    /** Jump to [ms] and play from there. */
    fun seekAndPlay(ms: Int) {
        positionMs = ms.coerceAtLeast(0).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        resumeOrRecover(positionMs)
    }

    /** Run while [isPlaying] (keyed on it and on [player] by the caller). */
    suspend fun poll() {
        // Poll the position and watch for stalls: while we intend to play, if the player isn't
        // advancing (a buffer underrun under decode/CPU contention freezes it), show "buffering" and
        // keep gently retrying start() — so it resumes when the buffer refills, like a streaming
        // player — with one re-prepare on a hard stall. Never stays stuck.
        var lastPos = -1
        var frozen = 0
        while (isPlaying) {
            // NOT YET PREPARED: skip the whole tick. A source swap builds the new MediaPlayer on the
            // main thread and prepares it ASYNCHRONOUSLY, and an unprepared player answers
            // currentPosition with 0 WITHOUT throwing — so runCatching's fallback never fired and
            // this loop overwrote the carried playhead with 0 before prepare could seek to it.
            // That is the "cursor jumps back to the start when processing finishes" report: the
            // end-of-ASR and LibrarySaved swaps both carry the playhead correctly, and this poll
            // then threw it away. Starting an unprepared player is the second hazard (it enters the
            // Error state, where every later start() fails), so the stall retry below must not run
            // either. durationMs is the readiness signal: the swap zeroes it and prepare sets
            // it only after prepare() returns.
            if (durationMs == 0) {
                durationMs = runCatching { player?.duration ?: 0 }.getOrDefault(0)
                if (durationMs <= 0) { delay(150); continue }
            }
            val pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(positionMs)
            positionMs = pos
            val atEnd = durationMs > 0 && pos >= durationMs - 250
            val advancing = pos != lastPos && runCatching { player?.isPlaying == true }.getOrDefault(false)
            if (!atEnd && !advancing) {
                frozen++
                buffering = frozen >= 3                        // ~450 ms frozen → show buffering, then WAIT
                // Underrun = the player is starved while the pipeline decodes; it resumes in place
                // once the CPU/buffer catches up, like a streaming player. Only resume it if it
                // actually paused — never reset/re-prepare here (that would jump the cursor to 0).
                if (frozen >= 3) runCatching { if (player?.isPlaying == false) player?.start() }
            } else { frozen = 0; buffering = false }
            lastPos = pos
            delay(150)
        }
        buffering = false
    }

    // Playback belongs to the Session view only. The player + isPlaying are hoisted above the screen
    // switch (so a source swap / re-prepare survives), which means leaving Session would otherwise
    // keep the audio playing with no visible control on the library home — and a later re-open, which
    // sets isPlaying=false without touching the still-playing player (same audioUri → no rebuild),
    // would leave the play/pause button desynced from the audio. Pause (keeping the playhead) whenever
    // the Session screen isn't on top, so leaving stops orphaned playback and re-entry shows a button
    // that matches reality. Re-opening a session sets screen=Session and rebuilds/prepares as before.
    fun pauseOffScreen() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        isPlaying = false
        buffering = false
    }

    /** The source is about to become [newUri], another file of the SAME audio: carry the playhead
     *  and play state across the rebuild. */
    fun carryPlayhead(newUri: Uri) {
        pendingSeekMs = positionMs.takeIf { it > 0 }
        resumeAfterSwap = isPlaying
        android.util.Log.i("primeminutes-play", "SWAP pos=$positionMs pending=$pendingSeekMs " +
            "playing=$isPlaying to=${newUri.lastPathSegment}")
    }
}
