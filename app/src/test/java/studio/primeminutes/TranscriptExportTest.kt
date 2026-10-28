package studio.primeminutes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.primeminutes.core.events.TranscriptEvent
import studio.primeminutes.core.export.TranscriptExport
import studio.primeminutes.core.reader.ReaderProtocol

/**
 * Pins the portable-export serialisers — SRT/VTT timestamp formatting (the part most likely to be
 * subtly wrong: comma vs dot millis, renumbering after a skipped blank cue, a degenerate
 * zero-length segment) plus plain-text / Markdown layout. Pure JVM; no device needed.
 */
class TranscriptExportTest {

    private fun u(i: Int, text: String, start: Double, end: Double, spk: Int? = null) =
        TranscriptEvent.Utterance(index = i, text = text, startSec = start, endSec = end, speaker = spk)

    private val label: (Int) -> String = { sid -> "Speaker ${sid + 1}" }

    private val sample = listOf(
        u(0, "Hello there", 1.0, 2.5, 0),
        u(1, "   ", 2.5, 3.0, 1),            // blank → dropped from subtitles, not numbered
        u(2, "我們開始吧", 65.2, 70.0, 1),
    )

    @Test fun srtFormatsCommaMillisRenumbersAfterBlank() {
        val srt = TranscriptExport.srt(sample, label)
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:02,500\nSpeaker 1: Hello there\n\n"))
        assertTrue("blank skipped, next cue numbered 2 at 1:05.200",
            srt.contains("2\n00:01:05,200 --> 00:01:10,000\nSpeaker 2: 我們開始吧"))
        assertFalse("no empty cue emitted", srt.contains("Speaker 2: \n"))
    }

    @Test fun vttHasHeaderAndDotMillis() {
        val vtt = TranscriptExport.vtt(sample, label)
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:02.500"))
    }

    @Test fun plainTextIncludesTitleSummaryAndClock() {
        val txt = TranscriptExport.plainText(sample, label, title = "My Meeting", summary = "- a point")
        assertTrue(txt.startsWith("My Meeting\n\n- a point\n\n"))
        assertTrue(txt.contains("[00:01] Speaker 1: Hello there"))
        assertTrue(txt.contains("[01:05] Speaker 2: 我們開始吧"))
    }

    @Test fun markdownHasHeadingsAndBoldSpeakers() {
        val md = TranscriptExport.markdown(sample, label, "Title", "body", "Summary", "Transcript")
        assertTrue(md.startsWith("# Title\n\n## Summary\n\nbody\n\n## Transcript\n\n"))
        assertTrue(md.contains("- `00:01` **Speaker 1** Hello there"))
    }

    @Test fun documentFormatsCarryActionItems() {
        // Action items used to reach no export at all — only the .m4a archive held them.
        val txt = TranscriptExport.plainText(sample, label, "T", "sum", "- Alice to follow up", "Action items")
        assertTrue(txt.contains("Action items\n- Alice to follow up"))
        val md = TranscriptExport.markdown(
            sample, label, "T", "sum", "Summary", "Transcript", "- Alice to follow up", "Action items",
        )
        assertTrue(md.contains("## Action items\n\n- Alice to follow up"))
        // Section order is Summary → Actions → Transcript.
        assertTrue(md.indexOf("## Summary") < md.indexOf("## Action items"))
        assertTrue(md.indexOf("## Action items") < md.indexOf("## Transcript"))
    }

    @Test fun theExtractorsNothingFoundMarkerIsNotRenderedAsAnActionItem() {
        // ActionItemExtractor writes "-" when it finds nothing; that must not become a section.
        val txt = TranscriptExport.plainText(sample, label, "T", "sum", "-", "Action items")
        assertFalse(txt.contains("Action items"))
        val md = TranscriptExport.markdown(sample, label, "T", "sum", "Summary", "Transcript", "-", "Action items")
        assertFalse(md.contains("## Action items"))
        // Absent (null) action items behave the same way.
        assertFalse(TranscriptExport.markdown(sample, label, "T", "sum", "Summary", "Transcript").contains("Action items"))
    }

    @Test fun degenerateEndGetsOneSecondCueAndNoSpeakerPrefixWhenNull() {
        val srt = TranscriptExport.srt(listOf(u(0, "x", 10.0, 10.0, null)), label)
        assertTrue(srt.contains("00:00:10,000 --> 00:00:11,000"))
        assertFalse("null speaker → no label prefix", srt.contains(": x"))
    }

