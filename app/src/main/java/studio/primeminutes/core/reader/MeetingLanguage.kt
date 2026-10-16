package studio.primeminutes.core.reader

/**
 * The language a meeting is held in, from its transcript's script: Han/kana characters against Latin
 * words. A word weighs one character, so a Chinese meeting sprinkled with English terms stays `zh`;
 * only a transcript where Latin words outnumber CJK characters is `en`. Model-independent: the model
 * card says what (if anything) changes for a language ([studio.primeminutes.core.models.ModelCard.forLanguage]).
 */
class MeetingLanguage {
    private var cjk = 0
    private var words = 0

    /** Count one more piece of transcript. */
    fun add(text: String) {
        var inWord = 0
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (isCjk(c)) cjk++
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code) inWord++ else { if (inWord >= 2) words++; inWord = 0 }
            i += Character.charCount(c)
        }
        if (inWord >= 2) words++
    }

    /** `zh`, `en`, or null while there is too little text to tell ([MIN_UNITS]). */
    val code: String?
        get() = when {
            cjk + words < MIN_UNITS -> null
            words > cjk -> "en"
            else -> "zh"
        }

    companion object {
        const val MIN_UNITS = 20

        /** Han ideographs and kana (explicit ranges, the same on iOS). */
        fun isCjk(c: Int) = c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF || c in 0xF900..0xFAFF || c in 0x20000..0x2FFFF ||
            c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF9D

        fun detect(texts: Iterable<String>): String? = MeetingLanguage().apply { texts.forEach(::add) }.code
    }
}
