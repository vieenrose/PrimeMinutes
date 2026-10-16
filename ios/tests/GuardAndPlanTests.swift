// macOS host test: download plan + size formatter, the no-speech check and the reader guardrail
// (same cases as Android DownloadPlanTest, SizeFormatTest, SpeechCheckTest, ReaderGuardTest).
import Foundation

final class OneReply: ReaderLlm {
    let reply: String; var n = 0
    init(_ r: String) { reply = r }
    func tokenize(_ text: String, special: Bool) -> [Int] { text.unicodeScalars.map { Int($0.value) } }
    func append(_ tokens: [Int]) -> Int { n += tokens.count; return n }
    func generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Void) -> String { reply + stop }
    var seqLength: Int { n }
    func reset() { n = 0 }
}

@main struct GuardAndPlanTests {
    static func main() {
        var failures = 0
        func check<T: Equatable>(_ a: T, _ b: T, _ what: String) { if a != b { failures += 1; print("FAIL \(what)\n  want: \(b)\n  got:  \(a)") } }

        // SizeFormat
        check(SizeFormat.bytes(2_195_000_000), "2.2 GB", "GB one decimal")
        check(SizeFormat.bytes(3_300_000_000), "3.3 GB", "E4B")
        check(SizeFormat.bytes(270_400_000), "270 MB", "MB rounded")
        check(SizeFormat.bytes(4_200), "4 KB", "KB")
        check(SizeFormat.bytes(0), "0 MB", "zero")

        // DownloadPlan
        let none = DownloadPlan.pending(speechReady: true, speechBytes: 500, readerReady: true, readerBytes: 2000)
        let both = DownloadPlan.pending(speechReady: false, speechBytes: 500, readerReady: false, readerBytes: 2000)
        check(none.any, false, "nothing pending"); check(both.total, 2500, "total")
        check(DownloadPlan.pending(speechReady: true, speechBytes: 500, readerReady: false, readerBytes: 2000).total, 2000, "reader only")
        check(DownloadPlan.decide(.unset, unmetered: true, none), .proceed, "nothing to fetch proceeds")
        check(DownloadPlan.decide(.unset, unmetered: true, both), .ask, "unset asks, even on Wi-Fi")
        check(DownloadPlan.decide(.anyNetwork, unmetered: false, both), .proceed, "any network")
        check(DownloadPlan.decide(.wifiOnly, unmetered: true, both), .proceed, "Wi-Fi only on Wi-Fi")
        check(DownloadPlan.decide(.wifiOnly, unmetered: false, both), .ask, "Wi-Fi only on mobile data asks")
        check(DownloadPlan.mayPrefetch(.wifiOnly, unmetered: false, both), false, "no prefetch on mobile data")
        check(DownloadPlan.mayPrefetch(.wifiOnly, unmetered: true, both), true, "prefetch on Wi-Fi")
        check(DownloadPlan.mayPrefetch(.unset, unmetered: true, both), false, "no prefetch without consent")
        check(DownloadPlan.mayPrefetch(.anyNetwork, unmetered: false, none), false, "nothing to prefetch")

        // SpeechCheck
        check(SpeechCheck.hasSpeech([]), false, "no lines")
        check(SpeechCheck.hasSpeech(["[noise]", "  ", "…"]), false, "noise tags and punctuation")
        check(SpeechCheck.hasSpeech(["[noise]", "尾牙定在十二月"]), true, "one real line")
        check(SpeechCheck.hasSpeech(["OK"]), true, "short English")

        // ReaderGuard: counts each kind, the notes are unchanged (unknown tags kept)
        func read(_ reply: String) -> MeetingReader {
            let r = MeetingReader(llm: OneReply(reply), systemPrompt: "sys")
            try! r.start()
            for s in [0, 20, 60] { try! r.offer(Line(startS: s, speaker: "S1", text: "話\(s)")) }
            _ = try! r.finish()
            return r
        }
        let r = read(["NOTE [0:00] (DECISION) 尾牙定在十二月十九號", "Here is a summary of the meeting.", "REVISE #1 something",
                      "NOTE garbage without a time", "NOTE [0:20] (TODO) 寄出邀請函", "", "NEXT"].joined(separator: "\n"))
        check(r.guardCounts, ReaderGuard(lines: 5, notes: 2, nonNote: 2, unknownTag: 1, malformed: 1), "guard counts")
        check(r.journal.map(\.tag), ["DECISION", "TODO"], "notes kept")
        check(abs(r.guardCounts.rate - 0.8) < 1e-9, true, "rate")
        check(read("NOTE [0:00] (DECISION) 尾牙定在十二月十九號\nNOTE [1:00] (ACTION) 週五前定案菜單").guardCounts, ReaderGuard(lines: 2, notes: 2), "clean reply")
        let card = GuardCard(maxOffProtocolRate: 0.2, minLines: 10)
        check(ReaderGuard(lines: 4, nonNote: 4).exceeds(card), false, "too few lines to judge")
        check(ReaderGuard(lines: 10, nonNote: 3).exceeds(card), true, "above")
        check(ReaderGuard(lines: 10, nonNote: 2).exceeds(card), false, "at the threshold")
        let g = ModelCard.default.reader.guardrail
        check(g.knownTags, ["DECISION", "ACTION", "NUMBER", "OPEN-ISSUE", "PROPOSAL"], "shipped known tags")
        check(g.ignoreActs, ["NEXT"], "shipped ignore acts"); check(g.maxOffProtocolRate, 0.2, "shipped rate")
        check(g.known(nil) && g.known("decision") && !g.known("TODO"), true, "known()")
        let json = try! JSONEncoder().encode(ReaderGuard(lines: 3, notes: 1, nonNote: 2))
        check(try! JSONDecoder().decode(ReaderGuard.self, from: json), ReaderGuard(lines: 3, notes: 1, nonNote: 2), "guard round trip")

        print(failures == 0 ? "guard+plan PASS" : "guard+plan: \(failures) failure(s)"); exit(failures == 0 ? 0 : 1)
    }
}
