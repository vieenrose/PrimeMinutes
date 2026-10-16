package studio.primeminutes.core.library

import studio.primeminutes.core.reader.ReaderProtocol

/**
 * Whether a finished transcript holds any speech. A silent (or near-silent) recording comes back
 * with no lines, or with only fillers and non-speech tags ("嗯", "[noise]") the recognizer guessed
 * from the background: such a meeting is not "Done" — it gets its own status and no AI notes.
 */
object SpeechCheck {
    /** At least one line with a letter or a digit once fillers and `[tags]` are cleaned away. */
    fun hasSpeech(texts: List<String>): Boolean =
        texts.any { t -> ReaderProtocol.cleanText(t).any { it.isLetterOrDigit() } }

    /** Titles a silent meeting was given before the status existed (English, Traditional and
     *  Simplified Chinese): such entries read back as no-speech. */
    val LEGACY_TITLES = setOf("No speech detected", "未偵測到語音", "未检测到语音")

    /**
     * The title a meeting carries into a new run of its audio (re-transcribe, a save over its entry),
     * where it outranks the reader's. Only a real one does — the reader's, the user's, a podcast's:
     * the "No speech detected" a silent run named the entry after (in any of the app's languages) is
     * dropped, so the new run's generated title replaces it once speech is found (iOS: RerunTitle.kept).
     */
    fun keptTitle(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() && it !in LEGACY_TITLES }
}
