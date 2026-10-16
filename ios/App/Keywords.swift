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

    /// BM25 of each document for `query`; 0 for a document sharing no term with it.
    static func bm25(_ query: String, _ docs: [String]) -> [Float] {
        let q = Set(terms(query))
        guard !q.isEmpty, !docs.isEmpty else { return Array(repeating: 0, count: docs.count) }
        let tf: [(Int, [String: Int])] = docs.map { d in
            let ts = terms(d); var c: [String: Int] = [:]
            for t in ts where q.contains(t) { c[t, default: 0] += 1 }
            return (ts.count, c)
        }
        let avg = max(Double(tf.reduce(0) { $0 + $1.0 }) / Double(docs.count), 1)
        var df: [String: Int] = [:]
        for (_, c) in tf { for t in c.keys { df[t, default: 0] += 1 } }
        let n = Double(docs.count)
        return tf.map { len, counts in
            Float(counts.reduce(0.0) { acc, e in
                let d = Double(df[e.key]!), f = Double(e.value)
                let idf = log(1 + (n - d + 0.5) / (d + 0.5))
                return acc + idf * f * (k1 + 1) / (f + k1 * (1 - b + b * Double(len) / avg))
            })
        }
    }
}
