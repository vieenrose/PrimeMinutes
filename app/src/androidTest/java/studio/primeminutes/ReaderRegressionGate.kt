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
import studio.primeminutes.core.reader.AgentEvent
import studio.primeminutes.core.reader.MeetingLanguage
import studio.primeminutes.core.reader.ReaderLane
import studio.primeminutes.core.reader.ReaderLlm
import studio.primeminutes.core.reader.ReaderProtocol
import studio.primeminutes.core.text.ChineseScript
import studio.primeminutes.core.text.OpenCcConverter
import java.io.File

/**
 * Regression gate for the reader: run it BEFORE shipping new weights, a new model card or a prompt
 * change. Each reference meeting (androidTest/assets/reader_gate, a fixed ASR transcript) goes
 * through the app's own path — ReaderLane with the model's card, then prose and title — and the
 * output must state at least `threshold` of the meeting's key facts (decisions, amounts, dates).
 * The threshold sits below the measured `baseline` (2026-10-16, max_notes 12: 0.75–0.92 for E2B and
 * E4B; 0.67–0.75 with the former 6; the English meeting: E2B 0.79 on 2026-10-17). The reading is deterministic (same output run after run), so
 * any change in recall comes from the change under test.
 * The facts are written in Traditional Chinese (English for an English meeting, matched ignoring case);
 * the output is converted to Traditional before matching. A reference with a `lang` also checks that the
 * notes and the summary are written in that language ([MeetingLanguage]).
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
    /** An English meeting (AMI IS1009c, 30 min): the notes and summary must be in English too. */
    @Test fun english() = gate("ami_is1009c")
    /** Any meeting, no key facts (`-e utts <path of a gate-format JSON on the device>`): timings, the
     *  prose summary's length and how far into the meeting it cites, peak memory. */
    @Test fun file() = gate("file")

    private fun gate(name: String) = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        val args = InstrumentationRegistry.getArguments()
        val spec = LlmRegistry.byId(args.getString("llm") ?: LlmRegistry.DEFAULT_ID)
        // Experiments without a new card: -e max_notes N, -e window_tokens N (the report records them).
        val card = spec.card.let { c ->
            c.copy(reader = args.getString("max_notes")?.toIntOrNull()?.let { c.reader.copy(maxNotes = it) } ?: c.reader,
                budget = args.getString("window_tokens")?.toIntOrNull()?.let { c.budget.copy(windowTokens = it) } ?: c.budget)
        }.let { c ->
            // -e prose_tokens N: the prose summary's budget (all languages).
            args.getString("prose_tokens")?.toIntOrNull()?.let { n ->
                c.copy(prose = c.prose.copy(maxTokens = n), languages = c.languages.mapValues { (_, l) -> l.copy(prose = l.prose?.copy(maxTokens = n)) })
            } ?: c
        }.let { c ->
            // -e segment_above N: the journal size over which the prose summary is written in parts (all languages).
            args.getString("segment_above")?.toIntOrNull()?.let { n ->
                c.copy(prose = c.prose.copy(segmentAbove = n), languages = c.languages.mapValues { (_, l) -> l.copy(prose = l.prose?.copy(segmentAbove = n)) })
            } ?: c
        }
        // -e compare_single 1: the summary also written as one reply (segmenting off), timed (prose_single_*).
        val single = if (args.getString("compare_single") == "1") card.copy(prose = card.prose.copy(segmentTokens = 0),
            languages = card.languages.mapValues { (_, l) -> l.copy(prose = l.prose?.copy(segmentTokens = 0)) }) else null
        var proseParts = 0; var singleSecs = 0.0; var singleProse: String? = null
        val mm = ModelManager(ctx)
        assumeTrue("model ${spec.id} not installed", mm.llmReady(spec))
        val utFile = args.getString("utts")
        val ref = if (name == "file") JSONObject(File(requireNotNull(utFile) { "-e utts <path>" }).readText()).apply { if (!has("facts")) put("facts", JSONArray()) }
            else JSONObject(ins.context.assets.open("reader_gate/$name.json").bufferedReader().readText())
        val label = if (name == "file") ref.optString("name", "file") else name
        val utts = ref.getJSONArray("utterances").let { a -> List(a.length()) { i -> a.getJSONObject(i) } }.map {
            TranscriptEvent.Utterance(it.getInt("index"), it.getString("text"), it.getDouble("start"), it.getDouble("end"),
                if (it.isNull("speaker")) null else it.getInt("speaker"))
        }

        val dir = mm.llmDir(spec)
        val engine = MfaEngine.load(File(dir, spec.mainFile).parentFile!!.path, ctx = spec.maxCtx, threads = 4,
            weightCache = File(dir, ModelManager.WEIGHT_CACHE).path, stopIds = spec.template.stopIds)
        val tok = SpTokenizer.load(File(dir, spec.tokenizerFile).path)
        val drops = HashMap<String, Int>()
        var proseSecs = 0.0
        val t0 = System.nanoTime()
        val probe = Probe(MfaSession(engine, tok, spec.sampler.topK, spec.sampler.topP, template = spec.template), engine)
        val (res, prose, title) = try {
            ReaderLane(probe, File(dir, spec.systemPromptFile).readText(), card) { ev ->
                if (ev is AgentEvent.NoteDropped) synchronized(drops) { drops.merge(ev.reason.name, 1, Int::plus) }
            }.use { lane ->
                lane.start()
                val r = lane.finish(utts)
                val p0 = System.nanoTime()
                val p = runCatching { lane.prose(r.journal) }.getOrNull()
                proseSecs = (System.nanoTime() - p0) / 1e9
                proseParts = lane.lastProseParts
                Triple(r, p, runCatching { lane.title(r.journal) }.getOrNull())
            }.also { (r, _, _) ->
                if (single != null) ReaderLane(probe, File(dir, spec.systemPromptFile).readText(), single) {}.use { lane ->
                    val s0 = System.nanoTime()
                    singleProse = runCatching { lane.prose(r.journal, MeetingLanguage.detect(r.journal.map { it.text })) }.getOrNull()
                    singleSecs = (System.nanoTime() - s0) / 1e9
                }
            }
        } finally { engine.close(); tok.close() }
        val secs = (System.nanoTime() - t0) / 1e9

        val notes = res.journal.joinToString("\n") { ReaderProtocol.render(it) }
        val zhTw = OpenCcConverter.get(ctx, ChineseScript.TRADITIONAL)
        // Digits grouped or spaced either way ("110,000", "11 萬") match the plain spelling.
        val hay = zhTw.convert(listOf(notes, res.minutes, prose.orEmpty(), title.orEmpty()).joinToString("\n")).lowercase()
            .replace(Regex("""(?<=\d)[ ,](?=\d)"""), "").replace(Regex("""(?<=\d) (?=\D)|(?<=\D) (?=\d)"""), "")
        val facts = ref.getJSONArray("facts").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val missed = facts.filterNot { f ->
            val groups = f.getJSONArray("all")
            (0 until groups.length()).all { g -> groups.getJSONArray(g).let { alts -> (0 until alts.length()).any { hay.contains(alts.getString(it).lowercase()) } } }
        }.map { it.getString("id") }
        val recall = if (facts.isEmpty()) Double.NaN else 1.0 - missed.size.toDouble() / facts.size
        // How far into the meeting the summary goes: its last cited time over the last note's.
        fun secsOf(ts: String) = ts.split(":").fold(0) { a, x -> a * 60 + (x.toIntOrNull() ?: 0) }
        val cited = Regex("""\[(\d+:\d{2}(?::\d{2})?)\]""").findAll(prose.orEmpty()).map { secsOf(it.groupValues[1]) }.maxOrNull() ?: 0
        val lastNote = res.journal.maxOfOrNull { secsOf(it.ts) } ?: 0
        val vmHwmKb = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM") }?.filter(Char::isDigit)?.toLongOrNull() ?: 0L
        // Format: the protocol must not leak into what the user reads.
        // Language: the notes and the summary follow the meeting's.
        val lang = ref.optString("lang").ifEmpty { null }
        val notesLang = MeetingLanguage.detect(res.journal.map { it.text })
        val proseLang = MeetingLanguage.detect(listOf(prose.orEmpty()))
        val leaks = listOf("NOTE", "NEXT", "<|", "[[").filter { prose.orEmpty().contains(it) || title.orEmpty().contains(it) }

        val report = JSONObject().put("meeting", name).put("model", spec.id).put("seconds", secs.toInt())
            .put("recall", if (recall.isNaN()) JSONObject.NULL else recall).put("missed", JSONArray(missed)).put("notes", res.journal.size)
            .put("actions", res.actions.size).put("leaks", JSONArray(leaks)).put("title", title)
            .put("max_notes", card.reader.maxNotes).put("window_tokens", card.budget.windowTokens).put("dropped", JSONObject(drops as Map<*, *>))
            .put("prose_tokens", card.prose.maxTokens).put("prose_seconds", proseSecs.toInt()).put("prose_parts", proseParts)
            .put("prose_single_seconds", if (single != null) singleSecs.toInt() else JSONObject.NULL).put("prose_single", singleProse ?: JSONObject.NULL).put("prose_chars", prose.orEmpty().length)
            .put("prose_cited_s", cited).put("last_note_s", lastNote).put("vm_hwm_mb", vmHwmKb / 1024)
            .put("turns", probe.turns).put("prose", prose).put("journal", notes).put("lang", lang).put("notes_lang", notesLang).put("prose_lang", proseLang)
        File(ctx.filesDir, "reader_gate").apply { mkdirs() }.resolve("$label-${spec.id}-n${card.reader.maxNotes}-w${card.budget.windowTokens}.json").writeText(report.toString(1))
        android.util.Log.i("ReaderGate", "$label ${spec.id} notes≤${card.reader.maxNotes}/win${card.budget.windowTokens}: recall %.2f (missed %s), %d notes, dropped %s, %d actions, title \"%s\", %ds, leaks %s, prose %ds (%d part(s); one reply %ds) %d chars cites %d/%d s, VmHWM %d MB"
            .format(recall, missed, res.journal.size, drops, res.actions.size, title, secs.toInt(), leaks, proseSecs.toInt(), proseParts, singleSecs.toInt(), prose.orEmpty().length, cited, lastNote, vmHwmKb / 1024))

        for (i in 0 until probe.turns.length()) android.util.Log.i("ReaderGate", "TURN $label ${spec.id} ${probe.turns.getJSONObject(i)}")

        val threshold = ref.optDouble("threshold", 0.75)
        if (facts.isNotEmpty()) assertTrue("$name: recall %.2f < %.2f, missed $missed".format(recall, threshold), recall >= threshold)
        assertTrue("$name: no title", !title.isNullOrBlank())
        assertTrue("$name: no summary prose", !prose.isNullOrBlank())
        assertTrue("$name: protocol leaked into the output: $leaks", leaks.isEmpty())
        if (lang != null) assertTrue("$name: notes in $notesLang, summary in $proseLang, meeting in $lang", notesLang == lang && proseLang == lang)
    }
}

