package studio.primeminutes

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import studio.primeminutes.core.embed.Keywords
import studio.primeminutes.core.text.OpenCcConverter

/**
 * Cost of Ask's keyword pass on a large library: the reference transcripts cut in ~40 s chunks
 * (the index's unit) and repeated as N meetings: the one-time build of the BM25 docs (folded
 * script, term codes — cached per meeting version) and the cost per question. Logs
 * "KeywordBench: N=… units=… build … ms, per question … ms".
 *   adb shell am instrument -w -e class studio.primeminutes.KeywordBench <runner>
 */
@RunWith(AndroidJUnit4::class)
class KeywordBench {
    @Test fun keywordPassScales() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val chunks = listOf("move", "weiya").flatMap { n ->
            val us = JSONObject(ctx.assets.open("reader_gate/$n.json").bufferedReader().readText()).getJSONArray("utterances")
            val out = ArrayList<String>(); val sb = StringBuilder(); var t0 = 0.0
            for (i in 0 until us.length()) {
                val u = us.getJSONObject(i)
                if (sb.isEmpty()) t0 = u.getDouble("start")
                sb.append(u.getString("text")).append(' ')
                if (u.getDouble("end") - t0 >= 40) { out += sb.toString().take(1200); sb.clear() }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            out
        }
        val table = OpenCcConverter.simplifiedChars(app)
        val q = Keywords.fold("每人保底禮券多少錢？", table)
        for (meetings in listOf(10, 100, 500, 1000)) {
            val texts = (0 until meetings).flatMap { m -> chunks.map { "$it $m" } }   // a per-meeting suffix keeps the texts distinct
            val t0 = System.nanoTime()
            val docs = texts.map { Keywords.doc(Keywords.fold(it, table)) }   // once per meeting version (cached)
            val t1 = System.nanoTime()
            repeat(2) { Keywords.score(q, docs) }
            val t2 = System.nanoTime()
            Log.i("KeywordBench", "N=$meetings units=${texts.size} chars=${texts.sumOf { it.length }} build ${(t1 - t0) / 1_000_000} ms, per question ${(t2 - t1) / 2_000_000} ms")
        }
    }
}
