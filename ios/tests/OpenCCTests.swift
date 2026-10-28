import Foundation

/// The app's OpenCC converters over ios/App/Resources/opencc (VOX_OPENCC_DIR): STPhrases' 了解→瞭解 must not
/// swallow the particle 了 plus the next word (討論了解決方案 → 討論瞭解決方案). Same cases as Android OpenCcLiaojieTest,
/// plus the shared bench (golden/opencc_bench.json = Android app/src/test/resources/opencc_bench.json, OpenCcBenchTest).
enum AppLanguage { case test; static let current = AppLanguage.test; var script: ChineseScript { .traditional } }   // OpenCC.swift's TextConv default

@main struct OpenCCTests {
    static func main() {
        var fails = 0
        func eq(_ got: String, _ want: String, _ what: String) { if got != want { fails += 1; print("FAIL \(what): \(got) != \(want)") } }
        let cases = [
            ("会议讨论了解决方案", "會議討論了解決方案"), ("说明了解约流程", "說明了解約流程"),
            ("會議討論了解決方案", "會議討論了解決方案"), ("說明了解約流程", "說明了解約流程"),
            ("明瞭", "明瞭"), ("一目瞭然", "一目瞭然"), ("这很明了", "這很明瞭"), ("这一目了然", "這一目瞭然"),
            ("明了", "明了"), ("一目了然", "一目了然"),   // span gating: no character the S→T table changes, left alone
            ("我们需要了解客户", "我們需要了解客戶"), ("据了解", "據了解"), ("讨论了如何推进", "討論了如何推進"),
            ("做完了然后再说", "做完了然後再說"), ("了如指掌", "了如指掌"), ("他对这了如指掌", "他對這瞭如指掌"), ("这证明了解法可行", "這證明了解法可行"),
        ]
        for (i, o) in cases {
            eq(OpenCC.generated(.traditional).convert(i), o, "s2twp \(i)")
            eq(OpenCC.transcript(.traditional).convert(i), o, "s2tw \(i)")
        }
        let s = OpenCC.generated(.simplified)
        eq(s.convert("會議討論了解決方案"), "会议讨论了解决方案", "tw2sp")
        eq(s.convert("說明了解約流程"), "说明了解约流程", "tw2sp")
        eq(s.convert("我們需要瞭解客戶"), "我们需要了解客户", "tw2sp")
        eq(s.convert("這一目瞭然"), "这一目了然", "tw2sp")
        eq(s.convert("一目瞭然"), "一目瞭然", "tw2sp gated")
        var n = cases.count * 2 + 5
        // Shared bench: converter, input, expected.
        let dir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "golden"
        let data = try! Data(contentsOf: URL(fileURLWithPath: dir + "/opencc_bench.json"))
        let rows = (try! JSONSerialization.jsonObject(with: data) as! [String: Any])["cases"] as! [[String]]
        let conv: [String: OpenCC] = ["generated-traditional": .generated(.traditional), "transcript-traditional": .transcript(.traditional),
                                      "generated-simplified": .generated(.simplified), "transcript-simplified": .transcript(.simplified)]
        for r in rows { eq(conv[r[0]]!.convert(r[1]), r[2], "bench \(r[0]) \(r[1])") }
        n += rows.count
        // Target-script text passes through untouched.
        let trad = "我們討論了解決方案，他說明了原因。我們不應該干涉他們；皇后出席了典禮！距離只有五公里 文件 權限 數據 項目"
        eq(OpenCC.generated(.traditional).convert(trad), trad, "trad passthrough"); eq(OpenCC.transcript(.traditional).convert(trad), trad, "trad passthrough tr")
        let simp = "我们讨论了解决方案，乾坤未定。这个文件的权限"
        eq(s.convert(simp), simp, "simp passthrough"); eq(OpenCC.transcript(.simplified).convert(simp), simp, "simp passthrough tr")
        n += 4
        print(fails == 0 ? "opencc: \(n) checks PASS" : "opencc: \(fails) FAIL"); exit(fails == 0 ? 0 : 1)
    }
}
