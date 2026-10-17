package studio.primeminutes.core.share

/**
 * The logic-less Mustache subset the participants e-mail templates use (docs/design/speaker-contacts.md
 * §5.3), the same semantics as the design's reference renderer `render_samples.py` and the iOS port:
 * `{{x}}` (HTML-escaped when [escape]), `{{{x}}}` raw, `{{#x}}…{{/x}}` (a list repeats, a map or any
 * other truthy value renders once), `{{^x}}…{{/x}}` (falsy), and the standalone rule: a line holding
 * only a section tag disappears with its newline. A context is a `Map<String, Any?>`; inside a section
 * the item's keys are laid over the enclosing ones.
 */
object MiniMustache {
    private val TAG = Regex("""\{\{(\{?)([#^/]?)\s*([\w.]+)\s*\}?\}\}""")
    private val STANDALONE = Regex("""^[ \t]*(\{\{[#^/][^}]*\}\})[ \t]*\n""", RegexOption.MULTILINE)

    fun render(template: String, ctx: Map<String, Any?>, escape: Boolean): String {
        val tpl = STANDALONE.replace(template) { it.groupValues[1] }
        val out = StringBuilder()
        var pos = 0
        while (true) {
            val m = TAG.find(tpl, pos)
            if (m == null) { out.append(tpl, pos, tpl.length); break }
            out.append(tpl, pos, m.range.first)
            val raw = m.groupValues[1].isNotEmpty()
            val kind = m.groupValues[2]
            val name = m.groupValues[3]
            if (kind == "#" || kind == "^") {
                val (body, next) = section(tpl, m.range.last + 1, name)
                pos = next
                val v = lookup(ctx, name)
                if (kind == "^") {
                    if (!truthy(v)) out.append(render(body, ctx, escape))
                } else if (v is List<*>) {
                    for (x in v) {
                        @Suppress("UNCHECKED_CAST")
                        val inner = if (x is Map<*, *>) ctx + (x as Map<String, Any?>) else ctx + mapOf("." to x)
                        out.append(render(body, inner, escape))
                    }
                } else if (truthy(v)) {
                    @Suppress("UNCHECKED_CAST")
                    out.append(render(body, if (v is Map<*, *>) ctx + (v as Map<String, Any?>) else ctx, escape))
                }
                continue
            }
            val v = lookup(ctx, name)?.toString() ?: ""
            out.append(if (escape && !raw) escapeHtml(v) else v)
            pos = m.range.last + 1
        }
        return out.toString()
    }

    private fun lookup(ctx: Map<String, Any?>, name: String): Any? = ctx[name]

    /** Python's truthiness, which the reference renderer uses. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0
        else -> true
    }

    /** The body of the section opened just before [start], and the index after its closing tag. */
    private fun section(tpl: String, start: Int, name: String): Pair<String, Int> {
        var depth = 1
        var i = start
        while (true) {
            val m = TAG.find(tpl, i) ?: throw IllegalArgumentException("unclosed $name")
            if (m.groupValues[3] == name && (m.groupValues[2] == "#" || m.groupValues[2] == "^")) depth++
            else if (m.groupValues[3] == name && m.groupValues[2] == "/") {
                depth--
                if (depth == 0) return tpl.substring(start, m.range.first) to m.range.last + 1
            }
            i = m.range.last + 1
        }
    }

    /** Python's `html.escape(s, quote=False)`. */
    fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
