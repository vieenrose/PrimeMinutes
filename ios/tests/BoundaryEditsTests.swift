// macOS host test: speaker-boundary edits, token times from the engine, the long-line splitter keeping them
// (Android BoundaryEditsTest / TokenTimesTest parity).
import Foundation

@main struct BoundaryEditsTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }
        func near(_ a: Double, _ b: Double, _ what: String) { if abs(a - b) > 1e-6 { failures += 1; print("FAIL \(what): want \(b) got \(a)") } }
        func u(_ spk: Int, _ a: Double, _ b: Double, _ t: String, _ tt: [Double]? = nil) -> Utterance { Utterance(speaker: spk, start: a, end: b, text: t, tokenTimes: tt) }

        // tokenize: lossless, Han char / Latin word, punctuation attached
        for t in ["好，我們今天討論預算。", "Okay, so the remote is ready.", "我用 iPhone 15 錄音，OK?", "  leading space"] {
            check(BoundaryEdits.tokenize(t).joined(), t, "lossless \(t)")
        }
        check(BoundaryEdits.tokenize("好，我們"), ["好，", "我", "們"], "han tokens")
        check(BoundaryEdits.tokenize("Okay, so the"), ["Okay, ", "so ", "the"], "latin tokens")

        // move: a late boundary moved earlier (English)
        let en = [u(0, 0, 4, "We decided on the budget. Yeah but"), u(1, 4, 6, "what about the case?")]
        let m1 = BoundaryEdits.move(en, seam: 1, split: 5)
        check(m1[0].text, "We decided on the budget.", "A after move"); check(m1[1].text, "Yeah but what about the case?", "B after move")
        near(m1[0].end, m1[1].start, "A.end == B.start"); check(m1.map(\.speaker), [0, 1], "speakers kept")
        let back = BoundaryEdits.move(m1, seam: 1, split: 7)
        check(back.map(\.text), en.map(\.text), "move back restores")
        check(m1.map(\.id), en.map(\.id), "line ids kept (undo compares lines)")

        // Han characters, proportional estimate
        let zh = [u(0, 10, 13, "預算就這樣定了對"), u(1, 13, 15, "可是時間呢")]
        let m2 = BoundaryEdits.move(zh, seam: 1, split: 7)
        check(m2.map(\.text), ["預算就這樣定了", "對可是時間呢"], "han move"); near(m2[1].start, 10 + 3.0 * 7 / 8, "han estimate")
        // at least one token each side
        let m3 = BoundaryEdits.move([u(0, 0, 1, "好的"), u(1, 1, 2, "嗯")], seam: 1, split: 99)
        check(BoundaryEdits.tokenize(m3[1].text).count, 1, "clamped")

        // the engine's token times win over the estimate
        let ta = u(0, 10, 13, "預算定了對", [0, 0.5, 1.0, 1.6, 2.9]), tb = u(1, 13, 14, "可是", [0, 0.4])
        let m4 = BoundaryEdits.move([ta, tb], seam: 1, split: 4)
        check(m4[1].text, "對可是", "timed move"); near(m4[1].start, 12.9, "engine time used")
        check(m4[1].tokenTimes ?? [], [0, 0.1, 0.5], "times re-based"); check(m4[0].tokenTimes ?? [], [0, 0.5, 1.0, 1.6], "A times")
        var stale = ta; stale.text = "預算定了對嗎"
        near(BoundaryEdits.tokenStarts(stale)[5], 10 + 3.0 * 5 / 6, "stale times fall back")

        // split a missed turn
        let sp = BoundaryEdits.split([u(0, 0, 6, "I think so. Sure. Then the buttons"), u(2, 6, 8, "next")], index: 0, at: 3, speaker: 1)
        check(sp.map(\.text), ["I think so.", "Sure. Then the buttons", "next"], "split text"); check(sp.map(\.speaker), [0, 1, 2], "split speaker")
        // candidates: neighbours, others, new
        let cs = [u(3, 0, 1, "a"), u(0, 1, 2, "b c"), u(2, 2, 3, "d"), u(1, 3, 4, "e")]
        check(BoundaryEdits.splitCandidates(cs, index: 1), [2, 3, 1, 4], "candidates")
        check(BoundaryEdits.splitCandidates([u(0, 0, 1, "a"), u(0, 1, 2, "b c")], index: 1), [1], "own speaker never offered")

        // decode: optional 5th field
        let rec = "1\u{1f}12.000\u{1f}14.000\u{1f}大家覺得呢\u{1f}0,100,300,400,700\u{1e}0\u{1f}14.000\u{1f}15.000\u{1f}no times here\u{1e}"
        let d = SegmentCodec.decode(rec)
        check(d.count, 2, "decoded"); check(d[0].tokenTimes ?? [], [0, 0.1, 0.3, 0.4, 0.7], "token times"); check(d[1].tokenTimes == nil, true, "no times")
        // alignment through cleanup
        let raw = "你 好 好 ， 世界 OK"
        let al = TokenTimes.fromCodepoints(raw: raw, starts: (0..<raw.unicodeScalars.count).map { Double($0) * 0.1 }, text: "你好，世界 OK") ?? []
        check(al.map { ($0 * 10).rounded() / 10 }, [0, 0.2, 0.8, 0.9, 1.1], "aligned")
        check(TokenTimes.fromCodepoints(raw: "abc", starts: [0, 0.1, 0.2], text: "完全不同的字") == nil, true, "mismatch refused")

        // long lines keep their token times when cut
        let s1 = String(repeating: "第一句話說得很長很長", count: 10) + "。", s2 = String(repeating: "第二句也是很長的一段話", count: 9) + "。"
        let toks = TokenTimes.tokenize(s1 + s2)
        let long = u(0, 100, 100 + 5 + Double(toks.count) * 0.2, s1 + s2, toks.indices.map { 5 + Double($0) * 0.2 })
        let cut = LongUtteranceSplitter.split([long])
        check(cut.map(\.text), [s1, s2], "cut text")
        near(cut[0].start, 105, "first starts at its first token")
        near(cut[1].start, 105 + Double(TokenTimes.tokenize(s1).count) * 0.2, "second starts at its first token")
        near(cut[0].end, cut[1].start, "contiguous"); check(cut[1].tokenTimes?.count, TokenTimes.tokenize(s2).count, "piece times")

        // the strip shows the text's spacing: "two," + "right?" across the seam is not glued
        let sm = BoundaryEdits.seam(u(0, 0, 2, "we need two,"), u(1, 2, 4, "right? Yes."))
        check(BoundaryEdits.displayTokens(sm.tokens).joined(), "we need two, right? Yes.", "display spacing")
        check(BoundaryEdits.displayTokens(sm.tokens).count, sm.tokens.count, "display keeps tokens")
        check(BoundaryEdits.displayTokens(BoundaryEdits.seam(u(0, 0, 1, "好的"), u(1, 1, 2, "可以")).tokens).joined(), "好的可以", "display Han")

        print(failures == 0 ? "boundary edits: PASS" : "boundary edits: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
