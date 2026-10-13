package studio.primeminutes

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.data.SpeakerName
import java.io.File

/** Speaker names of every length must survive the .m4a session round-trip byte for byte. */
@RunWith(AndroidJUnit4::class)
class SpeakerNameRoundTripTest {

    @Test fun namesOfEveryLengthSurvive() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(ctx.cacheDir, "spk_src.wav").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("en.wav").use { it.readBytes() })
        }
        val dir = File(ctx.cacheDir, "spk_build").apply { mkdirs() }
        val names = listOf("A", "Al", "Bob", "Alice", "Christopher", "小明", "王大明")
        val utts = names.indices.map {
            TranscriptEvent.Utterance(it, "line $it", it * 1.0, it + 1.0, speaker = it)
        }
        val map = names.withIndex().associate { (i, n) -> i to SpeakerName(n, "user", "") }

        val built = PrimeMinutesSession.buildSession(
            ctx, dir, Uri.fromFile(wav), utts, map,
            summary = null, actionItems = null, title = "spk",
            notes = null, asrModelId = "x-asr", asrBackend = "x-asr", llmModelId = "gemma", coverEnabled = false,
            fileName = "spk.m4a", format = PrimeMinutesSession.Format.M4A,
        )
        val loaded = PrimeMinutesSession.open(ctx, Uri.fromFile(built!!.file))
        names.forEachIndexed { i, expected ->
            assertEquals("speaker $i name must round-trip intact", expected, loaded.speakerNames[i]?.name)
        }
    }
}
