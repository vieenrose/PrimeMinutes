package studio.primeminutes.core.people

/**
 * A re-run renumbers the speakers. Names (and person links) are carried over by time overlap, no ML
 * (docs/design/speaker-tags.md §3.4): a new speaker inherits an old speaker's name when the old one
 * covers at least [NEW_SHARE] of the new speaker's speech AND the new one covers at least [OLD_SHARE]
 * of the old speaker's. Anything less leaves the speaker unnamed.
 */
object SpeakerCarryOver {
    const val NEW_SHARE = 0.6
    const val OLD_SHARE = 0.5

    data class Turn(val start: Double, val end: Double, val speaker: Int?)

    /** old speaker id → new speaker id, for the old speakers that pass both shares. */
    fun mapping(old: List<Turn>, new: List<Turn>): Map<Int, Int> {
        val oldTime = HashMap<Int, Double>(); val newTime = HashMap<Int, Double>()
        for (t in old) t.speaker?.let { oldTime[it] = (oldTime[it] ?: 0.0) + (t.end - t.start).coerceAtLeast(0.0) }
        for (t in new) t.speaker?.let { newTime[it] = (newTime[it] ?: 0.0) + (t.end - t.start).coerceAtLeast(0.0) }
        // overlap[old][new], by a sweep over both lists in start order.
        val overlap = HashMap<Pair<Int, Int>, Double>()
        val a = old.filter { it.speaker != null && it.end > it.start }.sortedBy { it.start }
        val b = new.filter { it.speaker != null && it.end > it.start }.sortedBy { it.start }
        var j0 = 0
        for (x in a) {
            while (j0 < b.size && b[j0].end <= x.start) j0++
            var j = j0
            while (j < b.size && b[j].start < x.end) {
                val y = b[j]
                val ov = minOf(x.end, y.end) - maxOf(x.start, y.start)
                if (ov > 0) { val k = x.speaker!! to y.speaker!!; overlap[k] = (overlap[k] ?: 0.0) + ov }
                j++
            }
        }
        val out = HashMap<Int, Int>()
        for ((k, ov) in overlap.entries.sortedByDescending { it.value }) {
            val (o, n) = k
            if (o in out || n in out.values) continue
            if (ov >= NEW_SHARE * (newTime[n] ?: 0.0) && ov >= OLD_SHARE * (oldTime[o] ?: 0.0)) out[o] = n
        }
        return out
    }

    /** The old names re-keyed by the new speaker ids (dropped where no new speaker inherits). */
    fun <N> carry(old: List<Turn>, oldNames: Map<Int, N>, new: List<Turn>): Map<Int, N> {
        if (oldNames.isEmpty() || new.isEmpty()) return emptyMap()
        val m = mapping(old, new)
        return oldNames.mapNotNull { (o, n) -> m[o]?.let { it to n } }.toMap()
    }
}
