import Foundation

/// The app's OpenCC converters over ios/App/Resources/opencc (VOX_OPENCC_DIR): STPhrases' 了解→瞭解 must not
/// swallow the particle 了 plus the next word (討論了解決方案 → 討論瞭解決方案). Same cases as Android OpenCcLiaojieTest.
enum AppLanguage { case test; static let current = AppLanguage.test; var script: ChineseScript { .traditional } }   // OpenCC.swift's TextConv default

@main struct OpenCCTests {
    static func main() {
        var fails = 0
        func eq(_ got: String, _ want: String, _ what: String) { if got != want { fails += 1; print("FAIL \(what): \(got) != \(want)") } }
        let cases = [
            ("会议讨论了解决方案", "會議討論了解決方案"), ("说明了解约流程", "說明了解約流程"),
            ("會議討論了解決方案", "會議討論了解決方案"), ("說明了解約流程", "說明了解約流程"),
            ("明瞭", "明瞭"), ("一目瞭然", "一目瞭然"), ("明了", "明瞭"), ("一目了然", "一目瞭然"),
            ("我们需要了解客户", "我們需要了解客戶"), ("据了解", "據了解"), ("讨论了如何推进", "討論了如何推進"),
            ("做完了然后再说", "做完了然後再說"), ("了如指掌", "瞭如指掌"), ("这证明了解法可行", "這證明了解法可行"),
        ]
        for (i, o) in cases {
            eq(OpenCC.generated(.traditional).convert(i), o, "s2twp \(i)")
            eq(OpenCC.transcript(.traditional).convert(i), o, "s2tw \(i)")
        }
        let s = OpenCC.generated(.simplified)
        eq(s.convert("會議討論了解決方案"), "会议讨论了解决方案", "tw2sp")
        eq(s.convert("說明了解約流程"), "说明了解约流程", "tw2sp")
        eq(s.convert("我們需要瞭解客戶"), "我们需要了解客户", "tw2sp")
        eq(s.convert("一目瞭然"), "一目了然", "tw2sp")
        print(fails == 0 ? "opencc: \(cases.count * 2 + 4) checks PASS" : "opencc: \(fails) FAIL"); exit(fails == 0 ? 0 : 1)
    }
}
