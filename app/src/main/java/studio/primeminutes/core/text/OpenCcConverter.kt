package studio.primeminutes.core.text

import android.content.Context
import java.io.InputStream

/** Which Han script to normalize Chinese text into. */
enum class ChineseScript { TRADITIONAL, SIMPLIFIED }

/**
 * Minimal on-device OpenCC converter, both directions, used to keep ALL output text (transcript,
 * summary, title, speaker names → and the lyrics built from them) in one consistent script:
 *  - [ChineseScript.TRADITIONAL] = Simplified→Traditional, Taiwan standard with phrases (`s2twp`):
 *    stage 1 maps S→T (STPhrases then STCharacters, plus STGuard exceptions); stage 2 applies the
 *    Taiwan localisation (TWVariants + TWVariantsPhrases + our curated TWLexicon) so vocabulary reads
 *    native — 資訊 not 信息, 影片 not 視頻, 軟體 not 軟件 — without OpenCC TWPhrases' over-localisation
 *    (文件→檔案, 權限→許可權, 優化→最佳化, 數據→資料, 項目→專案, 發布→釋出).
 *  - [ChineseScript.SIMPLIFIED]  = Traditional→Simplified (`t2s`): TSPhrases then TSCharacters.
 * Longest-match over the bundled OpenCC dictionaries (Apache-2.0, in assets/opencc/). One instance per
 * script, built once and cached, reused. Good enough for script consistency; not a full idiom engine.
 *
 * Span gating: the text is split on punctuation and whitespace, and a span is converted only if it holds
 * a character of the source script ([gate]: one the S→T character table changes for S→T, one the T→S
 * table changes for T→S). Text already in the target script passes through untouched, so phrase
 * entries can no longer rewrite it (說明了 → 說明瞭, 干涉 → 幹涉 on Traditional input).
 */
