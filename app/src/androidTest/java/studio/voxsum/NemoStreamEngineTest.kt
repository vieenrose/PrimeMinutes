package studio.voxsum

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.voxsum.core.asr.NemoModelFiles
import studio.voxsum.core.asr.NemoStreamEngine
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ModelManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device check of the streaming ASR + diarization engine: a bundled two-speaker clip streamed in
 * mic-sized 2048-sample blocks (what AudioRecorder emits) must produce text, frequent live snapshots
 * whose stable prefix only grows, and two speakers. Also logs the real-time factor.
 */
@RunWith(AndroidJUnit4::class)
class NemoStreamEngineTest {

    // audio.cpp unpacks the diarizer spec under $TMPDIR (default /data/local/tmp, not writable by apps on
    // some phones); the service points it at cacheDir, so do the same here.
    @Before
    fun tmpDir() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        android.system.Os.setenv("TMPDIR", ctx.cacheDir.absolutePath, true)
    }

    @Test
    fun streamsTwoSpeakerClip() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val models = ModelManager(inst.targetContext)
        if (!models.asrReady()) models.ensureAsrModels { }
        val pcm = readWav16kMono(inst.context.assets.open("two-speaker.wav").use { it.readBytes() })
        val chunks = flow {
            var i = 0
            while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
        }
        var snapshots = 0
        var lastStable = 0
        var stableDecreased = false
        var last: List<TranscriptEvent.Utterance> = emptyList()
        val t0 = System.nanoTime()
        val engine = NemoStreamEngine(models.asrFiles(), threads = 4)
        engine.use {
            engine.transcribeLive(chunks).collect { e ->
                if (e is TranscriptEvent.UtteranceSnapshot) {
                    snapshots++
                    last = e.utterances
                    if (e.stable < lastStable) stableDecreased = true
                    lastStable = e.stable
                }
            }
        }
        val rtf = (System.nanoTime() - t0) / 1e9 / (pcm.size / 16000.0)
        Log.i("NemoStreamEngineTest", "rtf=%.3f snapshots=$snapshots speakers=${engine.speakerCount} text=%s"
            .format(rtf, last.joinToString(" | ") { "${it.speaker}: ${it.text}" }))
        assertTrue("expected text", last.any { it.text.isNotBlank() })
        val audioSec = pcm.size / 16000.0
        // Live view updates every 0.5 s of audio, skipped when nothing changed (silence): demand at
        // least one per 2 s of audio, which the old decaying cadence (fed/60) could not guarantee.
        assertTrue("expected >= 1 snapshot per 2 s of audio, got $snapshots for %.1fs".format(audioSec),
            snapshots >= audioSec.toInt() / 2)
        assertTrue("stable prefix must never shrink during the live view", !stableDecreased)
        assertTrue("final snapshot must be fully stable", lastStable == last.size)
        assertTrue("expected 2 speakers, got ${engine.speakerCount}", engine.speakerCount == 2)
    }

    /**
     * LiteRT CPU backend (the X-ASR and Sortformer graphs are the .tflite files). Needs the four files pushed to
     * files/models/nemo-litert/ of the app (adb shell run-as); skipped when they are absent.
     */
    @Test
    fun streamsTwoSpeakerClipOnLiteRtCpu() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dir = File(inst.targetContext.filesDir, "models/nemo-litert")
        val xt = File(dir, "xasr_stream_q8_octav.tflite")
        val dt = File(dir, "sortformer_int8_f190.tflite")
        val files = NemoModelFiles(File(dir, "x-asr-zh-en-q8_0.gguf"), File(dir, "nemotron-3-diarization-q8_0.gguf"))
        org.junit.Assume.assumeTrue("litert models not pushed", listOf(xt, dt, files.xasr, files.diar).all { it.exists() })
        val pcm = readWav16kMono(inst.context.assets.open("two-speaker.wav").use { it.readBytes() })
        val chunks = flow {
            var i = 0
            while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
        }
        var last: List<TranscriptEvent.Utterance> = emptyList()
        val t0 = System.nanoTime()
        val engine = NemoStreamEngine(files, threads = 4, backend = "litert_cpu", xasrTflite = xt, diarTflite = dt)
        engine.use {
            engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) last = e.utterances }
        }
        val rtf = (System.nanoTime() - t0) / 1e9 / (pcm.size / 16000.0)
        Log.i("NemoStreamEngineTest", "litert_cpu rtf=%.3f speakers=${engine.speakerCount} text=%s"
            .format(rtf, last.joinToString(" | ") { "${it.speaker}: ${it.text}" }))
        assertTrue("expected text", last.any { it.text.isNotBlank() })
        assertTrue("expected 2 speakers, got ${engine.speakerCount}", engine.speakerCount == 2)
    }

    /**
     * ggml vs LiteRT CPU on a long clip (files/clip.wav of the app, 16 kHz mono 16-bit, pushed by hand): RTF,
     * turns, speakers and the word-level agreement of the two transcripts. Skipped when the clip or models are absent.
     */
    @Test
    fun longClipGgmlVsLiteRtCpu() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dir = File(inst.targetContext.filesDir, "models/nemo-litert")
        val clip = File(inst.targetContext.filesDir, "clip.wav")
        val xt = File(dir, "xasr_stream_q8_octav.tflite")
        val dt = File(dir, "sortformer_int8_f190.tflite")
        val files = NemoModelFiles(File(dir, "x-asr-zh-en-q8_0.gguf"), File(dir, "nemotron-3-diarization-q8_0.gguf"))
        org.junit.Assume.assumeTrue("clip/models not pushed", (listOf(xt, dt, files.xasr, files.diar, clip)).all { it.exists() })
        val pcm = readWav16kMono(clip.readBytes())
        suspend fun run(tag: String, backend: String): List<TranscriptEvent.Utterance> {
            val chunks = flow {
                var i = 0
                while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
            }
            var last: List<TranscriptEvent.Utterance> = emptyList()
            val t0 = System.nanoTime()
            val engine = if (backend == "ggml") NemoStreamEngine(files, threads = 4)
                else NemoStreamEngine(files, threads = 4, backend = backend, xasrTflite = xt, diarTflite = dt)
            engine.use {
                engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) last = e.utterances }
            }
            val rtf = (System.nanoTime() - t0) / 1e9 / (pcm.size / 16000.0)
            Log.i("NemoStreamEngineTest", "LONG $tag rtf=%.3f speakers=${engine.speakerCount} turns=${last.size} words=${last.sumOf { it.text.trim().split(Regex("\\s+")).size }}".format(rtf))
            return last
        }
        val a = run("ggml", "ggml")
        val b = run("litert_cpu", "litert_cpu")
        fun words(u: List<TranscriptEvent.Utterance>) = u.joinToString(" ") { it.text }.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wa = words(a); val wb = words(b)
        // Word-level edit distance.
        var prev = IntArray(wb.size + 1) { it }
        for (i in 1..wa.size) {
            val cur = IntArray(wb.size + 1); cur[0] = i
            for (j in 1..wb.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (wa[i - 1] == wb[j - 1]) 0 else 1)
            prev = cur
        }
        Log.i("NemoStreamEngineTest", "LONG word diff ggml vs litert: %d edits / %d words = %.2f%%".format(prev[wb.size], wa.size, 100.0 * prev[wb.size] / maxOf(1, wa.size)))
        assertTrue("expected text", wb.isNotEmpty())
    }

    private fun readWav16kMono(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                return FloatArray(n) { bb.getShort(pos + 8 + 2 * it) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in wav")
    }
}
