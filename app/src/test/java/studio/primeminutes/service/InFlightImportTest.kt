package studio.primeminutes.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.primeminutes.core.audio.WavIo
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import java.io.File

/** The stop handler's view of an import: banked once, at its on-disk length, with its checkpoint. */
@RunWith(RobolectricTestRunner::class)
class InFlightImportTest {
    private lateinit var ctx: Context
    private val promoted = ArrayList<Pair<File, Int>>()
    private lateinit var state: InFlightImport

    @Before fun setUp() {
        ctx = stringlessContext(ApplicationProvider.getApplicationContext())
        SessionLibrary.root(ctx).deleteRecursively()
        state = InFlightImport { wav, sec -> promoted += wav to sec; SessionLibrary.promoteRecording(ctx, wav, sec) }
    }
    @After fun tearDown() { SessionLibrary.root(ctx).deleteRecursively() }

    /** A work WAV the decoder is still writing: placeholder header, [seconds] of PCM. */
    private fun growing(seconds: Int) = File(File(ctx.filesDir, "audio").apply { mkdirs() }, "decoded_${System.nanoTime()}.wav")
        .apply { writeBytes(ByteArray(WavIo.HEADER + seconds * WavIo.SAMPLE_RATE * 2)) }

    @Test fun nothingInFlightOrTooShortSavesNothing() {
        assertNull(state.save("stop"))
        state.begin(growing(1), 60)            // ≤ 1 s of audio: not worth an entry
        assertNull(state.save("stop"))
        assertTrue(promoted.isEmpty())
    }

    @Test fun bankedOnceAtWhatIsOnDisk() {
        val wav = growing(3)
        state.begin(wav, 600)                  // the source is 10 min, 3 s were decoded
        val e = state.save("stop")!!
        assertEquals(3, promoted.single().second)
        assertTrue("header finalized", WavIo.isCanonical16kMono(e.wavFile))
        assertSame(e, state.savedEntry)
        assertNull(state.wav)
        assertNull("the pipeline's own save is a no-op now", state.save("pipeline"))
        assertEquals(1, SessionLibrary.list(ctx).size)
    }

    @Test fun aNewImportRearmsTheOneShot() {
        state.begin(growing(2), 0)
        state.save("pipeline")!!
        state.begin(growing(4), 0)
        assertEquals(4, state.save("pipeline")!!.durationSec)
    }

    @Test fun stopBanksTheCheckpointNextToTheAudio() {
        state.begin(growing(3), 0)
        val keep = listOf(TranscriptEvent.Utterance(0, "hello", 0.0, 1.0, 0))
        state.checkpoint = InFlightImport.Checkpoint(SessionLibrary.Progress("fp", keep, 1.0, false, "")) { null }
        state.bankOnStop { error("no notes: the model id is not asked for") }
        val e = SessionLibrary.list(ctx).single()
        val p = SessionLibrary.loadProgress(e)!!
        assertEquals(keep, p.utterances)
        assertNull(state.checkpoint)
    }

    @Test fun bankedSecondsTrustsTheSmallerLength() {
        val wav = growing(5)
        assertEquals(5, InFlightImport.bankedSeconds(wav, 0))
        assertEquals(5, InFlightImport.bankedSeconds(wav, 90))
        assertEquals(4, InFlightImport.bankedSeconds(wav, 4))
    }
}
