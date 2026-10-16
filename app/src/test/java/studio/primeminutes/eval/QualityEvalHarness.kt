package studio.primeminutes.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import studio.primeminutes.core.ask.AskEngine
import studio.primeminutes.core.asr.NemoModelFiles
import studio.primeminutes.core.asr.NemoStreamEngine
import studio.primeminutes.core.audio.GainNormalizer
import studio.primeminutes.core.audio.Mp4Tags
import studio.primeminutes.core.audio.SilenceSkipper
import studio.primeminutes.core.config.TranscriptionConfig
import studio.primeminutes.core.embed.LibraryIndexer
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.library.SessionLibrary
import studio.primeminutes.core.llm.MfaEngine
import studio.primeminutes.core.llm.MfaSession
import studio.primeminutes.core.llm.SpTokenizer
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelCard
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.core.reader.ReaderLane
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.core.session.PrimeMinutesSession
import studio.primeminutes.data.SpeakerName
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import java.io.DataInputStream
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host quality evaluation (docs/quality-eval-2026-10.md): the app's own Kotlin code on the JVM, with
 * the app's native engines built for Linux (tools/quality-eval/build_host_jni.sh). Skipped unless
 * PM_EVAL_MODE and PM_EVAL_CFG are set; tools/quality-eval/run_harness.sh sets them.
 *
 *  - asr:    16 kHz mono float PCM -> GainNormalizer -> 4096-sample blocks -> SilenceSkipper ->
 *            NemoStreamEngine (litert_host, the shipped ggml-free engine) -> final snapshot, times
 *            mapped back to the source, as ImportPipeline does.
 *  - reader: a transcript -> ReaderLane with the model's card -> prose, actions, title, notes, as
 *            ReaderWorker.finishLane does (the ReaderRegressionGate approach: the whole transcript
 *            at once, not the live reading during the import).
 *  - ask:    the meetings seeded into a Robolectric library (session.m4a with the embedded session,
 *            indexed by LibraryIndexer with the app's embedder), then AskEngine.ask over the whole
 *            library, one fresh conversation per question.
 *  - engines: two MfaEngines of the same model in one process, generating on two threads at once
 *            (Ask next to a reading); each reply must equal the one the engine gives alone.
 */
@RunWith(RobolectricTestRunner::class)
class QualityEvalHarness {
    private val mode = System.getenv("PM_EVAL_MODE")
    private val cfg by lazy { JSONObject(File(System.getenv("PM_EVAL_CFG")).readText()) }
    private val out by lazy { File(cfg.getString("out")).apply { mkdirs() } }

    @Test fun run() {
        assumeTrue("set PM_EVAL_MODE and PM_EVAL_CFG", mode != null && System.getenv("PM_EVAL_CFG") != null)
        ShadowLog.stream = PrintStream(File(out, "log-$mode-${System.currentTimeMillis()}.txt").outputStream(), true)
        when (mode) {
            "asr" -> asr()
            "reader" -> reader()
            "ask" -> ask()
            "engines" -> engines()
            else -> error("unknown mode $mode")
        }
    }

    /** Outputs in Simplified Chinese too (the zh references are Simplified), with the app's own converter. */
    private val hans by lazy { OpenCcConverter.get(ApplicationProvider.getApplicationContext(), ChineseScript.SIMPLIFIED) }

    private fun items() = cfg.getJSONArray("items").let { a -> List(a.length()) { a.getJSONObject(it) } }

    // ---------------------------------------------------------------------------------------- asr

    private fun asr() = runBlocking {
        val m = cfg.getJSONObject("models")
        val threads = cfg.optInt("threads", 4)
        val gate = Semaphore(cfg.optInt("parallel", 1))
        items().map { item ->
            async(Dispatchers.IO) { gate.withPermit { asrOne(item, m, threads) } }
        }.awaitAll()
    }

    private suspend fun asrOne(item: JSONObject, m: JSONObject, threads: Int) {
        val name = item.getString("name")
        val dest = File(out, "$name.asr.json")
        if (dest.exists()) return
        val pcm = File(item.getString("pcm"))
        val audioSec = pcm.length() / 4 / 16000.0
        // AudioDecoder.decodeToWav16k(normalize = true): GainNormalizer in front of the 4096-sample
        // ChunkingSink. Streamed from the file: the normalizer itself holds at most its 180 s lead.
        val ready = ArrayDeque<FloatArray>()
        var block = FloatArray(4096); var bn = 0
        val norm = GainNormalizer { v -> block[bn++] = v; if (bn == block.size) { ready.addLast(block); block = FloatArray(4096); bn = 0 } }
        val chunks = flow {
            DataInputStream(pcm.inputStream().buffered(1 shl 20)).use { ins ->
                val buf = ByteArray(1 shl 16)
                var left = pcm.length()
                while (left > 0) {
                    val want = minOf(buf.size.toLong(), left).toInt()
                    ins.readFully(buf, 0, want); left -= want
                    val bb = ByteBuffer.wrap(buf, 0, want).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(want / 4) { norm.add(bb.float) }
                    while (ready.isNotEmpty()) emit(ready.removeFirst())
                }
            }
            norm.finish()
            while (ready.isNotEmpty()) emit(ready.removeFirst())
            if (bn > 0) emit(block.copyOf(bn))
        }
        val engine = NemoStreamEngine(
            NemoModelFiles(File(m.getString("xasr_bin")), File(m.getString("diar_bin"))), threads,
            TranscriptionConfig().speakerDelaySec, backend = "litert_host",
            xasrTflite = File(m.getString("xasr_tflite")), diarTflite = File(m.getString("diar_tflite")),
            diarPreF32 = File(m.getString("pre_f32")),
        )
        val t0 = System.nanoTime()
        var last: List<TranscriptEvent.Utterance> = emptyList()
        var snapshots = 0
        val skipper = SilenceSkipper()
        val st = engine.use {
            engine.transcribeLive(skipper.apply(chunks)).collect { e ->
                if (e is TranscriptEvent.UtteranceSnapshot) { last = e.utterances; snapshots++ }
            }
            runCatching { engine.stats() }.getOrNull()
        }
        val wall = (System.nanoTime() - t0) / 1e9
        val utts = last.map { it.copy(startSec = skipper.toOriginal(it.startSec), endSec = skipper.toOriginal(it.endSec)) }
        val arr = JSONArray()
        utts.forEach { u ->
            arr.put(JSONObject().put("index", u.index).put("text", u.text).put("start", u.startSec).put("end", u.endSec)
                .put("speaker", u.speaker ?: JSONObject.NULL))
        }
        dest.writeText(JSONObject().put("name", name).put("audio_sec", audioSec).put("gain", norm.gain.toDouble())
            .put("skipped_sec", skipper.skippedSec).put("wall_sec", wall).put("threads", threads).put("snapshots", snapshots)
            .put("speakers", utts.mapNotNull { it.speaker }.distinct().size)
            .put("peak_rss_mb", st?.peakRssMb ?: -1.0).put("utterances", arr).toString(1))
    }

    // ------------------------------------------------------------------------------------- reader

    private fun utterances(f: File): List<TranscriptEvent.Utterance> {
        val a = JSONObject(f.readText()).getJSONArray("utterances")
        return List(a.length()) { i -> a.getJSONObject(i) }.map {
            TranscriptEvent.Utterance(it.getInt("index"), it.getString("text"), it.getDouble("start"), it.getDouble("end"),
                if (it.isNull("speaker")) null else it.getInt("speaker"))
        }
    }

    private fun reader() = runBlocking {
        val models = File(cfg.getString("models_dir"))
        val threads = cfg.optInt("threads", 4)
        val llms = cfg.getJSONArray("llm").let { a -> List(a.length()) { a.getString(it) } }
        // One model at a time, as in the app (memory; two engines are safe since the attention op locks: mode "engines").
        for (id in llms) readAll(id, models, threads)
    }

    private suspend fun readAll(id: String, models: File, threads: Int) {
            val spec = LlmRegistry.byId(id)
            check(spec.id == id) { "unknown model $id" }
            // Prompt experiments: another card file in place of the model's own ("card"), and only the
            // first "limit_sec" seconds of each meeting.
            val card = cfg.optString("card").ifEmpty { null }?.let { ModelCard.parse(File(it).readText()) } ?: spec.card
            val limitSec = cfg.optDouble("limit_sec", 0.0)
            val dir = File(models, spec.dirName)
            val todo = items().filter { !File(out, "${it.getString("name")}.reader.${spec.id}.json").exists() }
            if (todo.isEmpty()) return
            val tl = System.nanoTime()
            val engine = MfaEngine.load(File(dir, spec.mainFile).parentFile!!.path, ctx = spec.maxCtx, threads = threads,
                weightCache = File(dir, ModelManager.WEIGHT_CACHE).path, stopIds = spec.template.stopIds)
            val loadSec = (System.nanoTime() - tl) / 1e9
            val tok = SpTokenizer.load(File(dir, spec.tokenizerFile).path)
            try {
                for (item in todo) {
                    val name = item.getString("name")
                    val utts = utterances(File(item.getString("utts"))).let { u -> if (limitSec > 0) u.filter { it.startSec < limitSec } else u }
                    val drops = HashMap<String, Int>()
                    val t0 = System.nanoTime()
                    val session = MfaSession(engine, tok, spec.sampler.topK, spec.sampler.topP, template = spec.template)
                    var tFinish = 0.0; var tProse = 0.0
                    var language: String? = null; var proseRaw: String? = null
                    val (res, prose, title) = ReaderLane(session, File(dir, spec.systemPromptFile).readText(), card) { ev ->
                        if (ev is AgentEvent.NoteDropped) synchronized(drops) { drops.merge(ev.reason.name, 1, Int::plus) }
                    }.use { lane ->
                        lane.start()
                        val r = lane.finish(utts)
                        tFinish = (System.nanoTime() - t0) / 1e9
                        val p = runCatching { lane.prose(r.journal) }.getOrNull()
                        tProse = (System.nanoTime() - t0) / 1e9
                        language = lane.language; proseRaw = lane.lastProseRaw
                        Triple(r, p, runCatching { lane.title(r.journal) }.getOrNull())
                    }
                    val secs = (System.nanoTime() - t0) / 1e9
                    // ReaderWorker.finishLane's outputs (no script conversion: the converter is the user's setting).
                    val minutes = prose ?: res.minutes
                    val actions = res.actions.joinToString("\n") { "- ${it.text.trimEnd('。')} [${it.ts}]" }.ifEmpty { "-" }
                    val notes = res.journal.joinToString("\n") { ReaderProtocol.render(it) }
                    File(out, "$name.reader.${spec.id}.json").writeText(JSONObject().put("meeting", name).put("model", spec.id)
                        .put("threads", threads).put("load_sec", loadSec).put("seconds", secs).put("finish_sec", tFinish).put("prose_sec", tProse)
                        .put("utterances", utts.size).put("chars", utts.sumOf { it.text.length })
                        .put("notes_count", res.journal.size).put("actions_count", res.actions.size).put("dropped", JSONObject(drops as Map<*, *>))
                        .put("title", title).put("summary", minutes).put("prose_ok", prose != null).put("minutes_raw", res.minutes)
                        .put("actions", actions).put("notes", notes)
                        .put("language", language ?: JSONObject.NULL).put("card", card.name).put("limit_sec", limitSec)
                        .put("prose_raw", proseRaw ?: JSONObject.NULL)
                        .put("tags_raw", JSONArray(TS_ANY.findAll(proseRaw.orEmpty()).map { it.value }.toList()))
                        .put("tags_bad_raw", JSONArray(TS_ANY.findAll(proseRaw.orEmpty()).map { it.value }.filter { !WELL_FORMED.matches(it) || it.substring(1, it.length - 1) !in res.journal.map { n -> n.ts } }.toList()))
                        .put("tags_bad_out", JSONArray(TS_ANY.findAll(minutes).map { it.value }.filter { !WELL_FORMED.matches(it) || it.substring(1, it.length - 1) !in res.journal.map { n -> n.ts } }.toList()))
                        .put("hay_hans", hans.convert(listOf(title.orEmpty(), minutes, actions, notes).joinToString("\n"))).toString(1))
                }
            } finally { engine.close(); tok.close() }
    }

    /** Any bracket of digits and separators (a timestamp tag, well-formed or not), and a well-formed one. */
    private val TS_ANY = Regex("""\[[0-9:：\s,，、;；\-–~～]*\d[0-9:：\s,，、;；\-–~～]*\]""")
    private val WELL_FORMED = Regex("""\[\d+:\d{2}(?::\d{2})?\]""")

    // ---------------------------------------------------------------------------------------- ask

    private fun ask() = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // The models where ModelManager looks for them (filesDir/models), without copying gigabytes.
        val modelsLink = File(ctx.filesDir, "models")
        modelsLink.deleteRecursively()
        java.nio.file.Files.createSymbolicLink(modelsLink.toPath(), File(cfg.getString("models_dir")).toPath())
        val llm = cfg.getString("llm")
        TranscriptionConfig.Holder.config = TranscriptionConfig.Holder.config.copy(llmModelId = llm)
        check(ModelManager(ctx).llmReady(LlmRegistry.byId(llm))) { "reader model not in place" }
        SessionLibrary.root(ctx).deleteRecursively()

        // Seed: one library entry per meeting, its session.m4a carrying the transcript and the reading.
        val encode = PrimeMinutesSession::class.java.getDeclaredMethod("encodeSession", List::class.java, Map::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
        val m4a = File(cfg.getString("m4a"))
        val ids = LinkedHashMap<String, String>()   // meeting name -> entry id
        for (item in items()) {
            val name = item.getString("name")
            val utts = utterances(File(item.getString("utts")))
            val r = JSONObject(File(item.getString("reader")).readText())
            val title = r.optString("title").ifBlank { name }
            val wav = File(ctx.cacheDir, "$name.wav").apply { parentFile?.mkdirs(); writeBytes(ByteArray(44)) }
            val e = SessionLibrary.promoteRecording(ctx, wav, utts.lastOrNull()?.endSec?.toInt() ?: 0)!!
            val blob = encode.invoke(PrimeMinutesSession, utts, emptyMap<Int, SpeakerName>(), r.optString("summary"),
                r.optString("actions"), title, r.optString("notes").ifBlank { null }, "x-asr", "nemo", llm) as String
            check(Mp4Tags.write(m4a, e.sessionFile, blob, title, null, null)) { "session write failed" }
            SessionLibrary.rename(ctx, e.dir, title)
            check(LibraryIndexer.index(ctx, SessionLibrary.byId(ctx, e.id)!!)) { "$name not indexed" }
            ids[name] = e.id
        }
        File(out, "library.json").writeText(JSONObject(ids as Map<*, *>).toString(1))
        val entries = ids.values.map { SessionLibrary.byId(ctx, it)!! }

        val qs = cfg.getJSONArray("questions").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val results = JSONArray()
        val dest = File(out, "ask.$llm.json")
        for (q in qs) {
            AskEngine.reset()
            delay(300)
            var tFirst = 0L
            val t0 = System.nanoTime()
            val watch = launch(Dispatchers.Default) {
                AskEngine.turns.collect { ts -> if (tFirst == 0L && ts.lastOrNull()?.answer?.isNotBlank() == true) tFirst = System.nanoTime() }
            }
            AskEngine.ask(ctx, entries, q.getString("q"), q.getString("ui"))
            delay(200)
            while (true) {
                val t = AskEngine.turns.value.lastOrNull()
                val p = AskEngine.phase.value
                if (t != null && t.done && (p is AskEngine.Phase.Idle || p is AskEngine.Phase.Failed || p is AskEngine.Phase.Busy)) break
                if ((System.nanoTime() - t0) / 1e9 > 600) break
                delay(100)
            }
            watch.cancel()
            val turn = AskEngine.turns.value.lastOrNull()
            val ex = JSONArray()
            turn?.excerpts?.forEach { x ->
                ex.put(JSONObject().put("k", x.k).put("entry", x.entry.id).put("meeting", ids.entries.first { it.value == x.entry.id }.key)
                    .put("sec", x.sec).put("at", x.at).put("text", x.text))
            }
            results.put(JSONObject().put("id", q.getString("id")).put("q", q.getString("q")).put("ui", q.getString("ui"))
                .put("answer", turn?.answer ?: "").put("answer_hans", hans.convert(turn?.answer ?: "")).put("phase", AskEngine.phase.value.toString())
                .put("total_sec", (System.nanoTime() - t0) / 1e9).put("first_token_sec", if (tFirst == 0L) -1.0 else (tFirst - t0) / 1e9)
                .put("excerpts", ex))
            dest.writeText(results.toString(1))
        }
        AskEngine.release()
        delay(500)
    }

    // ------------------------------------------------------------------------------------ engines

    /** Two engines, two threads, [rounds] times: no crash, and greedy replies identical to the ones
     *  computed with one engine at a time. Prompts long enough for the prefill path of i8_attn. */
    private fun engines() {
        val spec = LlmRegistry.byId(cfg.getString("llm"))
        val dir = File(File(cfg.getString("models_dir")), spec.dirName)
        val threads = cfg.optInt("threads", 4)
        val rounds = cfg.optInt("rounds", 3)
        fun load() = MfaEngine.load(File(dir, spec.mainFile).parentFile!!.path, ctx = spec.maxCtx, threads = threads,
            weightCache = File(dir, ModelManager.WEIGHT_CACHE).path, stopIds = spec.template.stopIds)
        val tok = SpTokenizer.load(File(dir, spec.tokenizerFile).path)
        val a = load(); val b = load()
        val log = StringBuilder()
        try {
            val base = "Meeting transcript. The team reviewed the budget, the hiring plan and the launch date. "
            fun prompt(r: Int, who: String) =
                intArrayOf(MfaEngine.BOS) + tok.encode("$who round $r. " + base.repeat(40) + " Summarize in one sentence:")
            for (r in 0 until rounds) {
                val pa = prompt(r, "A"); val pb = prompt(r, "B")
                // Both passes start from the same cache state (a short unrelated prefix), so the prefill
                // is chunked the same way and greedy replies must match exactly.
                fun fresh() {
                    a.generate(intArrayOf(MfaEngine.BOS) + tok.encode("reset a"), 0, 0f, 1, 1f, 0)
                    b.generate(intArrayOf(MfaEngine.BOS) + tok.encode("reset b"), 0, 0f, 1, 1f, 0)
                }
                fresh()
                val refA = a.generate(pa, 24, 0f, 1, 1f, 0)
                val refB = b.generate(pb, 24, 0f, 1, 1f, 0)
                fresh()
                var gotA = IntArray(0); var gotB = IntArray(0)
                val t0 = System.nanoTime()
                val ta = Thread { gotA = a.generate(pa, 24, 0f, 1, 1f, 0) }
                val tb = Thread { gotB = b.generate(pb, 24, 0f, 1, 1f, 0) }
                ta.start(); tb.start(); ta.join(); tb.join()
                val line = "round $r: prompt ${pa.size}/${pb.size} tok, concurrent ${"%.1f".format(java.util.Locale.ROOT, (System.nanoTime() - t0) / 1e9)} s, " +
                    "A ${if (gotA.contentEquals(refA)) "same" else "DIFFERS"}, B ${if (gotB.contentEquals(refB)) "same" else "DIFFERS"}"
                println(line); log.appendLine(line)
                check(gotA.contentEquals(refA) && gotB.contentEquals(refB)) { line }
            }
        } finally {
            File(out, "engines-${spec.id}.txt").writeText(log.toString())
            a.close(); b.close(); tok.close()
        }
    }
}
