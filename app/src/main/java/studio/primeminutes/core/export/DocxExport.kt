package studio.primeminutes.core.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The meeting as a Word document (the Share sheet's "Word" format): WordprocessingML written by
 * hand into a zip, no library, offline. Same content and order as the PDF ([PdfExport]): title
 * block, summary, action items, transcript. Real styles (Title, Heading1/2, Normal, a "Speaker"
 * character style, a grey "Timestamp" one); every cite in the summary / action items is a
 * `w:hyperlink w:anchor` to the bookmark on its transcript turn, and a cited turn ends with a
 * "↑ Summary" link back to a bookmark on the paragraph that first cites it. Pure (no Android).
 */
object DocxExport {

    /** [zh]: "zh-TW" / "zh-CN" for a Chinese document (the East Asian font hint and language), null otherwise. */
    fun write(out: OutputStream, d: PdfExport.Doc, zh: String?) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, xml: String) {
                z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", RELS)
            put("word/_rels/document.xml.rels", DOC_RELS)
            put("word/styles.xml", styles(zh))
            put("word/settings.xml", SETTINGS)
            put("word/document.xml", document(d))
        }
    }

    private val CITE = Regex("""\[(\d+:\d{2}(?::\d{2})?(?:\s*[,，、]\s*\d+:\d{2}(?::\d{2})?)*)]""")
    private val TIME = Regex("""\d+:\d{2}(?::\d{2})?""")

    /** The bookmark name of transcript turn [i]. */
    fun turnAnchor(i: Int) = "t$i"
    /** The bookmark name of the [i]-th citing paragraph. */
    fun citeAnchor(i: Int) = "c$i"

    internal fun document(d: PdfExport.Doc): String {
        val turns = TranscriptExport.turns(d.utterances)
        val starts = turns.filter { !it.cont }.map { it.u.startSec }
        val firstCite = LinkedHashMap<Int, String>()   // turn → the citing paragraph's bookmark
        var paraN = 0
        var bm = 0
        val b = StringBuilder()

        fun para(style: String?, body: String, pPr: String = "") {
            b.append("<w:p><w:pPr>")
            style?.let { b.append("<w:pStyle w:val=\"").append(it).append("\"/>") }
            b.append(pPr).append("</w:pPr>").append(body).append("</w:p>")
        }
        fun run(text: String, rStyle: String? = null) = buildString {
            append("<w:r>")
            rStyle?.let { append("<w:rPr><w:rStyle w:val=\"").append(it).append("\"/></w:rPr>") }
            append("<w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>")
        }
        fun link(anchor: String, text: String, rStyle: String = "Hyperlink") =
            "<w:hyperlink w:anchor=\"$anchor\" w:history=\"1\">" + run(text, rStyle) + "</w:hyperlink>"
        fun bookmark(name: String, inner: String): String {
            val id = bm++
            return "<w:bookmarkStart w:id=\"$id\" w:name=\"$name\"/>$inner<w:bookmarkEnd w:id=\"$id\"/>"
        }
        /** Text with its cites as links; the paragraph is bookmarked when it cites a turn. */
        fun cited(text: String): String {
            val sb = StringBuilder()
            var at = 0
            val name = citeAnchor(paraN++)
            var cites = false
            for (m in CITE.findAll(text)) {
                if (m.range.first > at) sb.append(run(text.substring(at, m.range.first)))
                sb.append(run("[", "Timestamp"))
                var k = 0
                val inner = m.groupValues[1]
                for (t in TIME.findAll(inner)) {
                    if (t.range.first > k) sb.append(run(inner.substring(k, t.range.first), "Timestamp"))
                    val turn = ExportNotes.targetFor(ExportNotes.seconds(t.value), starts)
                    if (turn != null) { sb.append(link(turnAnchor(turn), t.value)); firstCite.putIfAbsent(turn, name); cites = true }
                    else sb.append(run(t.value, "Timestamp"))
                    k = t.range.last + 1
                }
                if (k < inner.length) sb.append(run(inner.substring(k), "Timestamp"))
                sb.append(run("]", "Timestamp"))
                at = m.range.last + 1
            }
            if (at < text.length) sb.append(run(text.substring(at)))
            return if (cites) bookmark(name, "") + sb else sb.toString()
        }

        d.title?.trim()?.takeIf { it.isNotEmpty() }?.let { para("Title", run(it)) }
        d.meta.filter { it.isNotBlank() }.forEach { para("Meta", run(it)) }
        d.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { sm ->
            para("Heading1", run(d.summaryHeading))
            ExportNotes.paragraphs(sm).flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { para(null, cited(it)) }
        }
        d.actions?.let { sec ->
            para("Heading1", run(sec.heading))
            for (g in sec.groups) {
                g.label?.let { para("Heading2", run(it)) }
                for (i in g.items) para("ListBullet", run("•") + "<w:r><w:tab/></w:r>" + cited(ExportNotes.line(i)))
            }
        }
        para("Heading1", run(d.transcriptHeading))
        val pad = TranscriptExport.gutterWidth(d.utterances)
        var turn = -1
        for ((u, text, cont) in turns) {
            val body = StringBuilder()
            if (cont) body.append(run(text))
            else {
                turn++
                body.append(bookmark(turnAnchor(turn), run(TranscriptExport.clock(u.startSec, pad), "Timestamp")))
                body.append("<w:r><w:tab/></w:r>")
                u.speaker?.let { body.append(run(d.label(it) + ": ", "Speaker")) }
                body.append(run(text))
                firstCite[turn]?.let { body.append(run("  ")).append(link(it, d.backLabel, "BackLink")) }
            }
            para(if (cont) "TurnCont" else "Turn", body.toString())
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>""" +
            b + """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1080" w:right="1080" w:bottom="1080" w:left="1080" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr></w:body></w:document>"""
    }

    private fun esc(s: String) = buildString {
        for (c in s) when {
            c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;"); c == '"' -> append("&quot;")
            c.code < 0x20 && c != '\t' -> {}   // invalid in XML 1.0
            c == '⁠' -> {}                 // word joiner: Word shows it as a box in some fonts
            else -> append(c)
        }
    }

    internal fun styles(zh: String?): String {
        val ea = if (zh == "zh-CN") "PingFang SC" else "Microsoft JhengHei"
        val lang = zh?.let { " w:eastAsia=\"$it\"" } ?: ""
        val fonts = "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"$ea\" w:cs=\"Calibri\" w:hint=\"${if (zh != null) "eastAsia" else "default"}\"/>"
        fun pStyle(id: String, name: String, ppr: String, rpr: String, next: String = "Normal") =
            """<w:style w:type="paragraph" w:customStyle="${if (id in BUILTIN) 0 else 1}" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="$next"/><w:qFormat/><w:pPr>$ppr</w:pPr><w:rPr>$rpr</w:rPr></w:style>"""
        fun cStyle(id: String, name: String, rpr: String) =
            """<w:style w:type="character" w:customStyle="${if (id in BUILTIN) 0 else 1}" w:styleId="$id"><w:name w:val="$name"/><w:rPr>$rpr</w:rPr></w:style>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr>$fonts<w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="en-US"$lang/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:rPr><w:color w:val="1F242E"/></w:rPr></w:style>""" +
            pStyle("Title", "Title", "<w:spacing w:after=\"80\"/>", "<w:b/><w:sz w:val=\"40\"/><w:szCs w:val=\"40\"/>") +
            pStyle("Meta", "Meta", "<w:spacing w:after=\"20\"/>", "<w:color w:val=\"5A6982\"/><w:sz w:val=\"19\"/>") +
            pStyle("Heading1", "heading 1", "<w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/>", "<w:b/><w:color w:val=\"1D4E89\"/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/>") +
            pStyle("Heading2", "heading 2", "<w:keepNext/><w:spacing w:before=\"160\" w:after=\"60\"/><w:outlineLvl w:val=\"1\"/>", "<w:b/><w:sz w:val=\"22\"/>") +
            pStyle("ListBullet", "List Bullet", "<w:tabs><w:tab w:val=\"left\" w:pos=\"360\"/></w:tabs><w:ind w:left=\"360\" w:hanging=\"240\"/><w:spacing w:after=\"60\"/>", "") +
            pStyle("Turn", "Turn", "<w:tabs><w:tab w:val=\"left\" w:pos=\"1000\"/></w:tabs><w:ind w:left=\"1000\" w:hanging=\"1000\"/><w:spacing w:before=\"120\" w:after=\"20\"/>", "") +
            pStyle("TurnCont", "Turn continued", "<w:ind w:left=\"1000\"/><w:spacing w:after=\"20\"/>", "") +
            cStyle("Speaker", "Speaker", "<w:b/>") +
            cStyle("Timestamp", "Timestamp", "<w:color w:val=\"5A6982\"/>") +
            cStyle("Hyperlink", "Hyperlink", "<w:color w:val=\"2A6CB8\"/>") +
            cStyle("BackLink", "Back link", "<w:color w:val=\"2A6CB8\"/><w:sz w:val=\"17\"/>") +
            "</w:styles>"
    }

    private val BUILTIN = setOf("Title", "Heading1", "Heading2", "ListBullet", "Hyperlink")

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>"""
    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/></Relationships>"""
    private const val SETTINGS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:defaultTabStop w:val="720"/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>"""
}
