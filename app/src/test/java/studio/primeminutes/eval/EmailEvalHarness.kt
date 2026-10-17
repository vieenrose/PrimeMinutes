package studio.primeminutes.eval

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import studio.primeminutes.core.llm.MfaEngine
import studio.primeminutes.core.llm.MfaSession
import studio.primeminutes.core.llm.SpTokenizer
import studio.primeminutes.core.models.LlmRegistry
import studio.primeminutes.core.models.ModelManager
import studio.primeminutes.core.reader.Note
import studio.primeminutes.core.reader.ReaderLane
import studio.primeminutes.core.reader.ReaderProtocol
import java.io.File
import java.io.PrintStream

/**
 * Design experiment (docs/design/speaker-contacts.md §7, tools/email-eval/): can the reader model
 * write the participants e-mail from a meeting's notes? Skipped unless PM_EMAIL_CFG is set
 * (tools/email-eval/run.sh).
 *
 * For each model × meeting × prompt variant: one fresh conversation, exactly like ReaderLane.title /
 * ReaderLane.prose (no system turn, the journal compacted to the card's reader.notes_chars and
 * further while the reply would not fit the context, the card's sampler temperature), on a journal
 * an earlier reader run wrote (the "notes" of a QualityEvalHarness reader output). The reply is
 * kept raw and cleaned the way the prose is (ReaderLane.cleanProse) or only timestamp-repaired.
 * Nothing here is used by the app.
 */
@RunWith(RobolectricTestRunner::class)
class EmailEvalHarness {
    private val cfgPath = System.getenv("PM_EMAIL_CFG")
    private val cfg by lazy { JSONObject(File(cfgPath).readText()) }
    private val out by lazy { File(cfg.getString("out")).apply { mkdirs() } }

    private val noteLine = Regex("""^#(\d+) \[(\d+:\d{2}(?::\d{2})?)] (?:\(([\w-]+)\) )?(.*)$""")

    private fun journal(text: String): List<Note> = text.lines().mapNotNull { l ->
        noteLine.find(l.trim())?.let { m -> Note(m.groupValues[1].toInt(), 0, m.groupValues[2], m.groupValues[3].ifEmpty { null }, m.groupValues[4]) }
    }

    @Test fun run() {
        assumeTrue("set PM_EMAIL_CFG", cfgPath != null)
        ShadowLog.stream = PrintStream(File(out, "log-email-${System.currentTimeMillis()}.txt").outputStream(), true)
        val models = File(cfg.getString("models_dir"))
        val threads = cfg.optInt("threads", 6)
        val llms = cfg.getJSONArray("llm").let { a -> List(a.length()) { a.getString(it) } }
        val variants = cfg.getJSONObject("variants")
        val items = cfg.getJSONArray("items").let { a -> List(a.length()) { a.getJSONObject(it) } }
        for (id in llms) {
            val spec = LlmRegistry.byId(id)
            val todo = items.flatMap { it -> variants.keys().asSequence().map { v -> it to v }.toList() }
                .filter { (it, v) -> !File(out, "${it.getString("name")}.$v.${spec.id}.json").exists() }
            if (todo.isEmpty()) continue
            val dir = File(models, spec.dirName)
            val engine = MfaEngine.load(File(dir, spec.mainFile).parentFile!!.path, ctx = spec.maxCtx, threads = threads,
                weightCache = File(dir, ModelManager.WEIGHT_CACHE).path, stopIds = spec.template.stopIds)
            val tok = SpTokenizer.load(File(dir, spec.tokenizerFile).path)
            try {
                for ((item, v) in todo) runOne(spec, engine, tok, item, v, variants.getJSONObject(v))
            } finally { engine.close(); tok.close() }
        }
    }

    private fun runOne(spec: studio.primeminutes.core.models.LlmSpec, engine: MfaEngine, tok: SpTokenizer, item: JSONObject, vName: String, variant: JSONObject) {
        val name = item.getString("name")
        val lang = item.getString("lang")
        val r = JSONObject(File(item.getString("reader")).readText())
        val notes = journal(r.getString("notes"))
        val card = spec.card.forLanguage(lang)
        val p = variant.optJSONObject(lang) ?: variant.getJSONObject("zh")
        val maxOut = p.getInt("max_tokens")
        val facts = item.getJSONObject("facts")
        fun fill(prompt: String, notesText: String) = prompt.replace("{notes}", notesText)
            .replace("{title}", r.optString("title")).replace("{date}", facts.getString("date"))
            .replace("{duration}", facts.getString(if (lang == "en") "duration_en" else "duration_zh"))
            .replace("{participants}", facts.getString("participants"))
        val session = MfaSession(engine, tok, spec.sampler.topK, spec.sampler.topP, template = spec.template)
        val t = spec.template
        // ReaderLane.fitting: the card's notes budget, cut by 400 characters while the reply would not fit.
        var chars = card.reader.notesChars
        var used: List<Note>
        var toks: IntArray
        while (true) {
            used = if (chars > 0) card.reader.compactNotes(notes, chars) else notes
            val text = used.joinToString("\n") { ReaderProtocol.render(it) }
            toks = session.tokenize(t.userFirst, true) + session.tokenize(fill(p.getString("prompt"), text), false) + session.tokenize(t.p2, true)
            if (chars <= 0 || toks.size + maxOut + 8 <= card.budget.ctxBudget || chars <= 800) break
            chars -= 400
        }
        session.reset()
        val t0 = System.nanoTime()
        check(session.append(toks) >= 0) { "prefill failed" }
        val t1 = System.nanoTime()
        val raw = session.generateContinue(maxOut, t.end, card.sampler.temp) {}
        val t2 = System.nanoTime()
        val body = raw.substringBefore(t.end)
        val genTokens = session.tokenize(body, false).size
        val known = notes.map { it.ts }.toSet()
        val cleaned = if (variant.optString("clean") == "prose") ReaderLane.cleanProse(raw, known, t.end)
            else ReaderProtocol.repairTimestamps(body, known).trim()
        File(out, "$name.$vName.${spec.id}.json").writeText(JSONObject()
            .put("meeting", name).put("variant", vName).put("model", spec.id).put("lang", lang)
            .put("notes_total", notes.size).put("notes_used", used.size).put("notes_chars", chars)
            .put("prompt_tokens", toks.size).put("gen_tokens", genTokens).put("max_tokens", maxOut).put("ended", raw.contains(t.end))
            .put("prefill_sec", (t1 - t0) / 1e9).put("decode_sec", (t2 - t1) / 1e9)
            .put("raw", raw).put("cleaned", cleaned ?: JSONObject.NULL)
            .put("notes_used_text", used.joinToString("\n") { ReaderProtocol.render(it) }).toString(1))
        println("email $name $vName ${spec.id}: ${toks.size} prompt tok, $genTokens gen tok, ${"%.1f".format((t2 - t0) / 1e9)} s")
    }
}
