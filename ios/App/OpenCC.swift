import Foundation

enum ChineseScript: String, CaseIterable { case traditional = "zh-Hant", simplified = "zh-Hans" }

/// Port of Android `OpenCcConverter`: longest-match OpenCC dictionaries (bundled in `opencc/`), staged.
/// Generated text (title, summary, notes) gets the full Taiwan localisation (s2twp / tw2sp); transcripts only
/// the character-level conversion (what was said keeps its words).
final class OpenCC: @unchecked Sendable {
    private let stages: [[String: String]], maxKey: Int
    private init(_ s: [[String: String]]) { stages = s; maxKey = s.flatMap(\.keys).map { $0.unicodeScalars.count }.max() ?? 1 }

    func convert(_ text: String) -> String {
        let sc = Array(text.unicodeScalars)
        if sc.contains(where: { (0x3040...0x30FF).contains($0.value) || (0xAC00...0xD7A3).contains($0.value) || (0x1100...0x11FF).contains($0.value) }) { return text }
        var cur = sc
        for d in stages where !d.isEmpty { cur = Self.apply(cur, d, maxKey) }
        var v = String.UnicodeScalarView(); v.append(contentsOf: cur); return String(v)
    }

    private static func apply(_ t: [Unicode.Scalar], _ d: [String: String], _ maxKey: Int) -> [Unicode.Scalar] {
        var out: [Unicode.Scalar] = []; out.reserveCapacity(t.count)
        var i = 0
        while i < t.count {
            var hit = false
            var len = min(maxKey, t.count - i)
            while len >= 1 {
                var k = String.UnicodeScalarView(); k.append(contentsOf: t[i..<i + len])
                if let r = d[String(k)] { out.append(contentsOf: r.unicodeScalars); i += len; hit = true; break }
                len -= 1
            }
            if !hit { out.append(t[i]); i += 1 }
        }
        return out
    }

    // MARK: dictionaries
    private static func load(_ name: String, reverse: Bool = false, into m: inout [String: String]) {
        guard let url = dictDir.map({ $0.appendingPathComponent(name + ".txt") })
                ?? Bundle.main.url(forResource: name, withExtension: "txt", subdirectory: "opencc"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return }
        for line in s.split(separator: "\n") where !line.hasPrefix("#") {
            guard let tab = line.firstIndex(of: "\t") else { continue }
            let src = String(line[..<tab]), vals = line[line.index(after: tab)...].split(separator: " ").map(String.init)
            if reverse { for v in vals where v != src && m[v] == nil { m[v] = src } }
            else if let f = vals.first, f != src { m[src] = f }
        }
    }
    /// Host tests read the dictionaries from ios/App/Resources/opencc (`VOX_OPENCC_DIR`); the app uses its bundle.
    private static let dictDir: URL? = ProcessInfo.processInfo.environment["VOX_OPENCC_DIR"].map { URL(fileURLWithPath: $0) }
    nonisolated(unsafe) private static var s2tCache: [String: String]?   // only read and set inside memo, under lock
    private static func s2t() -> [String: String] {
        if let c = s2tCache { return c }
        var m: [String: String] = [:]; load("STPhrases", into: &m); load("STCharacters", into: &m)
        for k in s2tDrop { m[k] = nil }
        m.merge(s2tGuard) { _, g in g }
        s2tCache = m; return m
    }
    /// STPhrases maps 了解 (了解到, 不了解, 据了解) to 瞭解, and longest match then reads the particle 了 plus the
    /// next word as that phrase: 討論了解決方案 → 討論瞭解決方案, 說明了解約 → 說明瞭解約, even in Traditional input.
    /// 了解 is a standard Taiwan spelling, so the character mapping (了→了) is right. Same lists as Android.
    static let s2tDrop = ["了解", "了解到", "不了解", "据了解"]
    /// The same cross-word problem for 了如 / 了然 (討論了如何, 做完了然後) and 明了 after a verb ending in 明
    /// (說明了 → 說明瞭): longer identity keys win the longest match; 了如指掌, 一目了然, 明了 keep their entries.
    static let s2tGuard: [String: String] = {
        var g = ["了如何": "了如何", "了然后": "了然後", "了然後": "了然後"]
        let toTrad: [Character: Character] = ["说": "說", "证": "證", "声": "聲", "阐": "闡", "标": "標", "写": "寫", "讲": "講", "点": "點", "聪": "聰"]
        for v in ["說明", "證明", "表明", "聲明", "查明", "闡明", "標明", "註明", "注明", "指明", "寫明", "講明", "點明", "聰明", "精明",
                  "说明", "证明", "声明", "阐明", "标明", "写明", "讲明", "点明", "聪明"] {
            g[v + "了"] = String(v.map { toTrad[$0] ?? $0 }) + "了"
        }
        return g
    }()
    nonisolated(unsafe) private static var cache: [String: OpenCC] = [:]; private static let lock = NSLock()   // cache guarded by lock
    private static func memo(_ k: String, _ b: () -> OpenCC) -> OpenCC { lock.lock(); defer { lock.unlock() }; if let c = cache[k] { return c }; let c = b(); cache[k] = c; return c }

