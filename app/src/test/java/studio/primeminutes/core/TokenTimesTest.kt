package studio.primeminutes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import studio.primeminutes.core.asr.LongUtteranceSplitter
import studio.primeminutes.core.asr.NemoStreamEngine
import studio.primeminutes.core.asr.TokenTimes
import studio.primeminutes.core.events.TranscriptEvent.Utterance

/** Per-token times from the engine's per-codepoint times (nemo_jni.cpp 5th field) to stored lines. */
class TokenTimesTest {

    @Test fun alignsThroughCleanup() {
        // The engine's raw text has spaces between Han characters and a doubled character that cleanup drops.
        val raw = "你 好 好 ， 世界 OK"
        val starts = raw.indices.map { it * 0.1 }       // one codepoint per char here
        val clean = "你好，世界 OK"
        val t = TokenTimes.fromCodepoints(raw, starts, clean)!!
        assertEquals(listOf("你", "好，", "世", "界 ", "OK"), TokenTimes.tokenize(clean))
        assertEquals(listOf(0.0, 0.2, 0.8, 0.9, 1.1), t.map { Math.round(it * 10) / 10.0 })
    }

    @Test fun refusesTextThatDoesNotMatch() {
        assertNull(TokenTimes.fromCodepoints("abc", listOf(0.0, 0.1, 0.2), "完全不同的字"))
        assertNull(TokenTimes.fromCodepoints("abc", listOf(0.0), "abc"))        // count mismatch
    }

    @Test fun parseSegmentsReadsTheOptionalTimesField() {
        val rec = "1\u001f12.000\u001f14.000\u001f大家 覺得 呢\u001f0,100,200,300,400,600,700\u001e" +
            "0\u001f14.000\u001f15.000\u001fno times here\u001e"
        val u = NemoStreamEngine.parseSegments(rec)
        assertEquals("大家覺得呢", u[0].text)
        assertEquals(listOf(0.0, 0.1, 0.3, 0.4, 0.7), u[0].tokenTimes)   // the spaces are skipped
        assertNull(u[1].tokenTimes)
    }

    @Test fun longLinesKeepTheirTokenTimesWhenCut() {
        val s1 = "第一句話說得很長很長" .repeat(10) + "。"      // 101 chars
        val s2 = "第二句也是很長的一段話" .repeat(9) + "。"
        val text = s1 + s2
        val toks = TokenTimes.tokenize(text)
        // 0.2 s per token, starting 5 s into the line
        val u = Utterance(0, text, 100.0, 100.0 + 5 + toks.size * 0.2, 0, tokenTimes = toks.indices.map { 5 + it * 0.2 })
        val out = LongUtteranceSplitter.split(listOf(u))
        assertEquals(2, out.size)
        assertEquals(s1, out[0].text); assertEquals(s2, out[1].text)
        val firstOfSecond = TokenTimes.tokenize(s1).size
        assertEquals(100.0 + 5, out[0].startSec, 1e-9)
        assertEquals(100.0 + 5 + firstOfSecond * 0.2, out[1].startSec, 1e-6)
        assertEquals(out[1].startSec, out[0].endSec, 1e-9)
        assertEquals(TokenTimes.tokenize(s2).size, out[1].tokenTimes!!.size)
        assertEquals(0.0, out[1].tokenTimes!!.first(), 1e-9)
    }
}
