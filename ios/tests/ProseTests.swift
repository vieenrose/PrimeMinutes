// macOS host test: the segmented prose summary (same cases as Android SegmentedProseTest and ModelCardTest;
// the parts golden is shared with it: app/src/test/resources/prose_parts.json = golden/prose_parts.json).
import Foundation

/// Replies in order; records each prompt; a token per character.
final class Replies: ReaderLlm {
    var replies: [String]; var prompts: [String] = []; private var seq = ""
    init(_ r: [String]) { replies = r }
    var template: ChatTemplate { ModelCard.default.template }
    func tokenize(_ text: String, special: Bool) -> [Int] { seq += text; return Array(repeating: 7, count: text.count) }
    func append(_ tokens: [Int]) -> Int { tokens.count }
    func generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Void) -> String {
        prompts.append(seq); seq = ""; return replies.removeFirst()
    }
    var seqLength: Int { 0 }
    func reset() { seq = "" }
}

@main struct ProseTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }
        let dir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "golden"

        // Shared golden: the same parts as Android for the same sizes and card numbers.
        let g = try! JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: "\(dir)/prose_parts.json"))) as! [String: Any]
        for (i, c) in (g["cases"] as! [[String: Any]]).enumerated() {
            let want = (c["parts"] as! [[Int]]).map { "\($0[0])...\($0[1])" }
            let got = ReaderSummarizer.proseParts(c["sizes"] as! [Int], segmentTokens: c["segment_tokens"] as! Int,
                                                  segmentMax: c["segment_max"] as! Int, segmentAbove: c["segment_above"] as! Int).map { "\($0.lowerBound)...\($0.upperBound)" }
            check(got, want, "golden case \(i)")
        }
        check(ReaderSummarizer.proseParts(Array(repeating: 30, count: 66), segmentTokens: 600, segmentMax: 4, segmentAbove: 2000).count, 1, "1980 tokens: one reply")
        check(ReaderSummarizer.proseParts(Array(repeating: 30, count: 67), segmentTokens: 600, segmentMax: 4, segmentAbove: 2000).count, 4, "2010 tokens: 4 parts")

        // Shipped cards: one reply of 1600 up to a 2000-token journal, then parts of ~600 (at most 4), a segment prompt per language.
        for name in ["gemma4-meeting-agent-zh", "gemma4-meeting-agent-zh-v2"] {
            let card = ModelCard.load(name)
            for lang in ["zh", "en"] {
                let p = card.forLanguage(lang).prose
                check([p.maxTokens, p.segmentAbove, p.segmentTokens, p.segmentMax], [1600, 2000, 600, 4], "\(name) \(lang) numbers")
                check(p.segmentPrompt.map { $0.contains("{notes}") && $0.contains("{from}") && $0.contains("{to}") }, true, "\(name) \(lang) segment prompt")
            }
            check(card.forLanguage("en").prose.renderSegment("n", from: "1:00", to: "2:00").hasPrefix("Here are the notes of a meeting from 1:00 to 2:00:\n\nn\n\n"), true, "\(name) en segment prompt in English")
            check(card.prose.renderSegment("n", from: "1:00", to: "2:00").hasPrefix("以下是一場會議中 1:00 到 2:00 這段時間的筆記：\n\nn\n\n"), true, "\(name) zh segment prompt")
        }
        check(ModelCard.load("gemma4-meeting-agent-zh-v2").forLanguage("en").prose.prompt, ModelCard.default.forLanguage("en").prose.prompt, "v2 = v1 prose")

        // A long journal is written in parts joined as paragraphs; the later parts read the segment prompt.
        let journal = (1...6).map { Note(id: $0, window: 1, ts: "\($0):00", tag: "DECISION", text: "決定第\($0)件事") }
        var card = ModelCard.default
        card.prose = ModelCard.OneShot(prompt: card.prose.prompt, maxTokens: 1600, label: nil, segmentTokens: 84, segmentMax: 4,
                                       segmentPrompt: "PART {from}-{to}\n{notes}", segmentAbove: 0)
        let llm = Replies(["第一段的摘要內容寫得已經夠長了 [1:00]。<turn|>", "第二段的摘要內容也寫得已經夠長 [4:00]。<turn|>"])
        check(ReaderSummarizer(llm: llm, card: card).prose(journal), "第一段的摘要內容寫得已經夠長了 [1:00]。\n\n第二段的摘要內容也寫得已經夠長 [4:00]。", "two parts")
        check(llm.prompts.count, 2, "two replies")
        check(llm.prompts.first.map { $0.contains("以下是一場會議的筆記") && $0.contains("決定第1件事") && !$0.contains("決定第4件事") }, true, "first part: the card's prompt")
        check(llm.prompts.last.map { $0.contains("PART 4:00-6:00") && $0.contains("決定第6件事") && !$0.contains("決定第3件事") }, true, "next part: the segment prompt")
        // Under the threshold: one reply.
        card.prose.segmentAbove = 10_000
        let one = Replies(["整場會議的摘要內容寫得已經夠長了 [1:00]。<turn|>"])
        check(ReaderSummarizer(llm: one, card: card).prose(journal), "整場會議的摘要內容寫得已經夠長了 [1:00]。", "one reply")
        check(one.prompts.count, 1, "one prompt")

        if failures > 0 { print("prose tests: \(failures) failure(s)"); exit(1) }
        print("prose tests: ok")
    }
}
