package studio.primeminutes.core.share

import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The participants e-mail (docs/design/speaker-contacts.md §5): a DETERMINISTIC template over what
 * the app already has — the prose summary the user saw (and could edit), the minutes grouped from
 * the typed notes (decisions, actions by owner, open points, …), the meeting facts — with the fixed
 * "machine-generated" note. No model writes anything at send time. Pure code (no Android), golden
 * tested against the design's reference renderer (`render_samples.py`); the iOS port is
 * `MeetingMail.swift` and reads the same resource files (`resources/mail/`).
 */
object MeetingMail {
    /** One minutes item: its text (speaker refs `S2` still in it) and its `[m:ss]`. */
    data class Item(val text: String, val ts: String?)

    /** One minutes section: the card's heading and tag for the meeting language, its items. */
    data class Section(val heading: String, val tag: String, val items: List<Item>)

    data class Input(
        val title: String,
        /** The prose summary as shown (null: none). */
        val summary: String?,
        val sections: List<Section>,
        /** Speaker id → the name the meeting shows (refs of other speakers stay as written). */
        val names: Map<Int, String>,
        /** The participants line, in speaker order (one per person). */
        val participants: List<String>,
        /** Speakers with no name. */
        val unnamed: Int,
        val start: ZonedDateTime,
        val durationSec: Double,
    )

    data class Options(
        val summary: Boolean = true,
        val minutes: Boolean = true,
        /** Only these section tags (the "Decisions and actions only" preset), or null for all. */
        val only: Set<String>? = null,
        val attachments: List<String> = emptyList(),
        /** Phase D (off): a model-written lead. Kept for the template's parity; the app passes null. */
        val lead: String? = null,
        /** Most characters of the plain body (§5.7), or null. */
        val budget: Int? = null,
    )

    data class Mail(val subject: String, val plain: String, val html: String, val chat: String, val truncated: Boolean)

    const val COMPOSER_BUDGET = 20_000
    const val CHAT_BUDGET = 2_000
    /** `mailto:` bodies (each CJK character percent-encodes to 9 bytes). */
    const val MAILTO_BUDGET_ZH = 1_500
    const val MAILTO_BUDGET_EN = 2_000

    val LANGS = listOf("zh-Hant", "zh-Hans", "en")

    /** The template files and fixed text, read once from the class path (`resources/mail/`). */
    class Templates(val txt: String, val html: String, val chat: String, val strings: JSONObject) {
        companion object {
            val SHIPPED: Templates by lazy {
                fun r(n: String) = checkNotNull(MeetingMail::class.java.getResourceAsStream("/mail/$n")) { "no mail/$n" }
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                Templates(r("meeting_mail.txt"), r("meeting_mail.html"), r("chat.txt"), JSONObject(r("strings.json")))
            }
        }
    }

    // --- minutes ------------------------------------------------------------------------------------

    private val ITEM = Regex("""^-\s*(.*?)\s*(?:\[(\d+:\d{2}(?::\d{2})?)\])?\s*$""")
    private val HEAD = Regex("""^【(.+)】$""")
    private val EMPTY = setOf("無", "无", "None", "-", "")

    /** The minutes text the reader assembles (`【heading】` then `- text [ts]`), as sections; empty ones dropped. */
    fun parseMinutes(raw: String, sections: List<Pair<String, String>>): List<Section> {
        val tags = sections.toMap()
        val out = ArrayList<Pair<String, MutableList<Item>>>()
        for (l in raw.lines()) {
            val line = l.trim()
            val h = HEAD.matchEntire(line)
            if (h != null) { out += h.groupValues[1] to ArrayList(); continue }
            if (line.startsWith("-") && out.isNotEmpty()) {
                val m = ITEM.matchEntire(line) ?: continue
                val text = m.groupValues[1]
                if (text in EMPTY) continue
                out.last().second += Item(text, m.groupValues[2].ifEmpty { null })
            }
        }
        return out.filter { it.second.isNotEmpty() }.map { (h, items) -> Section(h, tags[h] ?: "", items) }
    }

    /** The same sections built straight from the typed notes (the app's path): (tag, text, ts) each,
     *  already through the proposal guard. [heading] converts a card heading to the shown script. */
    fun sectionsOf(notes: List<Triple<String?, String, String>>, sections: List<Pair<String, String>>, heading: (String) -> String = { it }): List<Section> =
        sections.mapNotNull { (h, tag) ->
            notes.filter { it.first?.uppercase() == tag }.map { Item(it.second.trimEnd('。'), it.third) }
                .filter { it.text !in EMPTY }.takeIf { it.isNotEmpty() }?.let { Section(heading(h), tag, it) }
        }

    // --- speaker references ---------------------------------------------------------------------------

