package studio.primeminutes.core.text

import android.content.Context
import java.io.InputStream

/** Which Han script to normalize Chinese text into. */
enum class ChineseScript { TRADITIONAL, SIMPLIFIED }

/**
 * Minimal on-device OpenCC converter, both directions, used to keep ALL output text (transcript,
 * summary, title, speaker names → and the lyrics built from them) in one consistent script:
 *  - [ChineseScript.TRADITIONAL] = Simplified→Traditional, Taiwan standard with phrases (`s2twp`):
 *    stage 1 maps S→T (STPhrases then STCharacters); stage 2 applies the Taiwan localisation
 *    (TWVariants + TWVariantsPhrases + TWPhrases) so vocabulary reads native — 資訊 not 信息, 影片 not
 *    視頻, 軟體 not 軟件.
 *  - [ChineseScript.SIMPLIFIED]  = Traditional→Simplified (`t2s`): TSPhrases then TSCharacters.
 * Longest-match over the bundled OpenCC dictionaries (Apache-2.0, in assets/opencc/). One instance per
 * script, built once and cached, reused. Good enough for script consistency; not a full idiom engine.
 */
class OpenCcConverter private constructor(
    private val stages: List<Map<String, String>>,
    private val maxKey: Int,
) {
    fun convert(text: String): String {
        // Skip non-Chinese text. OpenCC maps shared Han characters to Traditional variants, which mangles
        // Japanese/Korean output (e.g. a model that summarized a JA/KO source in its own language — see the
        // cross-lingual target-language case). Latin text has no Han chars so it's untouched anyway; this
        // guards the CJK-but-not-Chinese case where conversion would corrupt the text.
        if (hasKanaOrHangul(text)) return text
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
                transcriptSimplified ?: run {
                    val open: (String) -> InputStream = context.assets::open
                    val t2s = HashMap<String, String>(8192)
                    loadInto(open, "opencc/TSPhrases.txt", t2s)
                    loadInto(open, "opencc/TSCharacters.txt", t2s)
                    build(listOf(t2s))
                }.also { transcriptSimplified = it }
            }

        private fun buildTraditionalConservative(open: (String) -> InputStream): OpenCcConverter {
            val twChars = HashMap<String, String>(2048)
            loadInto(open, "opencc/TWVariants.txt", twChars)
            return build(listOf(s2tStage(open), twChars))
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

        /** Build once per script (call off the main thread); later calls return the cached instance. */
        fun get(context: Context, script: ChineseScript): OpenCcConverter = when (script) {
            ChineseScript.TRADITIONAL ->
                traditional ?: synchronized(this) { traditional ?: buildTraditional(context.assets::open).also { traditional = it } }
            ChineseScript.SIMPLIFIED ->
                simplified ?: synchronized(this) { simplified ?: buildSimplified(context.assets::open).also { simplified = it } }
        }

        // s2twp: stage 1 S→T (phrases then chars merged — longest-match prefers the phrase entries);
        // stage 2 the Taiwan-localisation pass (variant dicts first, phrase dict LAST so TW vocabulary
        // wins on any key clash). Stage-2 keys are in Traditional form (post stage 1).
        private fun buildTraditional(open: (String) -> InputStream): OpenCcConverter {
            val tw = HashMap<String, String>(2048)
            loadInto(open, "opencc/TWVariants.txt", tw)
            loadInto(open, "opencc/TWVariantsPhrases.txt", tw)
            loadInto(open, "opencc/TWPhrases.txt", tw)
            PROSE_KEEP.forEach(tw::remove)
            return build(listOf(s2tStage(open), tw))
        }

        /**
         * TWPhrases entries that are software vocabulary, applied by OpenCC to any text: in meeting
         * prose they turn 擴展性 into 擴充套件性, 權限 into 許可權, 類型 into 型別, 項目 into 專案 (seen in
         * generated summaries). Both words are ordinary Taiwan Chinese, so the plain s2t result stays.
         * Only the forward (s2twp) stage drops them; tw2sp still maps the TW word back.
         */
        internal val PROSE_KEEP = listOf("擴展", "權限", "類型", "項目", "對象", "文件", "參數", "實例", "字段", "函數")

        // tw2sp, OpenCC's own Taiwan→Simplified-with-phrases chain: stage 1 undoes the Taiwan
        // localisation (TWPhrasesRev + TWVariantsRevPhrases + TWVariantsRev, the reverse of the
        // dictionaries s2twp uses), stage 2 is t2s (TSPhrases + TSCharacters). The summary model
        // writes Taiwan Traditional; plain t2s would leave 資訊/軟體/搜尋 untouched. Simplified input
        // passes through unchanged (every stage-1 key is Traditional).
        private fun buildSimplified(open: (String) -> InputStream): OpenCcConverter {
            val twRev = HashMap<String, String>(2048)
            loadReverseInto(open, "opencc/TWPhrases.txt", twRev)
            loadReverseInto(open, "opencc/TWVariantsPhrases.txt", twRev)
            loadReverseInto(open, "opencc/TWVariants.txt", twRev)
            // "點選" (Taiwan for "click") also ends a common noun: 地點選在… is 地點 + 選在, not
            // 地 + 點選 (seen live: "地点击在信义区"). Longest match from the left prefers these
            // longer keys, which stay as they are for t2s.
            NOUN_THEN_SELECT.forEach { twRev[it] = it }
            // 執行 is "carry out" (a plan, a decision) far more often than "run" (a program) in a
            // meeting, and mainland Chinese says 执行 too: "執行細節" became "运行细节".
            twRev["執行"] = "執行"
            // 介面 reverses to the FIRST TWPhrases line that targets it, 接口 (an API), but in meeting
            // prose it is the user interface: 會議介面 became 会议接口; 界面 is the mainland word.
            twRev["介面"] = "界面"
            val t2s = HashMap<String, String>(8192)
            loadInto(open, "opencc/TSPhrases.txt", t2s)
            loadInto(open, "opencc/TSCharacters.txt", t2s)
            return build(listOf(twRev, t2s))
        }

        private val NOUN_THEN_SELECT = listOf(
            "地點選", "重點選", "景點選", "觀點選", "優點選", "缺點選", "起點選", "終點選", "站點選", "據點選", "時點選", "焦點選",
        )

        /** OpenCC `*Rev` dictionary: every target of a line maps back to its source; earlier files win. */
        private fun loadReverseInto(open: (String) -> InputStream, asset: String, into: MutableMap<String, String>) {
            try {
                open(asset).use { raw ->
                    raw.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        seq.forEach { line ->
                            if (line.isBlank() || line.startsWith('#')) return@forEach
                            val tab = line.indexOf('\t')
                            if (tab <= 0) return@forEach
                            val src = line.substring(0, tab)
                            line.substring(tab + 1).split(' ').forEach { v ->
                                if (v.isNotEmpty() && v != src) into.putIfAbsent(v, src)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("OpenCcConverter", "failed loading $asset", e)
            }
        }

        private fun build(stages: List<Map<String, String>>): OpenCcConverter {
            val maxKey = stages.asSequence().flatMap { it.keys.asSequence() }.maxOfOrNull { it.length } ?: 1
            return OpenCcConverter(stages, maxKey)
        }

        /** OpenCC line: "src<TAB>tgt[ tgt2 …]" — take the first target; skip blank/# lines. */
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
                            if (first.isNotEmpty() && first != src) into[src] = first
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