    /// Generated text (summary, title, notes, speaker names).
    static func generated(_ s: ChineseScript) -> OpenCC {
        memo("gen-" + s.rawValue) {
            switch s {
            case .traditional:
                var tw: [String: String] = [:]; load("TWVariants", into: &tw); load("TWVariantsPhrases", into: &tw); load("TWPhrases", into: &tw)
                // Software vocabulary of TWPhrases that mangles meeting prose (擴展性→擴充套件性, 權限→許可權,
                // 類型→型別, 項目→專案); the plain s2t word is ordinary Taiwan Chinese. Same list as Android.
                for k in ["擴展", "權限", "類型", "項目", "對象", "文件", "參數", "實例", "字段", "函數"] { tw[k] = nil }
                return OpenCC([s2t(), tw])
            case .simplified:
                var rev: [String: String] = [:]
                load("TWPhrases", reverse: true, into: &rev); load("TWVariantsPhrases", reverse: true, into: &rev); load("TWVariants", reverse: true, into: &rev)
                for k in ["地點選", "重點選", "景點選", "觀點選", "優點選", "缺點選", "起點選", "終點選", "站點選", "據點選", "時點選", "焦點選", "執行"] { rev[k] = k }
                rev["介面"] = "界面"   // the first TWPhrases line targeting 介面 is 接口 (an API); meeting prose means the UI
                var t2s: [String: String] = [:]; load("TSPhrases", into: &t2s); load("TSCharacters", into: &t2s)
                return OpenCC([rev, t2s])
            }
        }
    }
    /// Character-level Traditional→Simplified table (TSCharacters, first choice, BMP) for search normalization
    /// (Android OpenCcConverter.simplifiedChars): a lookup per UTF-16 unit, no phrase matching — `convert` costs
    /// milliseconds per passage, which made scanning a whole library seconds long. Other units map to themselves.
    static let simplifiedChars: [UInt16] = {
        var t = (0..<65536).map { UInt16($0) }
        guard let url = Bundle.main.url(forResource: "TSCharacters", withExtension: "txt", subdirectory: "opencc"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return t }
        for line in s.split(separator: "\n") where !line.hasPrefix("#") {
            let u = Array(line.utf16)
            guard u.count >= 3, u[1] == 9 else { continue }   // one BMP character, then a tab
            let v = line.split(separator: "\t").dropFirst().first?.split(separator: " ").first.map { Array($0.utf16) } ?? []
            if v.count == 1 { t[Int(u[0])] = v[0] }
        }
        return t
    }()

    /// Transcripts: character-level only.
    static func transcript(_ s: ChineseScript) -> OpenCC {
        memo("tr-" + s.rawValue) {
            switch s {
            case .traditional: var tw: [String: String] = [:]; load("TWVariants", into: &tw); return OpenCC([s2t(), tw])
            case .simplified: var t2s: [String: String] = [:]; load("TSPhrases", into: &t2s); load("TSCharacters", into: &t2s); return OpenCC([t2s])
            }
        }
    }
}

/// Per-run converter for the chosen script: transcript utterances (cached by text, so only the live tail costs)
/// and generated text (title, summary, notes).
final class TextConv: @unchecked Sendable {
    private let tr: OpenCC, gen: OpenCC; private var cache: [String: String] = [:]; private let lock = NSLock()
    init(_ s: ChineseScript = AppLanguage.current.script) { tr = .transcript(s); gen = .generated(s) }
    func utterances(_ us: [Utterance]) -> [Utterance] {
        lock.lock(); defer { lock.unlock() }
        return us.map { var u = $0; if let c = cache[u.text] { u.text = c } else { let c = tr.convert(u.text); cache[u.text] = c; u.text = c }; return u }
    }
    func notes(_ ns: [Note]) -> [Note] { ns.map { var n = $0; n.text = gen.convert(n.text); return n } }
    func text(_ s: String) -> String { gen.convert(s) }
}