    @Test fun emptyTranscriptIsHandled() {
        assertTrue(TranscriptExport.srt(emptyList(), label).isEmpty())
        assertTrue(TranscriptExport.vtt(emptyList(), label).startsWith("WEBVTT"))
    }

    @Test fun lrcHasTitleCentisecondStampsSpeakerAndSkipsBlank() {
        val lrc = TranscriptExport.lrc(sample, label, title = "My Meeting")
        assertTrue("title header", lrc.startsWith("[ti:My Meeting]\n"))
        assertTrue("[mm:ss.xx] + speaker", lrc.contains("[00:01.00]Speaker 1: Hello there"))
        assertTrue("65.2s rolls to 1 min → [01:05.20]", lrc.contains("[01:05.20]Speaker 2: 我們開始吧"))
        // Exactly two timestamped lines — the blank utterance is dropped.
        val stamped = Regex("""^\[\d\d:\d\d\.\d\d]""")
        assertEquals(2, lrc.lines().count { stamped.containsMatchIn(it) })
    }

    @Test fun lrcOmitsTitleAndSpeakerWhenAbsent() {
        val lrc = TranscriptExport.lrc(listOf(u(0, "x", 5.0, 6.0, null)), label, title = null)
        assertFalse("no [ti:] header", lrc.contains("[ti:"))
        assertTrue(lrc.contains("[00:05.00]x"))
        assertFalse("null speaker → no label prefix", lrc.contains(": x"))
    }

    @Test fun longTurnIsSplitIntoShortTimedCues() {
        val text = "那個去查監控，你們物業那邊一直不給調監控，說那邊監控壞了怎麼的。因為一有問題你們物業就推卸責任，這不是個好理由對吧？然後我們卻把了這個這個錢誰出？"
        val u = studio.primeminutes.core.events.TranscriptEvent.Utterance(0, text, 11.0, 42.0, 0)
        val cues = studio.primeminutes.core.export.TranscriptExport.cues(listOf(u))
        org.junit.Assert.assertTrue(cues.size > 2)
        org.junit.Assert.assertTrue(cues.all { it.text.length <= 32 })
        org.junit.Assert.assertEquals(11.0, cues.first().startSec, 1e-9)
        org.junit.Assert.assertEquals(42.0, cues.last().endSec, 1e-9)
        org.junit.Assert.assertEquals(text, cues.joinToString("") { it.text })
    }

    @Test fun documentPathsFlattenEmbeddedNewlinesToOneLine() {
        // An edited/ASR utterance can embed a newline; txt/md must stay one line per utterance (LRC
        // already flattened) or the continuation reads as an untimestamped separate turn.
        val lines = listOf(u(0, "first line\nsecond line", 1.0, 2.0, 0))
        val txt = TranscriptExport.plainText(lines, label, "T", null)
        assertTrue("continuation stays on the timestamped line",
            txt.contains("[00:01] Speaker 1: first line second line\n"))
        assertEquals("still exactly one transcript line", 1,
            txt.lines().count { it.startsWith("[") })
        val md = TranscriptExport.markdown(lines, label, "T", null, "Summary", "Transcript")
        assertTrue(md.contains("- `00:01` **Speaker 1** first line second line\n"))
        assertEquals("still exactly one transcript bullet", 1,
            md.lines().count { it.startsWith("- `") })
        // Whitespace-only after flattening is still dropped.
        assertFalse(TranscriptExport.plainText(listOf(u(0, "\n  \n", 1.0, 2.0, 0)), label, "T", null).contains("[00:01]"))
    }

    @Test fun englishTurnIsCutBetweenWordsOnly() {
        val text = "Button so that you actually in the transcription box but so that you click on a button and then it opens whatever that speaker characterization is and the left click would give you the menu"
        val u = studio.primeminutes.core.events.TranscriptEvent.Utterance(0, text, 0.0, 9.6, 0)
        val cues = studio.primeminutes.core.export.TranscriptExport.cues(listOf(u))
        assertTrue(cues.size >= 3)
        assertTrue("≤64 Latin chars per cue", cues.all { it.text.length <= 64 })
        assertEquals("every piece is whole words", text, cues.joinToString(" ") { it.text })
        assertEquals(9.6, cues.last().endSec, 1e-9)
    }

