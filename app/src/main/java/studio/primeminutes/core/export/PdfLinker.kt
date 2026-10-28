package studio.primeminutes.core.export

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Adds internal link annotations to a finished PDF. [android.graphics.pdf.PdfDocument] (Skia) has no
 * link API, so the export draws first, then this appends an incremental update (ISO 32000 §7.5.6):
 * one `/Annot /Link` object per link with `/Dest [page /XYZ null y null]`, the linked pages rewritten
 * with an `/Annots` array, a new xref section and a trailer pointing back with `/Prev`. The original
 * bytes are kept unchanged in front. Needs a classic xref table (what Skia writes); anything else →
 * the input is returned untouched (links are an extra, never a reason to lose the export).
 */
object PdfLinker {

    /** A link on page [page] (0-based) over [rect] (PDF user space: x1, y1, x2, y2, origin bottom-left)
     *  to page [destPage] at height [destY]. */
    data class Link(val page: Int, val rect: FloatArray, val destPage: Int, val destY: Float)

    private val OBJ = Regex("""(?m)^(\d+)\s+(\d+)\s+obj\b""")
    private val REF = Regex("""(\d+)\s+(\d+)\s+R""")

    fun link(pdf: ByteArray, links: List<Link>): ByteArray =
        if (links.isEmpty()) pdf else runCatching { apply(pdf, links) }.getOrElse { pdf }

    private fun apply(pdf: ByteArray, links: List<Link>): ByteArray {
        val s = String(pdf, Charsets.ISO_8859_1)
        val startXref = Regex("""startxref\s+(\d+)""").findAll(s).last().groupValues[1].toLong()
        val tIdx = s.lastIndexOf("trailer")
        require(tIdx >= 0) { "no classic trailer" }
        val trailer = s.substring(tIdx, s.indexOf("startxref", tIdx))
        val size = Regex("""/Size\s+(\d+)""").find(trailer)!!.groupValues[1].toInt()
        val root = Regex("""/Root\s+(\d+\s+\d+\s+R)""").find(trailer)!!.groupValues[1]
        val info = Regex("""/Info\s+(\d+\s+\d+\s+R)""").find(trailer)?.groupValues?.get(1)

        // Object bodies (the last definition wins, as in an updated file).
        val bodies = HashMap<Int, String>()
        val gens = HashMap<Int, Int>()
        for (m in OBJ.findAll(s)) {
            val start = m.range.last + 1
            val end = s.indexOf("endobj", start).takeIf { it > 0 } ?: continue
            val n = m.groupValues[1].toInt()
            bodies[n] = s.substring(start, end).trim()
            gens[n] = m.groupValues[2].toInt()
        }
        fun refNum(r: String) = REF.find(r)!!.groupValues[1].toInt()
        val catalog = bodies[refNum(root)]!!
        val pagesRoot = Regex("""/Pages\s+(\d+\s+\d+\s+R)""").find(catalog)!!.groupValues[1]

        // Page objects in reading order (a Skia page tree is balanced, several levels deep).
        val pages = ArrayList<Int>()
        fun walk(n: Int, depth: Int) {
            require(depth < 32) { "page tree too deep" }
            val b = bodies[n] ?: error("missing object $n")
            if (Regex("""/Type\s*/Pages\b""").containsMatchIn(b)) {
                val kids = Regex("""/Kids\s*\[([^\]]*)]""").find(b)!!.groupValues[1]
                REF.findAll(kids).forEach { walk(it.groupValues[1].toInt(), depth + 1) }
            } else pages += n
        }
        walk(refNum(pagesRoot), 0)

        val out = ByteArrayOutputStream()
        out.write(pdf)
        if (pdf.last() != '\n'.code.toByte()) out.write('\n'.code)
        val offsets = sortedMapOf<Int, Long>()
        fun obj(n: Int, gen: Int, body: String) {
            offsets[n] = out.size().toLong()
            out.write("$n $gen obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        var next = size
        val annots = HashMap<Int, MutableList<Int>>()
        for (l in links) {
            if (l.page !in pages.indices || l.destPage !in pages.indices) continue
            val r = l.rect
            val n = next++
            obj(n, 0, String.format(Locale.US,
                "<< /Type /Annot /Subtype /Link /Rect [%.2f %.2f %.2f %.2f] /Border [0 0 0] /Dest [%d 0 R /XYZ null %.2f null] >>",
                r[0], r[1], r[2], r[3], pages[l.destPage], l.destY))
            annots.getOrPut(pages[l.page]) { ArrayList() } += n
        }
        if (annots.isEmpty()) return pdf
        for ((p, list) in annots) {
            val b = bodies[p]!!
            val refs = list.joinToString(" ") { "$it 0 R" }
            val nb = if (b.contains("/Annots")) {
                val m = Regex("""/Annots\s*\[""").find(b) ?: error("indirect /Annots")
                b.substring(0, m.range.last + 1) + refs + " " + b.substring(m.range.last + 1)
            } else {
                val close = b.lastIndexOf(">>")
                b.substring(0, close) + " /Annots [" + refs + "] " + b.substring(close)
            }
            obj(p, gens[p] ?: 0, nb)
        }
        val xref = out.size().toLong()
        val sb = StringBuilder("xref\n")
        for ((n, off) in offsets) {
            sb.append(n).append(" 1\n").append(String.format(Locale.US, "%010d %05d n \n", off, if (n < size) gens[n] ?: 0 else 0))
        }
        sb.append("trailer\n<< /Size ").append(next).append(" /Root ").append(root)
        info?.let { sb.append(" /Info ").append(it) }
        sb.append(" /Prev ").append(startXref).append(" >>\nstartxref\n").append(xref).append("\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }
}
