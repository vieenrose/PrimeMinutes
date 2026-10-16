import Foundation

/// Keyword scoring for Ask's lexical pass (Android Keywords): BM25 over terms that work without word
/// segmentation — character bigrams inside runs of CJK characters (a lone character stands for itself),
/// and words or numbers elsewhere. The caller normalizes case and script first (both sides the same way).
enum Keywords {
    private static let k1 = 1.2, b = 0.75

    /// Han (unified, extension A, compatibility), kana and Hangul — the same ranges as Android.
    private static func cjk(_ u: UInt16) -> Bool {
        (0x4E00...0x9FFF).contains(u) || (0x3400...0x4DBF).contains(u) || (0xF900...0xFAFF).contains(u) ||
            (0x3040...0x30FF).contains(u) || (0xAC00...0xD7AF).contains(u) || (0x1100...0x11FF).contains(u) || (0x3130...0x318F).contains(u)
    }
    private static func alnum(_ u: UInt16) -> Bool {
        guard let s = Unicode.Scalar(u) else { return false }
        return s.properties.isAlphabetic || s.properties.numericType != nil
    }

    /// UTF-16 units, as Android iterates its Chars (a surrogate pair is neither CJK nor a word).
    static func terms(_ text: String) -> [String] {
        let u = Array(text.utf16); var out: [String] = []; var i = 0
        func str(_ a: Int, _ b: Int) -> String { String(decoding: u[a..<b], as: UTF16.self) }
        while i < u.count {
            if cjk(u[i]) {
                var j = i; while j < u.count && cjk(u[j]) { j += 1 }
                if j - i == 1 { out.append(str(i, j)) } else { for k in i..<(j - 1) { out.append(str(k, k + 2)) } }
                i = j
            } else if alnum(u[i]) {
                var j = i; while j < u.count && alnum(u[j]) && !cjk(u[j]) { j += 1 }
                out.append(str(i, j)); i = j
            } else { i += 1 }
        }
        return out
    }

    /// Lower case and the `table` script (OpenCC.simplifiedChars), one UTF-16 unit at a time.
    static func fold(_ text: String, _ table: [UInt16]) -> String {
        String(decoding: text.lowercased().utf16.map { table[Int($0)] }, as: UTF16.self)
    }

    /// A term as an Int: a CJK bigram packs its two characters (exact), anything else is hashed (Android Keywords.code).
    static func code(_ term: String) -> Int32 {
        let u = Array(term.utf16)
        if u.count == 2 && cjk(u[0]) && cjk(u[1]) { return Int32(bitPattern: UInt32(u[0]) << 16 | UInt32(u[1])) }
        var h: Int32 = 0
        for c in u { h = h &* 31 &+ Int32(c) }   // Java String.hashCode
        return h
    }

    /// A passage reduced to what BM25 needs: its term count and its term codes, sorted.
    struct Doc {
        let length: Int, codes: [Int32]
        func tf(_ c: Int32) -> Int {
            var lo = 0, hi = codes.count
            while lo < hi { let m = (lo + hi) / 2; if codes[m] < c { lo = m + 1 } else { hi = m } }
            var n = 0; while lo + n < codes.count && codes[lo + n] == c { n += 1 }
            return n
        }
    }

    static func doc(_ text: String) -> Doc { let ts = terms(text); return Doc(length: ts.count, codes: ts.map(code).sorted()) }

    /// BM25 of each document for `query`; 0 for a document sharing no term with it.
    static func bm25(_ query: String, _ docs: [String]) -> [Float] { score(query, docs.map(doc)) }

    static func score(_ query: String, _ docs: [Doc]) -> [Float] {
        var seen = Set<Int32>(); let q = terms(query).map(code).filter { seen.insert($0).inserted }
        guard !q.isEmpty, !docs.isEmpty else { return Array(repeating: 0, count: docs.count) }
        let tf = docs.map { d in q.map { d.tf($0) } }
        let avg = max(Double(docs.reduce(0) { $0 + $1.length }) / Double(docs.count), 1)
        let df = q.indices.map { t in tf.filter { $0[t] > 0 }.count }
        let n = Double(docs.count)
        return docs.indices.map { d in
            var s = 0.0
            for t in q.indices where tf[d][t] > 0 {
                let f = Double(tf[d][t]), dft = Double(df[t])
                let idf = log(1 + (n - dft + 0.5) / (dft + 0.5))
                s += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * Double(docs[d].length) / avg))
            }
            return Float(s)
        }
    }
}