    // --- design A: gutter + turn grouping (txt / md; the PDF draws the same TranscriptExport.turns) ---

    private val turnsSample = listOf(
        u(0, "Hello there", 1.0, 2.5, 0),
        u(1, "   ", 2.5, 3.0, 1),                       // blank → dropped, does not break the turn logic
        u(2, "我們開始吧", 65.2, 70.0, 1),
        u(3, "接著討論預算，包括搬家公司報價、紙箱發放、座位表、客戶通知信與停車位抽籤的所有細節安排與時程確認", 67.0, 80.0, 1),
        u(4, "OK, let's go with the middle quote.", 81.0, 83.0, 0),
    )

    @Test fun plainTextGroupsTurnsWithHangingIndentGolden() {
        val txt = TranscriptExport.plainText(turnsSample, label, null, null)
        assertEquals(
            "[00:01] Speaker 1: Hello there\n" +
                "\n" +
                "[01:05] Speaker 2: 我們開始吧\n" +
                "        接著討論預算，包括搬家公司報價、紙箱發放、座位表、客戶通知信與停車位抽籤的所有細節安排與時程確認\n" +
                "\n" +
                "[01:21] Speaker 1: OK, let's go with the middle quote.\n",
            txt,
        )
        assertEveryStampedLineParses(txt)
    }

    @Test fun markdownGroupsTurnsGolden() {
        val md = TranscriptExport.markdown(turnsSample, label, "T", null, "Summary", "Transcript")
        assertEquals(
            "# T\n\n## Transcript\n\n" +
                "- `00:01` **Speaker 1** Hello there\n" +
                "\n" +
                "- `01:05` **Speaker 2** 我們開始吧\n" +
                "  接著討論預算，包括搬家公司報價、紙箱發放、座位表、客戶通知信與停車位抽籤的所有細節安排與時程確認\n" +
                "\n" +
                "- `01:21` **Speaker 1** OK, let's go with the middle quote.\n",
            md,
        )
    }

    @Test fun hourClockIsZeroPaddedAcrossTheWholeDocument() {
        val long = listOf(u(0, "start", 5.0, 6.0, 0), u(1, "一小時後", 3723.0, 3725.0, 1))
        val txt = TranscriptExport.plainText(long, label, null, null)
        assertEquals("[00:00:05] Speaker 1: start\n\n[01:02:03] Speaker 2: 一小時後\n", txt)
        assertEveryStampedLineParses(txt)
        assertTrue(TranscriptExport.markdown(long, label, null, null, "S", "Tr").contains("- `01:02:03` **Speaker 2** 一小時後"))
        assertEquals(3723, ReaderProtocol.parseLine("[01:02:03] Speaker 2: 一小時後")!!.startS)
    }

    @Test fun unknownSpeakersNeverMergeAndKeepTheirClock() {
        val anon = listOf(u(0, "one", 1.0, 2.0), u(1, "two", 3.0, 4.0))
        assertEquals("[00:01] one\n\n[00:03] two\n", TranscriptExport.plainText(anon, label, null, null))
    }

    @Test fun bilingualSpeakerChangeAndSubtitlesUnchanged() {
        // SRT / VTT / LRC are standards: grouping must not touch them (one cue / line per utterance).
        val srt = TranscriptExport.srt(turnsSample, label)
        assertTrue(srt.contains("2\n00:01:05,200 --> 00:01:10,000\nSpeaker 2: 我們開始吧\n\n"))
        val lrc = TranscriptExport.lrc(turnsSample, label, null)
        assertEquals(4, lrc.lines().count { it.startsWith("[") })
        assertTrue(lrc.contains("[01:07.00]Speaker 2: 接著討論預算"))
        assertTrue(TranscriptExport.vtt(turnsSample, label).contains("00:01:21.000 --> 00:01:23.000\nSpeaker 1: OK, let's"))
    }

    private fun assertEveryStampedLineParses(txt: String) {
        for (l in txt.lines().filter { it.startsWith("[") }) {
            val p = ReaderProtocol.parseLine(l)
            assertTrue("parseLine: $l", p != null && p.text.isNotEmpty())
        }
    }
}