    private val REF = Regex("""(?<![A-Za-z0-9_])S(\d{1,2})(?![A-Za-z0-9_])""")
    private val HAN_SPACE = Regex("""(?<=[㐀-鿿]) \u0000""")
    private val SPACE_HAN = Regex("""\u0000 (?=[㐀-鿿])""")

    /** S2 → the speaker's name; no space between a Han name and Han text (`S2 需` → `林雅婷需`). */
    fun resolveRefs(text: String, names: Map<Int, String>): String {
        var t = REF.replace(text) { m -> names[m.groupValues[1].toInt() - 1]?.let { "\u0000$it\u0000" } ?: m.value }
        t = HAN_SPACE.replace(t, "\u0000")
        t = SPACE_HAN.replace(t, "\u0000")
        return t.replace("\u0000", "")
    }

    private val OWNER = Regex("""^S(\d{1,2})(?![A-Za-z0-9_])""")

    private fun ownerOf(text: String, names: Map<Int, String>): String? =
        OWNER.find(text)?.let { names[it.groupValues[1].toInt() - 1] }

    // --- the mail -------------------------------------------------------------------------------------

    private val TS = Regex("""\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)\]""")
    private val PRIORITY = mapOf("DECISION" to 1, "ACTION" to 1, "OPEN-ISSUE" to 2, "NUMBER" to 3, "PROPOSAL" to 4)

    private fun fill(t: String, vararg kv: Pair<String, Any>): String {
        var s = t
        for ((k, v) in kv) s = s.replace("{$k}", v.toString())
        return s
    }

    private fun htmlTs(t: String): String =
        TS.replace(MiniMustache.escapeHtml(t)) { m -> """<span style="color:#5A6982;font-size:13px;white-space:nowrap;">[${m.groupValues[1]}]</span>""" }

    private fun len(s: String) = s.codePointCount(0, s.length)

    private class Block(val kind: String, val tag: String, val heading: String, var items: List<Item> = emptyList(),
                        var paragraphs: List<String> = emptyList(), var dropped: Int = 0)

