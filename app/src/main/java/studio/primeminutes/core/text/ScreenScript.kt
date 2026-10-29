package studio.primeminutes.core.text

import android.content.Context

/**
 * The screens outside the open meeting (People, Ask, the podcast channel page) in the app's Han script
 * (Settings language → `SummaryScript.scriptFor`), like iOS `ScreenScript.swift`. Display only: nothing
 * stored is rewritten, an edit saves what was typed ([typed]) and an Ask question is sent as typed.
 *
 * [gen] is the span-gated converter for generated or published text (titles, answers, notes, channel
 * and episode names); [tr] the character-level one for what was said (excerpts). Both are memoized, so a
 * redraw costs a map lookup per string. Person names (OS contacts, typed by the user) are NOT converted.
 */
class ScreenScript(val script: ChineseScript, generated: (String) -> String, transcript: (String) -> String) {
    /** One converter with a bounded text → converted memo (cleared past [cap] entries). */
    class Memo(private val f: (String) -> String, private val cap: Int = 20_000) {
        private val m = HashMap<String, String>()
        var misses = 0; private set
        operator fun invoke(t: String): String {
            if (t.isEmpty() || t.all { it.code < 128 }) return t
            synchronized(m) { m[t]?.let { return it } }
            val r = f(t)
            synchronized(m) { if (m.size >= cap) m.clear(); m[t] = r; misses++ }
            return r
        }
    }

    val gen = Memo(generated)
    val tr = Memo(transcript)

    companion object {
        private val byScript = HashMap<ChineseScript, ScreenScript>()

        /** The converters of a script, built once (call off the main thread: the first one loads the dictionaries). */
        fun of(context: Context, script: ChineseScript): ScreenScript = synchronized(byScript) {
            byScript.getOrPut(script) {
                val g = OpenCcConverter.get(context, script)
                val t = if (script == ChineseScript.TRADITIONAL) OpenCcConverter.getTranscriptTraditional(context)
                    else OpenCcConverter.getTranscriptSimplified(context)
                ScreenScript(script, g::convert, t::convert)
            }
        }

        /** An edit opened on [shown] (the converted text): unchanged → null (keep the stored text); else what was typed. */
        fun typed(draft: String, shown: String): String? = if (draft == shown) null else draft
    }
}
