package studio.primeminutes.core.library

import org.junit.Assert.assertFalse
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
}