    /** The template context (what `render_samples.build` returns). */
    fun model(input: Input, lang: String, opts: Options, tpl: Templates = Templates.SHIPPED, htmlHeadings: Boolean = false, budgetTemplate: String = tpl.txt): Pair<Map<String, Any?>, Boolean> {
        val s = tpl.strings.getJSONObject(lang)
        fun str(k: String) = s.getString(k)
        val start = input.start
        val end = start.plusNanos((input.durationSec * 1e9).toLong())
        val wd = s.getJSONArray("weekdays").getString(start.dayOfWeek.value - 1)
        val mon = s.optJSONArray("months")?.getString(start.monthValue - 1) ?: ""
        val dateLong = fill(str("date_long"), "y" to start.year, "mo" to start.monthValue, "d" to start.dayOfMonth, "wd" to wd, "mon" to mon)
        val dateShort = fill(str("date_short"), "mo" to start.monthValue, "d" to start.dayOfMonth, "mon" to mon)
        fun sub(t: String) = fill(t, "title" to input.title, "date_long" to dateLong, "date_short" to dateShort)
        fun plainHeading(h: String) = if (htmlHeadings) h else fill(str("plain_heading"), "h" to h, "H" to h.uppercase(Locale.ROOT))
        // Python's round(): half to even.
        val minutes = Math.rint(input.durationSec / 60).toInt()
        val duration = if (minutes >= 60) fill(str("duration_hm"), "h" to minutes / 60, "m" to minutes % 60) else fill(str("duration_m"), "m" to minutes)
        val parts = input.participants + (if (input.unnamed > 0) listOf(fill(str("unnamed"), "n" to input.unnamed)) else emptyList())
        val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val facts = listOf(
            mapOf("label" to str("label_date"), "value" to dateLong),
            mapOf("label" to str("label_time"), "value" to "${start.format(hm)}–${end.format(hm)}"),
            mapOf("label" to str("label_duration"), "value" to duration),
            mapOf("label" to str("label_participants"), "value" to parts.joinToString(str("list_sep"))),
        )
        val names = input.names
        fun refs(t: String) = resolveRefs(t, names)
        val model = HashMap<String, Any?>()
        model["subject"] = sub(str("subject")); model["greeting"] = str("greeting"); model["intro"] = sub(str("intro"))
        model["chat_intro"] = sub(str("chat_intro"))
        model["facts_heading"] = plainHeading(str("facts_heading"))
        model["facts"] = facts; model["colon"] = str("colon")
        model["disclaimer"] = str("disclaimer"); model["footer"] = str("footer"); model["bullet"] = str("bullet")
        model["html_lang"] = when (lang) { "zh-Hant" -> "zh-Hant-TW"; "zh-Hans" -> "zh-Hans-CN"; else -> "en" }
        opts.lead?.let { l -> refs(l).let { model["lead"] = it; model["lead_html"] = htmlTs(it) } }
        val blocks = ArrayList<Block>()
        if (opts.summary && !input.summary.isNullOrBlank()) {
            blocks += Block("summary", "", str("summary_heading"),
                paragraphs = refs(input.summary).split("\n\n").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (opts.minutes) for (sec in input.sections) {
            if (opts.only != null && sec.tag !in opts.only) continue
            blocks += Block("section", sec.tag, sec.heading, items = sec.items)
        }
        if (opts.attachments.isNotEmpty()) model["attachments"] = fill(str("attachments"), "files" to opts.attachments.joinToString(str("list_sep")))

        fun groups(items: List<Map<String, Any?>>): List<Map<String, Any?>> {
            val none = str("owner_none")
            val order = ArrayList<String>()
            val byOwner = HashMap<String, MutableList<Map<String, Any?>>>()
            for (i in items) {
                val owner = i["owner"] as String?
                val o = owner ?: none
                if (o !in byOwner) { byOwner[o] = ArrayList(); order += o }
                var t = i["text"] as String
                if (owner != null && t.startsWith(owner)) t = t.substring(owner.length).trimStart(' ')
                byOwner[o]!! += mapOf("text" to t, "ts" to i["ts"])
            }
            val sorted = order.filter { it != none } + (if (none in byOwner) listOf(none) else emptyList())
            return sorted.map { mapOf("owner" to it, "items" to byOwner[it]) }
        }

        fun assemble() {
            model["summary"] = null
            val secs = ArrayList<Map<String, Any?>>()
            for (b in blocks) {
                if (b.kind == "summary") {
                    if (b.paragraphs.isNotEmpty()) model["summary"] = mapOf("heading" to plainHeading(b.heading),
                        "paragraphs" to b.paragraphs.map { mapOf("text" to it, "html" to htmlTs(it)) })
                    continue
                }
                if (b.items.isEmpty()) continue
                val items = b.items.map { i ->
                    mapOf("text" to refs(i.text), "ts" to i.ts, "owner" to if (b.tag == "ACTION") ownerOf(i.text, names) else null)
                }
                val g = if (items.any { it["owner"] != null }) groups(items) else listOf(mapOf("owner" to null, "items" to items))
                secs += mapOf("heading" to plainHeading(b.heading), "groups" to g,
                    "more" to if (b.dropped > 0) fill(str("more"), "n" to b.dropped) else null)
            }
            model["sections"] = secs
        }

        assemble()
        var truncated = false
        opts.budget?.let { budget ->
            // Least important first: discussion points, the prose, figures, then open issues (§5.7).
            val steps = listOf("PROPOSAL" to 5, "summary" to 1, "NUMBER" to 5, "PROPOSAL" to 0, "summary" to 0, "NUMBER" to 0, "OPEN-ISSUE" to 3)
            for ((key, keep) in steps) {
                if (len(MiniMustache.render(budgetTemplate, model, false)) <= budget) break
                truncated = true
                for (b in blocks) {
                    if (key == "summary" && b.kind == "summary") b.paragraphs = b.paragraphs.take(keep)
                    else if (b.tag == key && b.items.size > keep) { b.dropped += b.items.size - keep; b.items = b.items.take(keep) }
                }
                assemble()
            }
        }
        return model to truncated
    }

    private val BLANKS = Regex("""\n{3,}""")
    private val HTML_BLANKS = Regex("""\n\s*\n""")

    fun plain(model: Map<String, Any?>, template: String): String =
        BLANKS.replace(MiniMustache.render(template, model, false), "\n\n").trim() + "\n"

    fun html(model: Map<String, Any?>, template: String): String =
        HTML_BLANKS.replace(MiniMustache.render(template, model, true), "\n")

    /** Subject, plain body, HTML body and the chat variant for [lang] (`zh-Hant`, `zh-Hans`, `en`). */
    fun build(input: Input, lang: String, opts: Options, tpl: Templates = Templates.SHIPPED): Mail {
        val (m, cut) = model(input, lang, opts, tpl)
        val (hm, _) = model(input, lang, opts, tpl, htmlHeadings = true)
        val (cm, _) = model(input, lang, opts.copy(summary = false, attachments = emptyList(), budget = CHAT_BUDGET), tpl, budgetTemplate = tpl.chat)
        return Mail(m["subject"] as String, plain(m, tpl.txt), html(hm, tpl.html), plain(cm, tpl.chat), cut)
    }

    /** The mail language for a meeting in [meetingLang] (`zh` / `en`) and an interface in [ui]:
     *  English for an English meeting; for Chinese, the interface's script, else Traditional (§5.4). */
    fun defaultLang(meetingLang: String?, ui: Locale): String = when {
        meetingLang == "en" -> "en"
        ui.language == "zh" && (ui.script == "Hans" || ui.country in setOf("CN", "SG")) -> "zh-Hans"
        else -> "zh-Hant"
    }
}
