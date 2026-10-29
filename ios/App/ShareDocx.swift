import Foundation

/// The summary + transcript as Word (.docx), written by hand (share v2 addendum): WordprocessingML in a stored zip,
/// no library. Same content and order as the PDF; every cite is a `w:hyperlink w:anchor` to the bookmark of its
/// transcript turn, every cited turn links back ("↑ …") to the first cite of it, else to the summary.
/// Foundation only: the app builds the `Doc`, the host tests check the XML and the zip.
enum ShareDocx {
    struct Turn { var clock: String; var speaker: String; var text: String; var sec: Int; var cont: Bool }
    struct Group { var label: String?; var items: [String] }
    struct Section { var heading: String; var groups: [Group] }
    struct Doc {
        var title: String
        var meta: [String]
        var summaryHeading: String
        var summary: [String]
        var sections: [Section]       // the action items (the AI notes stay in the .pmin)
        var transcriptHeading: String
        var turns: [Turn]
        var backLabel: String         // "摘要" → "↑ 摘要"
        var zh: Bool
        var simplified: Bool
    }

    static func esc(_ s: String) -> String {
        var o = ""
        for c in s.unicodeScalars {
            switch c {
            case "&": o += "&amp;"; case "<": o += "&lt;"; case ">": o += "&gt;"; case "\"": o += "&quot;"
            default: if c.value < 0x20 && c != "\t" { continue }; o.unicodeScalars.append(c)
            }
        }
        return o
    }
    static func bookmark(_ dest: String) -> String { dest.replacingOccurrences(of: "-", with: "_") }   // t-65 → t_65

    private static func run(_ t: String, style: String? = nil) -> String {
        let rpr = style.map { "<w:rPr><w:rStyle w:val=\"\($0)\"/></w:rPr>" } ?? ""
        return "<w:r>\(rpr)<w:t xml:space=\"preserve\">\(esc(t))</w:t></w:r>"
    }
    private static func para(_ style: String?, _ body: String, extra: String = "") -> String {
        let ppr = style.map { "<w:pPr><w:pStyle w:val=\"\($0)\"/>\(extra)</w:pPr>" } ?? (extra.isEmpty ? "" : "<w:pPr>\(extra)</w:pPr>")
        return "<w:p>\(ppr)\(body)</w:p>"
    }

