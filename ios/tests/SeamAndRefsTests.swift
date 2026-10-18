import Foundation

// Android SeamStitcherTest (repeated head at a resume seam) and SpeakerRefsTest (resolveCited).
@main struct SeamAndRefsTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }
    static func u(_ t: String, _ s: Double, _ e: Double, _ spk: Int, _ times: [Double]? = nil) -> Utterance {
        Utterance(speaker: spk, start: s, end: e, text: t, tokenTimes: times)
    }

    static func main() {
        // Seen on the Android emulator (move meeting killed at 268 s): the resumed straddling line restarts with "很好，客戶".
        let prior = [u("三月十號當天早上生效。很好，客戶", 262, 268, 0)]
        let fresh = [u("三月十號當天早上生效。", 262, 266.5, 0), u("很好，客戶通知信，我希望二月底前寄出", 266.5, 275, 1)]
        let (all, _) = SeamStitcher.stitch(prior, seam: 268, fresh, stable: 2)
        check(all.map(\.text) == ["三月十號當天早上生效。很好，客戶", "通知信，我希望二月底前寄出"], "seam repeat dropped: \(all.map(\.text))")

        let g = SeamStitcher.trimRepeatedHead("we'll see you", u("see you on Thursday", 10, 12, 0, [0, 0.3, 0.6, 0.9]))
        check(g.text == "on Thursday", "latin trim: \(g.text)")
        check(abs(g.start - 10.6) < 1e-9 && g.tokenTimes.map { $0.map { ($0 * 10).rounded() / 10 } } == [0, 0.3], "token times kept: \(g.start) \(String(describing: g.tokenTimes))")
        let h = SeamStitcher.trimRepeatedHead("很好，", u("很好，客戶", 1, 2, 0, [0, 0.1, 0.2, 0.3]))
        check(h.text == "客戶" && h.tokenTimes.map { $0.map { ($0 * 10).rounded() / 10 } } == [0, 0.1], "han times: \(String(describing: h.tokenTimes))")
        let p = u("on the table", 1, 2, 0)
        check(SeamStitcher.trimRepeatedHead("put it upon", p) == p, "partial latin word is no repeat")
        let n = u("通知信", 1, 2, 0)
        check(SeamStitcher.trimRepeatedHead("生效。", n) == n, "no repeat untouched")

        let cite = try! NSRegularExpression(pattern: #"\[\[([A-Za-z0-9_\-]+)@(\d+)\]\]"#)
        let zh = { (i: Int) in "語者 \(i + 1)" }
        let labels: [String: SpeakerRefs.Labels] = ["a": .init(label: zh, known: [0, 1]),
                                                    "b": .init(label: { $0 == 0 ? "Alice" : "語者 \($0 + 1)" }, known: [0, 1, 2])]
        let key = { (t: String, m: NSTextCheckingResult) in (t as NSString).substring(with: m.range(at: 1)) }
        let t1 = "費用一萬二 [[a@239]]，由 S1 負責聯絡 [[b@400]]，S3 同意。"
        let r1 = SpeakerRefs.resolveCited(t1, cite: cite) { labels[key(t1, $0)] }
        check(r1 == "費用一萬二 [[a@239]]，由 Alice 負責聯絡 [[b@400]]，語者 3 同意。", "cited: \(r1)")
        check(SpeakerRefs.resolveCited("S1 說好", cite: cite) { _ in .init(label: zh, known: nil) } == "S1 說好", "uncited left")
        check(SpeakerRefs.resolveCited("S1 說好 [[x@1]]", cite: cite) { _ in nil } == "S1 說好 [[x@1]]", "unknown meeting left")
        print("seam+refs tests: OK")
    }
}
