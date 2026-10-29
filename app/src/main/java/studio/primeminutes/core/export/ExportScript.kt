package studio.primeminutes.core.export

/**
 * The shared PDF / .docx in the app's Han script (Settings language): [generated] (the span-gated
 * OpenCC converter for generated text) for the title, meta lines, summary, action items and speaker
 * names; [transcript] (character-level) for what was said. The stored session and the `.pmin` are
 * NOT converted — only this export view.
 *
 * Links stay intact: citations are `[m:ss]` timestamps and anchors are turn indexes, both resolved
 * from the converted text at layout time; OpenCC leaves digits, brackets, colons and the
 * U+2060/U+00A0 cite joiners alone (punctuation and spaces are span delimiters), so no stored
 * offset can drift even when a phrase changes length.
 */
object ExportScript {
    fun apply(d: PdfExport.Doc, generated: (String) -> String, transcript: (String) -> String): PdfExport.Doc {
        val label = d.label
        val names = HashMap<Int, String>()
        return d.copy(
            utterances = d.utterances.map { u -> u.copy(text = transcript(u.text), tokens = u.tokens?.map(transcript)) },
            label = { sid -> names.getOrPut(sid) { generated(label(sid)) } },
            title = d.title?.let(generated),
            meta = d.meta.map(generated),
            summary = d.summary?.let(generated),
            actions = d.actions?.let { s ->
                s.copy(heading = generated(s.heading), groups = s.groups.map { g ->
                    g.copy(label = g.label?.let(generated), items = g.items.map { it.copy(text = generated(it.text)) })
                })
            },
        )
    }
}