    /// The document body and the ids it uses. Cites are numbered c_1… in reading order.
    static func documentXML(_ d: Doc) -> String {
        let starts = d.turns.filter { !$0.cont }.map(\.sec)
        var bm = 0, citeN = 0, citeTargets: [(cite: Int, turnSec: Int)] = []
        func mark(_ name: String, _ inner: String) -> String {
            bm += 1
            return "<w:bookmarkStart w:id=\"\(bm)\" w:name=\"\(bookmark(name))\"/>\(inner)<w:bookmarkEnd w:id=\"\(bm)\"/>"
        }
        /// Text with its cites as hyperlinks (one per time) and a bookmark on each cite.
        func cited(_ t: String) -> String {
            let ns = t as NSString; var out = "", at = 0
            for m in NoteCites.tsRe.matches(in: t, range: NSRange(location: 0, length: ns.length)) {
                out += run(ns.substring(with: NSRange(location: at, length: m.range.location - at)))
                at = m.range.location + m.range.length
                citeN += 1
                var inner = run("[", style: "Cite"), first = true
                let times = ShareCore.citeTimes(ns.substring(with: m.range(at: 1)))
                for tm in times {
                    if !first { inner += run(", ", style: "Cite") }; first = false
                    if let k = ShareCore.target(ShareCore.seconds(tm), starts) {
                        citeTargets.append((citeN, starts[k]))
                        inner += "<w:hyperlink w:anchor=\"\(bookmark(ShareCore.turnDest(starts[k])))\" w:history=\"1\">\(run(tm, style: "Cite"))</w:hyperlink>"
                    } else { inner += run(tm, style: "Cite") }
                }
                inner += run("]", style: "Cite")
                out += mark("c-\(citeN)", inner)
            }
            return out + run(ns.substring(from: at))
        }
        var b = para("Title", d.summary.isEmpty ? mark(ShareCore.summaryDest, run(d.title)) : run(d.title))
        for m in d.meta { b += para("Meta", run(m)) }
        if !d.summary.isEmpty {
            b += para("Heading1", mark(ShareCore.summaryDest, run(d.summaryHeading)))
            for p in d.summary { b += para(nil, cited(p)) }
        }
        for s in d.sections {
            b += para("Heading1", run(s.heading))
            for g in s.groups {
                if let l = g.label { b += para("Heading2", run(l)) }
                for i in g.items { b += para("ListBullet", run("•\t") + cited(i)) }
            }
        }
        if !d.turns.isEmpty {
            b += para("Heading1", run(d.transcriptHeading))
            var named = Set<Int>()
            for t in d.turns {
                if t.cont { b += para("Turn", run("\t") + run(t.text)); continue }
                var body = run(t.clock, style: "Clock") + run("\t") + run(t.speaker, style: "Speaker") + run("  ") + run(t.text)
                if let back = citeTargets.first(where: { $0.turnSec == t.sec }) {
                    body += run("  ") + "<w:hyperlink w:anchor=\"c_\(back.cite)\" w:history=\"1\">\(run("↑ " + d.backLabel, style: "Back"))</w:hyperlink>"
                }
                b += para("Turn", named.insert(t.sec).inserted ? mark(ShareCore.turnDest(t.sec), body) : body, extra: "<w:spacing w:before=\"120\"/>")
            }
        }
        let sect = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1240\" w:right=\"1160\" w:bottom=\"1240\" w:left=\"1160\" w:header=\"600\" w:footer=\"600\" w:gutter=\"0\"/></w:sectPr>"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>\(b)\(sect)</w:body></w:document>"
    }

    static func stylesXML(zh: Bool, simplified: Bool) -> String {
        let ea = simplified ? "PingFang SC" : "PingFang TC", eaWin = simplified ? "Microsoft YaHei" : "Microsoft JhengHei"
        let lang = zh ? (simplified ? "zh-CN" : "zh-TW") : "en-US"
        // eastAsia: the Windows font first (Word), the Apple one as the theme-less fallback through altName.
        let fonts = "<w:rFonts w:ascii=\"Helvetica Neue\" w:hAnsi=\"Helvetica Neue\" w:eastAsia=\"\(eaWin)\" w:cs=\"Arial\"/>"
        func style(_ id: String, _ name: String, type: String = "paragraph", based: String? = nil, ppr: String = "", rpr: String = "") -> String {
            "<w:style w:type=\"\(type)\" w:styleId=\"\(id)\"><w:name w:val=\"\(name)\"/>\(based.map { "<w:basedOn w:val=\"\($0)\"/>" } ?? "")<w:qFormat/>\(ppr.isEmpty ? "" : "<w:pPr>\(ppr)</w:pPr>")\(rpr.isEmpty ? "" : "<w:rPr>\(rpr)</w:rPr>")</w:style>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr>\(fonts)<w:sz w:val=\"22\"/><w:lang w:val=\"\(zh ? "en-US" : lang)\" w:eastAsia=\"\(zh ? lang : "zh-TW")\"/></w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/><w:rPr><w:color w:val=\"1C2430\"/></w:rPr></w:style>"
            + style("Title", "Title", based: "Normal", ppr: "<w:spacing w:after=\"120\"/>", rpr: "<w:b/><w:sz w:val=\"40\"/>")
            + style("Meta", "Meta", based: "Normal", ppr: "<w:spacing w:after=\"40\"/>", rpr: "<w:color w:val=\"5A6982\"/><w:sz w:val=\"20\"/>")
            + style("Heading1", "heading 1", based: "Normal", ppr: "<w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"4\" w:color=\"D1D1D1\"/></w:pBdr><w:outlineLvl w:val=\"0\"/>", rpr: "<w:b/><w:color w:val=\"1F5FAE\"/><w:sz w:val=\"28\"/>")
            + style("Heading2", "heading 2", based: "Normal", ppr: "<w:keepNext/><w:spacing w:before=\"160\" w:after=\"60\"/><w:outlineLvl w:val=\"1\"/>", rpr: "<w:b/><w:sz w:val=\"22\"/>")
            + style("ListBullet", "List Bullet", based: "Normal", ppr: "<w:tabs><w:tab w:val=\"left\" w:pos=\"360\"/></w:tabs><w:ind w:left=\"360\" w:hanging=\"360\"/><w:spacing w:after=\"60\"/>")
            + style("Turn", "Transcript Turn", based: "Normal", ppr: "<w:tabs><w:tab w:val=\"left\" w:pos=\"900\"/></w:tabs><w:ind w:left=\"900\" w:hanging=\"900\"/><w:spacing w:after=\"40\"/>")
            + style("Speaker", "Speaker", type: "character", rpr: "<w:b/>")
            + style("Clock", "Timestamp", type: "character", rpr: "<w:rFonts w:ascii=\"Menlo\" w:hAnsi=\"Menlo\"/><w:color w:val=\"5A6982\"/><w:sz w:val=\"18\"/>")
            + style("Cite", "Citation", type: "character", rpr: "<w:color w:val=\"1F5FAE\"/><w:sz w:val=\"19\"/>")
            + style("Back", "Back Link", type: "character", rpr: "<w:color w:val=\"1F5FAE\"/><w:sz w:val=\"16\"/>")
            + "<!-- \(ea) on Apple devices --></w:styles>"
    }

    static let contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>"
    static let rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
    static let docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"

    static func data(_ d: Doc) -> Data {
        Zip.stored([("[Content_Types].xml", Data(contentTypes.utf8)), ("_rels/.rels", Data(rels.utf8)),
                    ("word/document.xml", Data(documentXML(d).utf8)), ("word/_rels/document.xml.rels", Data(docRels.utf8)),
                    ("word/styles.xml", Data(stylesXML(zh: d.zh, simplified: d.simplified).utf8))])
    }
}

