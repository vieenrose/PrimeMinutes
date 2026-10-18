package studio.primeminutes

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.core.asr.NemoModelFiles
import studio.primeminutes.core.asr.NemoStreamEngine
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.models.ModelManager
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
        if (!models.asrReady(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)) models.ensureAsrModels(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS) { }
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
        val engine = NemoStreamEngine(models.asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)!!.host, threads = 4)
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
        val models = ModelManager(inst.targetContext)
        if (!models.asrReady(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)) models.ensureAsrModels(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS) { }
        val lit = models.asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)?.litert ?: error("LiteRT ASR graphs missing")
        val files = models.asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)!!.host
        val pcm = readWav16kMono(inst.context.assets.open("two-speaker.wav").use { it.readBytes() })
        val chunks = flow {
            var i = 0
            while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
        }
        var last: List<TranscriptEvent.Utterance> = emptyList()
        val t0 = System.nanoTime()
        // The shipped ggml-free path: host weights (xasr_host.bin / diar_host.bin) + the LiteRT graphs, no GGUF.
        val engine = NemoStreamEngine(files, threads = 4, backend = "litert_host", xasrTflite = lit.xasr,
            diarTflite = lit.diar, diarPreF32 = lit.preEncode)
        engine.use {
            engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) last = e.utterances }
        }
        val rtf = (System.nanoTime() - t0) / 1e9 / (pcm.size / 16000.0)
        Log.i("NemoStreamEngineTest", "litert_host rtf=%.3f speakers=${engine.speakerCount} text=%s"
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
        val hostFiles = NemoModelFiles(File(dir, "xasr_host.bin"), File(dir, "diar_host.bin"))
        // -e backends ggml,litert_cpu (default) | litert_host,litert_cpu ...: the first two entries are compared.
        val backends = (InstrumentationRegistry.getArguments().getString("backends") ?: "ggml,litert_cpu").split(",")
        val needGguf = backends.any { it == "ggml" || it == "litert_cpu" }
        org.junit.Assume.assumeTrue("clip/models not pushed", (listOf(xt, dt, clip) + (if (needGguf) listOf(files.xasr, files.diar) else listOf()) + (if ("litert_host" in backends) listOf(hostFiles.xasr, hostFiles.diar) else listOf())).all { it.exists() })
        // -e noPre true: ablation, LiteRT without the pre-encode hook (audio.cpp ggml linear instead).
        val noPre = InstrumentationRegistry.getArguments().getString("noPre") == "true"
        val pre = if (noPre) null else ModelManager(inst.targetContext).asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)?.litert?.preEncode
        org.junit.Assume.assumeTrue("pre-encode matrix missing", noPre || pre != null)
        val pcm = readWav16kMono(clip.readBytes())
        suspend fun run(tag: String, backend: String): List<TranscriptEvent.Utterance> {
            val chunks = flow {
                var i = 0
                while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
            }
            var last: List<TranscriptEvent.Utterance> = emptyList()
            try { File("/proc/self/clear_refs").writeText("5") } catch (_: Exception) {}   // reset the peak RSS (VmHWM)
            val t0 = System.nanoTime()
            val engine = if (backend == "ggml") NemoStreamEngine(files, threads = 4)
                else NemoStreamEngine(if (backend == "litert_host") hostFiles else files, threads = 4, backend = backend, xasrTflite = xt, diarTflite = dt, diarPreF32 = pre)
            engine.use {
                engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) last = e.utterances }
            }
            val rtf = (System.nanoTime() - t0) / 1e9 / (pcm.size / 16000.0)
            Log.i("NemoStreamEngineTest", "LONG $tag rtf=%.3f speakers=${engine.speakerCount} turns=${last.size} words=${last.sumOf { it.text.trim().split(Regex("\\s+")).size }} ${File("/proc/self/status").readLines().first { it.startsWith("VmHWM") } + File("/proc/self/status").readLines().filter { it.startsWith("RssAnon") || it.startsWith("RssFile") }}".format(rtf))
            return last
        }
        val a = run(backends[0], backends[0])
        val b = run(backends[1], backends[1])
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

    /** Peak RSS of the whole app path: E2B reader loaded (mfa-e2b) while the litert_host engine streams the clip. */
    @Test
    fun peakRssEngineWithReader() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val dir = File(ctx.filesDir, "models/nemo-litert")
        val clip = File(ctx.filesDir, "clip.wav")
        val e2b = File(ctx.filesDir, "mfa-e2b")
        val xt = File(dir, "xasr_stream_q8_octav.tflite"); val dt = File(dir, "sortformer_int8_f190.tflite")
        val hostFiles = NemoModelFiles(File(dir, "xasr_host.bin"), File(dir, "diar_host.bin"))
        val pre = ModelManager(ctx).asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)?.litert?.preEncode
        org.junit.Assume.assumeTrue("not pushed", listOf(xt, dt, clip, hostFiles.xasr, hostFiles.diar, File(e2b, "prefill_decode_fused.tflite")).all { it.exists() } && pre != null)
        fun mem() = File("/proc/self/status").readLines().filter { it.startsWith("VmHWM") || it.startsWith("RssAnon") || it.startsWith("RssFile") }.joinToString(" ") { it.replace(Regex("\\s+"), " ") }
        val pcm = readWav16kMono(clip.readBytes())
        val chunks = flow { var i = 0; while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e } }
        try { File("/proc/self/clear_refs").writeText("5") } catch (_: Exception) {}
        studio.primeminutes.core.llm.MfaEngine.load(e2b.path, ctx = 4096, threads = 4, weightCache = File(ctx.cacheDir, "mfa-e2b.wcache").path).use { reader ->
            Log.i("NemoStreamEngineTest", "RSS reader loaded: ${mem()}")
            val engine = NemoStreamEngine(hostFiles, threads = 4, backend = "litert_host", xasrTflite = xt, diarTflite = dt, diarPreF32 = pre)
            var turns = 0
            engine.use { engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) turns = e.utterances.size } }
            Log.i("NemoStreamEngineTest", "RSS reader + litert_host engine done (turns=$turns): ${mem()}")
        }
        Unit
    }

    /**
     * Every .wav under files/gold (16 kHz mono 16-bit) on both backends; the final transcript goes to
     * files/gold/<clip>.<backend>.txt for scoring against a reference on the host. Skipped without models/clips.
     */
    @Test
    fun goldenClipsBothBackends() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dir = File(inst.targetContext.filesDir, "models/nemo-litert")
        val gold = File(inst.targetContext.filesDir, "gold")
        val xt = File(dir, "xasr_stream_q8_octav.tflite")
        val dt = File(dir, "sortformer_int8_f190.tflite")
        val files = NemoModelFiles(File(dir, "x-asr-zh-en-q8_0.gguf"), File(dir, "nemotron-3-diarization-q8_0.gguf"))
        val clips = gold.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()
        org.junit.Assume.assumeTrue("clips/models not pushed", clips.isNotEmpty() && listOf(xt, dt, files.xasr, files.diar).all { it.exists() })
        val pre = ModelManager(inst.targetContext).asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)?.litert?.preEncode
        // -e backends litert_host (default, ggml-free) | ggml,litert_host ...: the ggml reference is opt-in.
        val backends = (InstrumentationRegistry.getArguments().getString("backends") ?: "litert_host").split(",")
        val hostFiles = ModelManager(inst.targetContext).asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)!!.host
        for (clip in clips) {
            val pcm = readWav16kMono(clip.readBytes())
            for (backend in backends) {
                val chunks = flow {
                    var i = 0
                    while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
                }
                var last: List<TranscriptEvent.Utterance> = emptyList()
                val t0 = System.nanoTime()
                val engine = if (backend == "ggml") NemoStreamEngine(files, threads = 4)
                    else NemoStreamEngine(hostFiles, threads = 4, backend = backend, xasrTflite = xt, diarTflite = dt, diarPreF32 = pre)
                engine.use {
                    engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) last = e.utterances }
                }
                val rtf = (System.nanoTime() - t0) / 1e9 / (pcm.size / 16000.0)
                File(gold, "${clip.nameWithoutExtension}.$backend.txt").writeText(last.joinToString("\n") { it.text })
                Log.i("NemoStreamEngineTest", "GOLD ${clip.name} $backend rtf=%.3f speakers=${engine.speakerCount} turns=${last.size}".format(rtf))
            }
        }
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

    /** The app's own path, from an empty models dir: download (GGUF + LiteRT), warm the weight cache, then the
     *  LiteRT engine on the two-speaker clip. Logs the times; the ggml run on the same clip is the reference. */
    @Test
    fun appPathDownloadsAndRunsLiteRt() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val models = ModelManager(inst.targetContext)
        if (InstrumentationRegistry.getArguments().getString("seeded") != "true") models.deleteAsr()   // -e seeded true: files pushed by hand, no download
        val t0 = System.nanoTime()
        models.ensureAsrModels(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS) { }
        val tDl = (System.nanoTime() - t0) / 1e9
        val t1 = System.nanoTime()
        models.warmLitertCache(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS, threads = 4)
        val tWarm = (System.nanoTime() - t1) / 1e9
        val lit = models.asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)?.litert
        Log.i("NemoStreamEngineTest", "APP download %.1fs warm %.1fs litert=%s".format(tDl, tWarm, lit != null))
        org.junit.Assert.assertNotNull("LiteRT files not provisioned", lit)
        val pcm = readWav16kMono(inst.context.assets.open("two-speaker.wav").use { it.readBytes() })
        val chunks = flow {
            var i = 0
            while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e }
        }
        var last: List<TranscriptEvent.Utterance> = emptyList()
        val t2 = System.nanoTime()
        NemoStreamEngine(models.asrEngineFiles(studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS)!!.host, threads = 4, backend = "litert_host", xasrTflite = lit!!.xasr, diarTflite = lit.diar, diarPreF32 = lit.preEncode).use { engine ->
            engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) last = e.utterances }
            Log.i("NemoStreamEngineTest", "APP litert run %.2fs speakers=%s turns=%d".format((System.nanoTime() - t2) / 1e9, engine.speakerCount, last.size))
        }
        org.junit.Assert.assertTrue("no turns", last.isNotEmpty())
    }

    /** Latency switch on a real install (emulator check, needs the network): the chosen variant ([-e from], default
     *  1920) is installed; switching to [-e to] keeps it as the stand-in, downloads the new graph from the pinned
     *  repo, then the new one runs and the old one is reclaimed. */
    @Test
    fun latencySwitchDownloadsThenReclaims() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val a = InstrumentationRegistry.getArguments()
        val from = a.getString("from")?.toInt() ?: studio.primeminutes.core.models.AsrRegistry.DEFAULT_MS
        val to = a.getString("to")?.toInt() ?: 480
        val models = ModelManager(inst.targetContext)
        org.junit.Assert.assertTrue("from variant not installed", models.asrTargetReady(from))
        org.junit.Assert.assertEquals("stand-in", from, models.asrEngineFiles(to)?.variant?.latencyMs)
        val t0 = System.nanoTime()
        models.ensureAsrModels(to) { }
        Log.i("NemoStreamEngineTest", "SWITCH %d->%d download %.1fs".format(from, to, (System.nanoTime() - t0) / 1e9))
        val f = models.asrEngineFiles(to)!!
        org.junit.Assert.assertEquals(to, f.variant?.latencyMs)
        val pcm = readWav16kMono(inst.context.assets.open("two-speaker.wav").use { it.readBytes() })
        val chunks = flow { var i = 0; while (i < pcm.size) { val e = minOf(i + 2048, pcm.size); emit(pcm.copyOfRange(i, e)); i = e } }
        var last: List<TranscriptEvent.Utterance> = emptyList()
        val t2 = System.nanoTime()
        NemoStreamEngine(f.host, threads = 4, backend = "litert_host", xasrTflite = f.litert.xasr, diarTflite = f.litert.diar,
            diarPreF32 = f.litert.preEncode, tokenOffsetMs = f.tokenOffsetMs).use { engine ->
            engine.transcribeLive(chunks).collect { e -> if (e is TranscriptEvent.UtteranceSnapshot) last = e.utterances }
            Log.i("NemoStreamEngineTest", "SWITCH run %d ms graph %.2fs speakers=%s turns=%d text=%s".format(to, (System.nanoTime() - t2) / 1e9,
                engine.speakerCount, last.size, last.joinToString(" | ") { it.text }.take(300)))
        }
        org.junit.Assert.assertTrue("no turns", last.isNotEmpty())
        models.reclaimOtherAsr(f.variant!!)
        org.junit.Assert.assertFalse("old variant kept", models.variantReady(studio.primeminutes.core.models.AsrRegistry.byLatency(from)))
        org.junit.Assert.assertTrue("new variant gone", models.asrTargetReady(to))
    }
}
