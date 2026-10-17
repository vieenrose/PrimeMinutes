package studio.primeminutes.core.people

import kotlin.math.sqrt

/**
 * Voice resemblance (docs/design/speaker-tags.md §4.4), pure. A person's voice is the seconds-weighted
 * mean of their CONFIRMED exemplars for the current model; a speaker is compared to it by cosine; the
 * meeting's speakers are assigned jointly so one person is not the first suggestion of two speakers.
 */
object VoiceMath {
    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        if (n <= 0) return v.copyOf()
        val inv = (1.0 / sqrt(n)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    fun cos(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i].toDouble() * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
        return if (na <= 0 || nb <= 0) 0f else (d / sqrt(na * nb)).toFloat()
    }

    /** Seconds-weighted mean of unit vectors, re-normalized; null when there is none. */
    fun centroid(exemplars: List<Pair<FloatArray, Float>>): FloatArray? {
        val dim = exemplars.firstOrNull()?.first?.size ?: return null
        val acc = DoubleArray(dim)
        var any = false
        for ((v, w) in exemplars) {
            if (v.size != dim) continue
            val u = normalize(v)
            for (i in 0 until dim) acc[i] += u[i] * w.coerceAtLeast(1e-3f).toDouble()
            any = true
        }
        return if (!any) null else normalize(FloatArray(dim) { acc[it].toFloat() })
    }

    /** One suggestion level: 3 likely, 2 possible, 0 nothing (see [SpeakerCard]). */
    fun level(score: Float, next: Float, card: SpeakerCard): Int = when {
        score >= card.tHigh && score - next >= card.margin -> 3
        score >= card.tLow -> 2
        else -> 0
    }

    /**
     * Suggestions for every speaker of a meeting. [speakers]: speaker → vector. [people]: person → voice.
     * [excluded]: speaker → people never suggested for it (already linked in this meeting, or its own link).
     * The best (speaker, person) pairs are taken greedily in score order; a person taken by one speaker is
     * not offered to another; at most [max] per speaker, above [SpeakerCard.tLow] only.
     */
    fun suggest(
        speakers: Map<Int, FloatArray>, people: Map<String, FloatArray>, card: SpeakerCard,
        excluded: Map<Int, Set<String>> = emptyMap(), max: Int = 3,
    ): Map<Int, List<VoiceSuggestion>> {
        val scores = HashMap<Int, List<Pair<String, Float>>>()
        for ((sid, v) in speakers) {
            val ex = excluded[sid].orEmpty()
            scores[sid] = people.filterKeys { it !in ex }.map { (p, c) -> p to cos(v, c) }.sortedByDescending { it.second }
        }
        val owner = HashMap<String, Int>()
        val taken = HashSet<Int>()
        scores.flatMap { (sid, l) -> l.map { Triple(sid, it.first, it.second) } }
            .filter { it.third >= card.tLow }.sortedByDescending { it.third }
            .forEach { (sid, p, _) -> if (sid !in taken && p !in owner) { owner[p] = sid; taken += sid } }
        return scores.mapValues { (sid, l) ->
            val mine = l.filter { (p, s) -> s >= card.tLow && (owner[p] == null || owner[p] == sid) }
            mine.take(max).mapIndexed { i, (p, s) ->
                // "Likely" needs the margin over the next person (any person, shown or not).
                val next = l.firstOrNull { it.first != p && it.second <= s }?.second ?: -1f
                VoiceSuggestion(p, if (i == 0) level(s, next, card) else minOf(2, level(s, next, card)), s)
            }.filter { it.level > 0 }
        }
    }
}