class OpenCcConverter private constructor(
    private val stages: List<Map<String, String>>,
    private val maxKey: Int,
    private val gate: Set<Int>,
) {
    fun convert(text: String): String {
        // Skip non-Chinese text. OpenCC maps shared Han characters to Traditional variants, which mangles
        // Japanese/Korean output (e.g. a model that summarized a JA/KO source in its own language — see the
        // cross-lingual target-language case). Latin text has no Han chars so it's untouched anyway; this
        // guards the CJK-but-not-Chinese case where conversion would corrupt the text.
        if (hasKanaOrHangul(text)) return text
        if (gate.isEmpty()) return convertSpan(text)
        val sb = StringBuilder(text.length)
        var start = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = Character.charCount(cp)
            if (isDelimiter(cp)) {
                appendSpan(sb, text, start, i)
                sb.appendCodePoint(cp)
                start = i + w
            }
            i += w
        }
        appendSpan(sb, text, start, text.length)
        return sb.toString()
    }

    private fun appendSpan(sb: StringBuilder, text: String, from: Int, to: Int) {
        if (from >= to) return
        val span = text.substring(from, to)
        var gated = false
        var i = 0
        while (i < span.length && !gated) {
            val cp = span.codePointAt(i)
            gated = cp in gate
            i += Character.charCount(cp)
        }
        sb.append(if (gated) convertSpan(span) else span)
    }

    private fun convertSpan(text: String): String {
        var out = text
        for (dict in stages) out = applyStage(out, dict)
        return out
    }

    private fun hasKanaOrHangul(s: String): Boolean = s.any { c ->
        c in '぀'..'ヿ' ||   // hiragana + katakana
        c in '가'..'힣' ||   // hangul syllables
        c in 'ᄀ'..'ᇿ'      // hangul jamo
    }

    private fun applyStage(text: String, dict: Map<String, String>): String {
        if (dict.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            var matched = false
            val maxLen = minOf(maxKey, n - i)
            for (len in maxLen downTo 1) {
                val rep = dict[text.substring(i, i + len)]
                if (rep != null) { sb.append(rep); i += len; matched = true; break }
            }
            if (!matched) { sb.append(text[i]); i++ }
        }
        return sb.toString()
    }

    companion object {
        @Volatile private var traditional: OpenCcConverter? = null
        @Volatile private var simplified: OpenCcConverter? = null
        @Volatile private var traditionalConservative: OpenCcConverter? = null
        @Volatile private var transcriptSimplified: OpenCcConverter? = null

        /**
         * Conservative Simplified→Traditional for TRANSCRIPTS (every ASR backend): `s2t` plus
         * the character-level TWVariants only — NO phrase-level Taiwan localisation.
         *
         * The split is phonetic vs semantic. A transcript records what was SAID, so conversion
         * may only re-spell the same word; [get] with [ChineseScript.TRADITIONAL] (`s2twp`) also
         * substitutes vocabulary (信息→資訊), which is a semantic edit and belongs to generated
         * text — summary, title, action items, speaker names. Measured on real 立法院 audio,
         * that phrase pass corrupted domain proper nouns (高端疫苗 → 高階疫苗, 程序委員會 →
         * 程式委員會) with every observed difference being a corruption; single-character
         * variants can't do that.
         */
        fun getTranscriptTraditional(context: Context): OpenCcConverter =
            traditionalConservative ?: synchronized(this) {
                traditionalConservative ?: buildTraditionalConservative(context.assets::open)
                    .also { traditionalConservative = it }
            }

        /**
         * Character-level Traditional→Simplified for TRANSCRIPTS: plain `t2s` (TSPhrases +
         * TSCharacters, which only disambiguate how a character is written) with NO reversal of the
         * Taiwan vocabulary. Same phonetic-vs-semantic rule as [getTranscriptTraditional]: ASR
         * records what was said, so the word must stay the same word. [get] with SIMPLIFIED
         * (`tw2sp`) is for generated text.
         */
        fun getTranscriptSimplified(context: Context): OpenCcConverter =
            transcriptSimplified ?: synchronized(this) {
                transcriptSimplified ?: buildTranscriptSimplified(context.assets::open).also { transcriptSimplified = it }
            }

        private fun buildTranscriptSimplified(open: (String) -> InputStream): OpenCcConverter {
            val t2s = HashMap<String, String>(8192)
            loadInto(open, "opencc/TSPhrases.txt", t2s)
            loadInto(open, "opencc/TSCharacters.txt", t2s)
            return build(listOf(t2s), traditionalOnly(open))
        }

        private fun buildTraditionalConservative(open: (String) -> InputStream): OpenCcConverter {
            val twChars = HashMap<String, String>(2048)
            loadInto(open, "opencc/TWVariants.txt", twChars)
            return build(listOf(s2tStage(open), twChars), simplifiedOnly(open))
        }

        /**
         * The S→T stage (~53k entries, parsed from a 1 MB pair of dictionaries) shared by BOTH
         * Traditional converters — a zh-Hant session builds both (conservative for the transcript,
         * localising for the summary) and used to hold two identical copies resident. The stage is
         * read-only once built, so sharing it is safe.
         */
        @Volatile private var s2t: Map<String, String>? = null

        private fun s2tStage(open: (String) -> InputStream): Map<String, String> =
            s2t ?: synchronized(this) {
                s2t ?: HashMap<String, String>(60_000).also { m ->
                    loadInto(open, "opencc/STPhrases.txt", m)
                    loadInto(open, "opencc/STCharacters.txt", m)
                    S2T_DROP.forEach(m::remove)
                    m.putAll(S2T_GUARD)
                    loadInto(open, "opencc/STGuard.txt", m)
                    s2t = m
                }
            }

        /**
         * STPhrases maps 了解 (and 了解到, 不了解, 据了解) to 瞭解. Longest match then reads the particle
         * 了 plus the next word as that phrase: 討論了解決方案 became 討論瞭解決方案, 說明了解約 became
         * 說明瞭解約, even for text already in Traditional. 了解 is a standard Taiwan spelling, so
         * the plain character mapping (了→了) is right everywhere.
         */
        internal val S2T_DROP = listOf("了解", "了解到", "不了解", "据了解")

        /**
         * Same cross-word problem for other 了X / X明了 entries: the particle 了 followed by 如何 or
         * 然后 (了如→瞭如, 了然→瞭然), or a verb ending in 明 followed by 了 (明了→明瞭: 說明了 →
         * 說明瞭). Longer identity keys win the longest match; 了如指掌, 一目了然, 明了 keep their
         * entries. Upstream already guards simplified 说明了/证明了; these cover the rest and the
         * Traditional input forms.
         */
        internal val S2T_GUARD: Map<String, String> = buildMap {
            put("了如何", "了如何")
            put("了然后", "了然後"); put("了然後", "了然後")
            for (v in listOf("說明", "證明", "表明", "聲明", "查明", "闡明", "標明", "註明", "注明",
                "指明", "寫明", "講明", "點明", "聰明", "精明", "说明", "证明", "声明", "阐明", "标明",
                "写明", "讲明", "点明", "聪明")) {
                val trad = v.map { c -> mapOf('说' to '說', '证' to '證', '声' to '聲', '阐' to '闡',
                    '标' to '標', '写' to '寫', '讲' to '講', '点' to '點', '聪' to '聰')[c] ?: c }.joinToString("")
                put(v + "了", trad + "了")
            }
        }

        @Volatile private var simpOnly: Set<Int>? = null
        @Volatile private var tradOnly: Set<Int>? = null

        /**
         * Characters the S→T character table changes (first candidate differs: 们, 发, 据, 后, 干…): a span
         * holding one is treated as Simplified. 后/干/里 also occur in Traditional words, which is why the
         * phrase tables' identity rows (皇后, 干涉, 公里) are kept.
         */
        private fun simplifiedOnly(open: (String) -> InputStream): Set<Int> =
            simpOnly ?: synchronized(this) { simpOnly ?: scriptOnly(open, "opencc/STCharacters.txt").also { simpOnly = it } }

        /** Characters the T→S character table changes (們, 發, 後, 乾, 瞭…): a span holding one is Traditional. */
        private fun traditionalOnly(open: (String) -> InputStream): Set<Int> =
            tradOnly ?: synchronized(this) { tradOnly ?: scriptOnly(open, "opencc/TSCharacters.txt").also { tradOnly = it } }

        private fun scriptOnly(open: (String) -> InputStream, asset: String): Set<Int> {
            val out = HashSet<Int>(4096)
            try {
                open(asset).use { raw ->
                    raw.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        seq.forEach { line ->
                            if (line.isBlank() || line.startsWith('#')) return@forEach
                            val tab = line.indexOf('\t')
                            if (tab <= 0) return@forEach
                            val key = line.substring(0, tab)
                            if (key.codePointCount(0, key.length) != 1) return@forEach
                            if (line.substring(tab + 1).substringBefore(' ').trim() != key) out.add(key.codePointAt(0))
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("OpenCcConverter", "failed loading $asset", e)
            }
            return out
        }

        /** Span boundaries for gating: whitespace and CJK/ASCII punctuation. */
        private fun isDelimiter(cp: Int): Boolean = Character.isWhitespace(cp) || cp == 0x3000 || cp in DELIMS

        private val DELIMS: Set<Int> = "，。！？、；：,.!?;:「」『』（）()《》〈〉“”‘’\"'…—·[]【】".codePoints().toArray().toHashSet()

        @Volatile private var tsChars: CharArray? = null

        /**
         * Character-level Traditional→Simplified table (TSCharacters, first choice, BMP) for search
         * normalization: a lookup per character, no phrase matching — [convert] costs ~5 ms per
         * 200-character passage on a Note10+, which made scanning a whole library seconds long.
         * Any other character maps to itself.
         */
        fun simplifiedChars(context: Context): CharArray = tsChars ?: synchronized(this) {
            tsChars ?: CharArray(65536) { it.toChar() }.also { t ->
                context.assets.open("opencc/TSCharacters.txt").bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.forEach { line ->
                        if (line.startsWith('#') || line.indexOf('\t') != 1) return@forEach
                        val v = line.substring(2).substringBefore(' ')
                        if (v.length == 1) t[line[0].code] = v[0]
                    }
                }
                tsChars = t
            }
        }

        /** Uncached converters over any dictionary source, for JVM tests that read the asset files. */
        internal fun forTest(open: (String) -> InputStream): Triple<OpenCcConverter, OpenCcConverter, OpenCcConverter> =
            Triple(buildTraditional(open), buildTraditionalConservative(open), buildSimplified(open))

        internal fun transcriptSimplifiedForTest(open: (String) -> InputStream): OpenCcConverter = buildTranscriptSimplified(open)

        /** Build once per script (call off the main thread); later calls return the cached instance. */
        fun get(context: Context, script: ChineseScript): OpenCcConverter = when (script) {
            ChineseScript.TRADITIONAL ->
                traditional ?: synchronized(this) { traditional ?: buildTraditional(context.assets::open).also { traditional = it } }
            ChineseScript.SIMPLIFIED ->
                simplified ?: synchronized(this) { simplified ?: buildSimplified(context.assets::open).also { simplified = it } }
        }

        // s2twp: stage 1 S→T (phrases then chars merged — longest-match prefers the phrase entries);
        // stage 2 the Taiwan-localisation pass (variant dicts first, the lexicon LAST so TW vocabulary
        // wins on any key clash). Stage-2 keys are in Traditional form (post stage 1).
        private fun buildTraditional(open: (String) -> InputStream): OpenCcConverter {
            val tw = HashMap<String, String>(2048)
            loadInto(open, "opencc/TWVariants.txt", tw)
            loadInto(open, "opencc/TWVariantsPhrases.txt", tw)
            loadInto(open, LEXICON, tw)
            return build(listOf(s2tStage(open), tw), simplifiedOnly(open))
        }

        /**
         * Curated Taiwan vocabulary (~270 rows, mainland word in s2t form → Taiwan word), replacing OpenCC
         * TWPhrases, whose software vocabulary rewrote ordinary meeting prose (文件→檔案, 權限→許可權,
         * 擴展→擴充套件, 優化→最佳化, 數據→資料, 項目→專案, 發布→釋出). Identity rows are guards
         * (軟體動物, 程序委員會, 地點選…). The same file ships on iOS.
         */
        internal const val LEXICON = "opencc/TWLexicon.txt"

        // tw2sp: stage 1 undoes the Taiwan localisation (lexicon reversed, first row wins — so 介面 → 界面,
        // 預設 → 默认 — then TWVariantsRevPhrases + TWVariantsRev), stage 2 is t2s (TSPhrases +
        // TSCharacters). The summary model writes Taiwan Traditional; plain t2s would leave 資訊/軟體
        // untouched. Simplified input passes through unchanged (span gating, and every key is Traditional).
        private fun buildSimplified(open: (String) -> InputStream): OpenCcConverter {
            val twRev = HashMap<String, String>(2048)
            loadReverseInto(open, LEXICON, twRev, keepIdentity = true)
            loadReverseInto(open, "opencc/TWVariantsPhrases.txt", twRev)
            loadReverseInto(open, "opencc/TWVariants.txt", twRev)
            val t2s = HashMap<String, String>(8192)
            loadInto(open, "opencc/TSPhrases.txt", t2s)
            loadInto(open, "opencc/TSCharacters.txt", t2s)
            return build(listOf(twRev, t2s), traditionalOnly(open))
        }

        /** OpenCC `*Rev` dictionary: every target of a line maps back to its source; earlier files win. */
        private fun loadReverseInto(
            open: (String) -> InputStream, asset: String, into: MutableMap<String, String>, keepIdentity: Boolean = false,
        ) {
            try {
                open(asset).use { raw ->
                    raw.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        seq.forEach { line ->
                            if (line.isBlank() || line.startsWith('#')) return@forEach
                            val tab = line.indexOf('\t')
                            if (tab <= 0) return@forEach
                            val src = line.substring(0, tab)
                            line.substring(tab + 1).split(' ').forEach { v ->
                                if (v.isNotEmpty() && (v != src || keepIdentity)) into.putIfAbsent(v, src)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("OpenCcConverter", "failed loading $asset", e)
            }
        }

        private fun build(stages: List<Map<String, String>>, gate: Set<Int>): OpenCcConverter {
            val maxKey = stages.asSequence().flatMap { it.keys.asSequence() }.maxOfOrNull { it.length } ?: 1
            return OpenCcConverter(stages, maxKey, gate)
        }

        /**
         * OpenCC line: "src<TAB>tgt[ tgt2 …]" — take the first target; skip blank/# lines. Identity rows
         * (干涉→干涉, 皇后→皇后, 公里→公里 in STPhrases) are kept: they are how OpenCC stops the
         * character table (干→幹, 后→後, 里→裡) inside those words.
         */
        private fun loadInto(open: (String) -> InputStream, asset: String, into: MutableMap<String, String>) {
            try {
                open(asset).use { raw ->
                    raw.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        seq.forEach { line ->
                            if (line.isBlank() || line.startsWith('#')) return@forEach
                            val tab = line.indexOf('\t')
                            if (tab <= 0) return@forEach
                            val src = line.substring(0, tab)
                            val first = line.substring(tab + 1).substringBefore(' ').trim()
                            if (first.isNotEmpty()) into[src] = first
                        }
                    }
                }
                android.util.Log.i("OpenCcConverter", "loaded $asset, total entries now ${into.size}")
            } catch (e: Throwable) {
                android.util.Log.e("OpenCcConverter", "failed loading $asset", e)
            }
        }
    }
}
