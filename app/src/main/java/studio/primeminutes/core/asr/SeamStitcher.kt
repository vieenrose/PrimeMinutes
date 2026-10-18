package studio.primeminutes.core.asr

import studio.primeminutes.core.events.TranscriptEvent.Utterance

/**
 * Joins a resumed ASR run onto the frozen transcript it continues. The resumed engine restarts its
 * diarizer (speaker ids start over) and is fed [PREROLL_SEC] of audio BEFORE the seam: those
 * re-recognised utterances overlap ones we already hold, so they tell us which new speaker id is
 * which old one. They are then dropped, leaving the new text to start exactly at the seam.
 */
object SeamStitcher {
    const val PREROLL_SEC = 30.0

    /** Returns the full transcript (prior + new) and how many of its leading utterances are stable. */
    fun stitch(prior: List<Utterance>, seamSec: Double, fresh: List<Utterance>, freshStable: Int): Pair<List<Utterance>, Int> {
        if (prior.isEmpty()) return fresh to freshStable
        val overlap = HashMap<Pair<Int, Int>, Double>()   // (fresh speaker, prior speaker) → seconds
        for (f in fresh) {
            val fs = f.speaker ?: continue
            if (f.startSec >= seamSec) continue
            for (p in prior) {
                val ps = p.speaker ?: continue
                val o = minOf(f.endSec, p.endSec) - maxOf(f.startSec, p.startSec)
                if (o > 0) overlap.merge(fs to ps, o, Double::plus)
            }
        }
        val map = HashMap<Int, Int>()
        val taken = HashSet<Int>()
        overlap.entries.sortedByDescending { it.value }.forEach { (k, _) ->
            if (k.first !in map && k.second !in taken) { map[k.first] = k.second; taken += k.second }
        }
        var nextId = (prior.mapNotNull { it.speaker }.maxOrNull() ?: -1) + 1
        fun speakerOf(s: Int?): Int? = s?.let { map.getOrPut(it) { nextId++ } }

        val kept = ArrayList<Utterance>()
        var keptStable = 0
        fresh.forEachIndexed { i, f ->
            if ((f.startSec + f.endSec) / 2 < seamSec) return@forEachIndexed
            // An utterance straddling the seam re-recognises the words that close the prior transcript
            // ("…生效。很好，客戶" + "很好，客戶通知信…"): drop that repeated head.
            val g = if (f.startSec < seamSec && kept.isEmpty()) trimRepeatedHead(prior.last().text, f) else f
            if (g.text.isBlank()) return@forEachIndexed
            kept += g.copy(speaker = speakerOf(f.speaker), index = prior.size + kept.size)
            if (i < freshStable) keptStable++
        }
        return (prior + kept) to (prior.size + keptStable)
    }

    /** [f] without its longest head (at least 2 chars) that repeats the end of [priorText]. */
    internal fun trimRepeatedHead(priorText: String, f: Utterance): Utterance {
        val text = f.text.trimStart()
        val tail = priorText.trimEnd()
        var k = minOf(text.length, tail.length)
        // Latin words must match whole ("upon" does not end with the word "on").
        fun latin(c: Char?) = c != null && c.code < 0x2E80 && c.isLetterOrDigit()
        fun ok(k: Int) = tail.endsWith(text.substring(0, k)) &&
            !(latin(tail.getOrNull(tail.length - k - 1)) && latin(text[0])) &&
            !(latin(text.getOrNull(k)) && latin(text[k - 1]))
        while (k >= 2 && !ok(k)) k--
        if (k < 2) return f
        val rest = text.substring(k).trimStart()
        val toks = f.tokens; val times = f.tokenTimes
        // Keep the token timings when the tokens spell the text: drop those inside the repeated head.
        val joined = toks?.joinToString("")
        if (toks != null && times != null && toks.size == times.size && joined!!.trimStart() == text) {
            val target = joined.length - text.length + k   // leading whitespace + the repeated head
            var len = 0; var n = 0
            while (n < toks.size && len < target) { len += toks[n].length; n++ }
            if (len == target && n < toks.size) {
                val shift = times[n]
                return f.copy(text = rest, startSec = f.startSec + shift,
                    tokens = toks.drop(n), tokenTimes = times.drop(n).map { it - shift })
            }
        }
        return f.copy(text = rest, tokens = null, tokenTimes = null)
    }
}
