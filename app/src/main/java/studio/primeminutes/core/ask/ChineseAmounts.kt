package studio.primeminutes.core.ask

/**
 * Chinese amounts of a thousand or more, valued: [toDigits] writes them in digits ("八萬二" → "82,000"),
 * [annotate] adds the value after them. Used on the excerpts of a non-Chinese Ask: the small reader
 * translated 八萬 as "eight thousand" and no prompt wording fixed it; with the value written in the
 * excerpt (annotated, it still wrote "twenty million (200,000)"), digits are simply copied.
 * Spoken shortcuts are read as people say them: 八萬二 = 82,000, 四千五 = 4,500, 一萬零五 = 10,005.
 * A run must start with a digit (千萬不要 "by all means", 萬一 "in case" are words, not amounts).
 */
object ChineseAmounts {
    private const val DIGITS = "零〇一二兩两三四五六七八九"
    private val RUN = Regex("[0-9]+(?:\\.[0-9]+)?[千萬万億亿]+|[一二兩两三四五六七八九十][零〇一二兩两三四五六七八九十百千萬万億亿]*")

    fun annotate(text: String): String = RUN.replace(text) { m ->
        val v = value(m.value) ?: return@replace m.value
        if (v < 1000) m.value else "${m.value} (${String.format(java.util.Locale.ROOT, "%,d", v)})"
    }

    /** Same runs, replaced by their value ("八萬二" → "82,000"): what the reader gets outside Chinese. */
    fun toDigits(text: String): String = RUN.replace(text) { m ->
        val v = value(m.value) ?: return@replace m.value
        if (v < 1000) m.value else String.format(java.util.Locale.ROOT, "%,d", v)
    }

    private fun digit(c: Char): Int? = when (c) {
        '零', '〇' -> 0; '一' -> 1; '二', '兩', '两' -> 2; '三' -> 3; '四' -> 4; '五' -> 5
        '六' -> 6; '七' -> 7; '八' -> 8; '九' -> 9; else -> null
    }

    private fun unit(c: Char): Long? = when (c) {
        '十' -> 10L; '百' -> 100L; '千' -> 1000L; '萬', '万' -> 10_000L; '億', '亿' -> 100_000_000L; else -> null
    }

    /** The run's value, or null when it is not a well-formed number. */
    fun value(s: String): Long? {
        if (s.first().isDigit()) {
            val n = s.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
            var mult = 1.0
            for (c in s.drop(s.indexOfFirst { !it.isDigit() && it != '.' })) mult *= unit(c)?.toDouble() ?: return null
            return Math.round(n * mult)
        }
        var total = 0L      // finished 億/萬 groups
        var section = 0L    // below 萬 within the current group
        var number = -1L    // pending digit, -1 = none
        var lastUnit = 0L
        for (c in s) {
            val d = digit(c)
            if (d != null) {
                if (number > 0 && d != 0) return null   // 二三: not a number
                if (d != 0) number = d.toLong()
                continue
            }
            val u = unit(c) ?: return null
            if (u >= 10_000L) {
                // 萬 scales what is below it; 億 scales everything so far (一億二千萬 = 1.2 億).
                val g = (if (u > 10_000L) total else 0L) + section + maxOf(number, 0L)
                if (g == 0L) return null
                total = (if (u > 10_000L) 0L else total) + g * u; section = 0
            } else {
                section += (if (number > 0) number else if (u == 10L) 1L else return null) * u
            }
            number = -1; lastUnit = u
        }
        if (number > 0) {
            // A bare digit right after a unit is the next unit down (八萬二, 四千五, 二十五); after 零 it is ones.
            val afterUnit = s.length >= 2 && unit(s[s.length - 2]) != null
            section += if (afterUnit) number * maxOf(lastUnit / 10, 1L) else number
        }
        return total + section
    }
}
