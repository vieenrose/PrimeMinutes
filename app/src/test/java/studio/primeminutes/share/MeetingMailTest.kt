package studio.primeminutes.share

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.models.ModelCard
import studio.primeminutes.core.share.MeetingMail
import studio.primeminutes.core.share.MiniMustache
import java.time.OffsetDateTime

/**
 * The participants e-mail against the design's golden files (docs/design/speaker-contacts/samples,
 * produced by `render_samples.py` from real reader outputs). iOS runs the same fixtures.
 */
class MeetingMailTest {
    private fun res(n: String) = checkNotNull(javaClass.getResourceAsStream("/mail_golden/$n")) { n }.use { it.readBytes().toString(Charsets.UTF_8) }

    /** The fixture as the app would hand it over (render_samples.build). */
    private fun input(name: String): MeetingMail.Input {
        val fx = JSONObject(res("$name.json"))
        val lang = fx.getString("lang")
        val people = fx.getJSONObject("people")
        val names = people.keys().asSequence().associate { it.toInt() to people.getJSONObject(it).getString("name") }
        val utts = fx.getJSONArray("utterances")
        val known = (0 until utts.length()).map { utts.getJSONObject(it).getInt("speaker") }.toSet()
        val dur = (0 until utts.length()).maxOf { utts.getJSONObject(it).getDouble("end") }
        val sections = ModelCard.DEFAULT.forLanguage(lang).reader.sections
        return MeetingMail.Input(
            title = fx.getString("title"), summary = fx.optString("summary").ifBlank { null },
            sections = MeetingMail.parseMinutes(fx.getString("minutes_raw"), sections),
            names = names, participants = names.toSortedMap().values.toList(), unnamed = known.count { it !in names },
            start = OffsetDateTime.parse(fx.getString("started")).toZonedDateTime(), durationSec = dur,
        )
    }

    /** A golden .txt minus its "To:/Subject:" header (the mail app shows those). */
    private fun body(n: String): String = res(n).let { t -> if (t.startsWith("To:")) t.substringAfter("\n\n") else t }

    private fun plain(name: String, lang: String, opts: MeetingMail.Options, chat: Boolean = false): String {
        val tpl = MeetingMail.Templates.SHIPPED
        val (m, _) = MeetingMail.model(input(name), lang, opts, tpl, budgetTemplate = if (chat) tpl.chat else tpl.txt)
        return MeetingMail.plain(m, if (chat) tpl.chat else tpl.txt)
    }

    private fun html(name: String, lang: String, opts: MeetingMail.Options): String {
        val (m, _) = MeetingMail.model(input(name), lang, opts, htmlHeadings = true)
        return MeetingMail.html(m, MeetingMail.Templates.SHIPPED.html)
    }

    @Test fun zhMailMatchesGolden() {
        assertEquals(body("move.zh-TW.txt"), plain("move", "zh-Hant", MeetingMail.Options()))
        assertEquals(res("move.zh-TW.html"), html("move", "zh-Hant", MeetingMail.Options()))
    }

    @Test fun decisionsAndActionsOnly() {
        val o = MeetingMail.Options(summary = false, only = setOf("DECISION", "ACTION"))
        assertEquals(body("move.zh-TW.actions-only.txt"), plain("move", "zh-Hant", o))
        assertEquals(res("move.zh-TW.actions-only.html"), html("move", "zh-Hant", o))
    }

    @Test fun enMailWithAttachment() {
        val o = MeetingMail.Options(attachments = listOf("Remote-control-concept.pdf"))
        assertEquals(body("is1009c.en.txt"), plain("is1009c", "en", o))
        assertEquals(res("is1009c.en.html"), html("is1009c", "en", o))
    }

    @Test fun truncatedForMailto() {
        val o = MeetingMail.Options(budget = 2000)
        val (_, cut) = MeetingMail.model(input("is1009c"), "en", o)
        assertTrue(cut)
        assertEquals(body("is1009c.en.mailto.txt"), plain("is1009c", "en", o))
    }

    /** An import whose file has no creation date: no time line, the rest as before. */
    @Test fun unknownStartLeavesOutTheTime() {
        val tpl = MeetingMail.Templates.SHIPPED
        val withTime = plain("move", "zh-Hant", MeetingMail.Options())
        val without = MeetingMail.plain(MeetingMail.model(input("move").copy(timeKnown = false), "zh-Hant", MeetingMail.Options(), tpl).first, tpl.txt)
        val timeLine = withTime.lines().single { it.contains("時間") && it.contains("–") }
        assertFalse(without.contains(timeLine))
        assertEquals(withTime.lines().filterNot { it == timeLine }, without.lines())
    }

    @Test fun chatVariant() {
        assertEquals(body("move.zh-TW.chat.txt"), plain("move", "zh-Hant", MeetingMail.Options(summary = false), chat = true))
    }

    @Test fun buildGivesSubjectAndAllBodies() {
        val m = MeetingMail.build(input("move"), "zh-Hant", MeetingMail.Options())
        assertEquals("會議紀要｜新辦公室搬遷規劃與預算決議會議（10/14）", m.subject)
        assertFalse(m.truncated)
        assertTrue(m.html.contains("<html lang=\"zh-Hant-TW\">"))
        assertTrue(m.chat.startsWith("「新辦公室搬遷規劃與預算決議會議」（10/14）"))
        // Simplified frame text (the content itself is never converted here).
        val s = MeetingMail.build(input("move"), "zh-Hans", MeetingMail.Options())
        assertTrue(s.subject.startsWith("会议纪要｜"))
        assertTrue(s.plain.contains("注意：本纪要由 AI 根据录音自动生成"))
    }

    @Test fun refsAndMustache() {
        assertEquals("林雅婷需請法務", MeetingMail.resolveRefs("S2 需請法務", mapOf(1 to "林雅婷")))
        assertEquals("Ask Mark now, S9 too", MeetingMail.resolveRefs("Ask S1 now, S9 too", mapOf(0 to "Mark")))
        assertEquals("a&lt;b|x", MiniMustache.render("{{v}}|{{#l}}{{.}}{{/l}}{{^n}}{{/n}}", mapOf("v" to "a<b", "l" to listOf("x")), escape = true))
        assertEquals("<b>", MiniMustache.render("{{{v}}}", mapOf("v" to "<b>"), escape = true))
        assertEquals("A\nB\n", MiniMustache.render("A\n{{#x}}\nB\n{{/x}}\n", mapOf("x" to true), escape = false))
    }

    @Test fun mailLanguage() {
        assertEquals("en", MeetingMail.defaultLang("en", java.util.Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-Hans", MeetingMail.defaultLang("zh", java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant", MeetingMail.defaultLang("zh", java.util.Locale.ENGLISH))
        assertEquals("zh-Hant", MeetingMail.defaultLang(null, java.util.Locale.TRADITIONAL_CHINESE))
    }
}
