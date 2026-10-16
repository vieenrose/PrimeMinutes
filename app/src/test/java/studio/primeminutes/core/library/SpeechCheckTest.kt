package studio.primeminutes.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A silent or near-silent recording is not "Done": no lines, or only fillers and noise tags. */
class SpeechCheckTest {
    @Test fun noLinesIsNoSpeech() = assertFalse(SpeechCheck.hasSpeech(emptyList()))
    @Test fun fillersAndTagsAreNoSpeech() = assertFalse(SpeechCheck.hasSpeech(listOf("嗯", "[noise]", "  ", "啊。", "[Music]")))
    @Test fun oneRealLineIsSpeech() = assertTrue(SpeechCheck.hasSpeech(listOf("嗯", "我們下週一開會")))
    @Test fun englishAndDigitsAreSpeech() {
        assertTrue(SpeechCheck.hasSpeech(listOf("ok")))
        assertTrue(SpeechCheck.hasSpeech(listOf("2026")))
    }

    /** A re-run keeps a real title; the no-speech one a silent run gave the entry is dropped, so the
     *  new run's generated title replaces it (iOS RerunTitle.kept). */
    @Test fun aRerunKeepsOnlyARealTitle() {
        assertEquals("Budget review", SpeechCheck.keptTitle(" Budget review "))
        assertEquals("週會", SpeechCheck.keptTitle("週會"))
        assertNull(SpeechCheck.keptTitle(null))
        assertNull(SpeechCheck.keptTitle("  "))
        for (t in listOf("No speech detected", "未偵測到語音", "未检测到语音", " No speech detected ")) assertNull(t, SpeechCheck.keptTitle(t))
    }
}
