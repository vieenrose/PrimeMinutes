package studio.primeminutes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.llm.MfaEngine
import studio.primeminutes.core.llm.MfaSession
import studio.primeminutes.core.llm.SpTokenizer
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.reader.ReaderLane
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File

/**
 * Regression gate for the reader: run it BEFORE shipping new weights, a new model card or a prompt
 * change. Each reference meeting (androidTest/assets/reader_gate, a fixed ASR transcript) goes
 * through the app's own path — ReaderLane with the model's card, then prose and title — and the
 * output must state at least `threshold` of the meeting's key facts (decisions, amounts, dates).
 * The threshold sits below the measured `baseline` (2026-10-16: 0.67–0.75 for E2B and E4B — the
 * readers do miss facts today); a change that lowers recall, or raises it, shows in the report.
 * The facts are written in Traditional Chinese; the output is converted to it before matching.
 *
 * Needs the model installed in the app under test (skipped otherwise). Pick it with
 * `-e llm <LlmRegistry id>` (default: the default reader). Report: logcat `ReaderGate` and
 * files/reader_gate/<meeting>-<model>.json. Takes minutes per meeting on a phone.
 *   adb shell am instrument -w -e class studio.primeminutes.ReaderRegressionGate \
 *     -e llm gemma4-e4b-meeting-agent-zh-mobile <test pkg>/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ReaderRegressionGate {
    @Test fun move() = gate("move")
    @Test fun weiya() = gate("weiya")

    private fun gate(name: String) = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        val spec = LlmRegistry.byId(InstrumentationRegistry.getArguments().getString("llm") ?: LlmRegistry.DEFAULT_ID)
        val mm = ModelManager(ctx)
        assumeTrue("model ${spec.id} not installed", mm.llmReady(spec))
        val ref = JSONObject(ins.context.assets.open("reader_gate/$name.json").bufferedReader().readText())
        val utts = ref.getJSONArray("utterances").let { a -> List(a.length()) { i -> a.getJSONObject(i) } }.map {
            TranscriptEvent.Utterance(it.getInt("index"), it.getString("text"), it.getDouble("start"), it.getDouble("end"),
                if (it.isNull("speaker")) null else it.getInt("speaker"))
        }

        val dir = mm.llmDir(spec)
        val engine = MfaEngine.load(File(dir, spec.mainFile).parentFile!!.path, ctx = spec.maxCtx, threads = 4,
            weightCache = File(dir, ModelManager.WEIGHT_CACHE).path, stopIds = spec.template.stopIds)
        val tok = SpTokenizer.load(File(dir, spec.tokenizerFile).path)
        val t0 = System.nanoTime()
        val (res, prose, title) = try {
            val session = MfaSession(engine, tok, spec.sampler.topK, spec.sampler.topP, template = spec.template)
            ReaderLane(session, File(dir, spec.systemPromptFile).readText(), spec.card) { }.use { lane ->
                lane.start()
                val r = lane.finish(utts)
                Triple(r, runCatching { lane.prose(r.journal) }.getOrNull(), runCatching { lane.title(r.journal) }.getOrNull())
            }
        } finally { engine.close(); tok.close() }
        val secs = (System.nanoTime() - t0) / 1e9

        val notes = res.journal.joinToString("\n") { ReaderProtocol.render(it) }
        val zhTw = OpenCcConverter.get(ctx, ChineseScript.TRADITIONAL)
        // Digits grouped or spaced either way ("110,000", "11 萬") match the plain spelling.
        val hay = zhTw.convert(listOf(notes, res.minutes, prose.orEmpty(), title.orEmpty()).joinToString("\n"))
            .replace(Regex("""(?<=\d)[ ,](?=\d)"""), "").replace(Regex("""(?<=\d) (?=\D)|(?<=\D) (?=\d)"""), "")
        val facts = ref.getJSONArray("facts").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val missed = facts.filterNot { f ->
            val groups = f.getJSONArray("all")
            (0 until groups.length()).all { g -> groups.getJSONArray(g).let { alts -> (0 until alts.length()).any { hay.contains(alts.getString(it)) } } }
        }.map { it.getString("id") }
        val recall = 1.0 - missed.size.toDouble() / facts.size
        // Format: the protocol must not leak into what the user reads.
        val leaks = listOf("NOTE", "NEXT", "<|", "[[").filter { prose.orEmpty().contains(it) || title.orEmpty().contains(it) }

        val report = JSONObject().put("meeting", name).put("model", spec.id).put("seconds", secs.toInt())
            .put("recall", recall).put("missed", JSONArray(missed)).put("notes", res.journal.size)
            .put("actions", res.actions.size).put("leaks", JSONArray(leaks)).put("title", title)
            .put("prose", prose).put("journal", notes)
        File(ctx.filesDir, "reader_gate").apply { mkdirs() }.resolve("$name-${spec.id}.json").writeText(report.toString(1))
        android.util.Log.i("ReaderGate", "$name ${spec.id}: recall %.2f (missed %s), %d notes, %d actions, title \"%s\", %ds, leaks %s"
            .format(recall, missed, res.journal.size, res.actions.size, title, secs.toInt(), leaks))

        val threshold = ref.optDouble("threshold", 0.75)
        assertTrue("$name: recall %.2f < %.2f, missed $missed".format(recall, threshold), recall >= threshold)
        assertTrue("$name: no title", !title.isNullOrBlank())
        assertTrue("$name: no summary prose", !prose.isNullOrBlank())
        assertTrue("$name: protocol leaked into the output: $leaks", leaks.isEmpty())
    }
}
