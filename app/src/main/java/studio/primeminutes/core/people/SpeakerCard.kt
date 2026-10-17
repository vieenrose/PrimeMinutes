package studio.primeminutes.core.people

import org.json.JSONObject

/**
 * The speaker-embedding model's card (`speaker_cards/<name>.json`, the same file on Android and iOS):
 * what to download, its input contract and the calibrated thresholds. Features read the thresholds
 * from here, never from constants, so another model is a new card plus a re-calibration
 * (docs/design/speaker-tags.md §3.6, memory harness-model-agnostic).
 */
data class SpeakerCard(
    val id: String,
    val displayName: String,
    val licence: String,
    val source: String,
    val dirName: String,
    /** HF `resolve/<commit>` base URL. */
    val revision: String,
    /** relative path → (size, sha256). */
    val files: Map<String, Pair<Long, String>>,
    val graph: String,
    val dim: Int,
    val mels: Int,
    val meanNorm: Boolean,
    val int16Scale: Boolean,
    val minCleanSec: Double,
    val capSec: Double,
    /** Under [tLow] nothing is suggested; [tLow]..[tHigh] "possible"; ≥ [tHigh] with [margin] over the next "likely". */
    val tLow: Float,
    val tHigh: Float,
    val margin: Float,
    /** A confirmed exemplar this far from the person's voice asks before it is learned. */
    val outlier: Float,
) {
    val totalBytes: Long get() = files.values.sumOf { it.first }

    companion object {
        const val DEFAULT = "campplus-zh-en"

        fun load(name: String = DEFAULT): SpeakerCard {
            val text = checkNotNull(SpeakerCard::class.java.getResourceAsStream("/speaker_cards/$name.json")) { "no speaker card $name" }
                .bufferedReader().use { it.readText() }
            return parse(text)
        }

        fun parse(text: String): SpeakerCard {
            val o = JSONObject(text)
            val f = o.getJSONObject("files")
            val fe = o.getJSONObject("frontend")
            val t = o.getJSONObject("thresholds")
            return SpeakerCard(
                id = o.getString("id"), displayName = o.getString("displayName"), licence = o.getString("licence"),
                source = o.getString("source"), dirName = o.getString("dirName"), revision = o.getString("revision"),
                files = f.keys().asSequence().associateWith { k -> f.getJSONObject(k).let { it.getLong("size") to it.getString("sha256") } },
                graph = o.getString("graph"), dim = o.getInt("dim"),
                mels = fe.getInt("mels"), meanNorm = fe.getBoolean("meanNorm"), int16Scale = fe.optBoolean("int16Scale", false),
                minCleanSec = o.getDouble("minCleanSec"), capSec = o.getDouble("capSec"),
                tLow = t.getDouble("low").toFloat(), tHigh = t.getDouble("high").toFloat(),
                margin = t.getDouble("margin").toFloat(), outlier = t.getDouble("outlier").toFloat(),
            )
        }
    }
}