/// A minimal zip writer: stored entries (no compression), CRC-32, UTF-8 names. Enough for a .docx.
enum Zip {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i); for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }; return c
    }
    static func crc32(_ d: Data) -> UInt32 { ~d.reduce(UInt32.max) { table[Int(($0 ^ UInt32($1)) & 0xFF)] ^ ($0 >> 8) } }
    static func stored(_ files: [(String, Data)]) -> Data {
        var out = Data(), central = Data()
        func u16(_ v: Int, _ d: inout Data) { var x = UInt16(v).littleEndian; d.append(Data(bytes: &x, count: 2)) }
        func u32(_ v: UInt32, _ d: inout Data) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 4)) }
        for (name, body) in files {
            let n = Data(name.utf8), crc = crc32(body), off = UInt32(out.count)
            u32(0x04034b50, &out); u16(20, &out); u16(0x0800, &out); u16(0, &out); u16(0, &out); u16(0x21, &out)
            u32(crc, &out); u32(UInt32(body.count), &out); u32(UInt32(body.count), &out); u16(n.count, &out); u16(0, &out)
            out.append(n); out.append(body)
            u32(0x02014b50, &central); u16(20, &central); u16(20, &central); u16(0x0800, &central); u16(0, &central); u16(0, &central); u16(0x21, &central)
            u32(crc, &central); u32(UInt32(body.count), &central); u32(UInt32(body.count), &central); u16(n.count, &central)
            u16(0, &central); u16(0, &central); u16(0, &central); u16(0, &central); u32(0, &central); u32(off, &central); central.append(n)
        }
        let cdOff = UInt32(out.count); out.append(central)
        u32(0x06054b50, &out); u16(0, &out); u16(0, &out); u16(files.count, &out); u16(files.count, &out)
        u32(UInt32(central.count), &out); u32(cdOff, &out); u16(0, &out)
        return out
    }
}