/**
 * The reader's session with each decode turn measured (reading windows, then prose and title): the
 * prompt length it decodes after, the prefill since the previous turn (tokens, seconds: the window's
 * transcript) and the decode itself, from the engine's own counters.
 */
private class Probe(private val s: MfaSession, private val e: MfaEngine) : ReaderLlm by s {
    val turns = JSONArray()
    private var preTok = 0; private var preSec = 0.0

    override fun append(tokens: IntArray): Int = s.append(tokens).also { r ->
        if (r >= 0) e.lastStats?.let { preTok += it.prefilled; preSec += it.prefillSec }
    }

    override fun reset() { s.reset() }

    override fun generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Unit): String {
        val prompt = s.seqLength()
        val out = s.generateContinue(maxTokens, stop, temp, onToken)
        val st = e.lastStats
        turns.put(JSONObject().put("prompt", prompt).put("prefill_tok", preTok + (st?.prefilled ?: 0))
            .put("prefill_s", "%.2f".format(java.util.Locale.ROOT, preSec + (st?.prefillSec ?: 0.0)).toDouble())
            .put("gen", st?.generated ?: 0).put("decode_s", "%.2f".format(java.util.Locale.ROOT, st?.decodeSec ?: 0.0).toDouble()))
        preTok = 0; preSec = 0.0
        return out
    }
}
