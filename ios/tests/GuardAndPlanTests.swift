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
        // PromptGate: "Later" holds every automatic prompt for the run; a user action still asks.
        var pg = DownloadPlan.PromptGate()
        check(pg.mayAutoPrompt(.unset, both), true, "first launch asks")
        check(pg.mayAutoPrompt(.unset, both), false, "no second prompt while one shows")
        pg.dismissed()
        check(pg.mayAutoPrompt(.unset, both), false, "Later: a network change does not ask again")
        check(pg.mayAutoPrompt(.unset, both), false, "Later: still quiet on the next re-render")
        pg.explicitPrompt()
        check(pg.mayAutoPrompt(.unset, both), false, "a run's own prompt showing: no second one")
        pg.dismissed()
        check(pg.later, true, "Later on a run's prompt keeps the hold")
        pg.explicitPrompt(); pg.answered()
        check(pg.later, false, "an answer lifts the hold")
        check(pg.mayAutoPrompt(.unset, none), false, "nothing missing: no prompt")
        check(pg.mayAutoPrompt(.anyNetwork, both), false, "consent given: no prompt")
        var pg2 = DownloadPlan.PromptGate(); pg2.dismissed()
        check(pg2.mayAutoPrompt(.unset, both), false, "dismissed before it showed: quiet")
        check(DownloadPlan.decide(.unset, unmetered: true, both), .ask, "unset asks, even on Wi-Fi")
        check(DownloadPlan.decide(.anyNetwork, unmetered: false, both), .proceed, "any network")
        check(DownloadPlan.decide(.wifiOnly, unmetered: true, both), .proceed, "Wi-Fi only on Wi-Fi")
        check(DownloadPlan.decide(.wifiOnly, unmetered: false, both), .ask, "Wi-Fi only on mobile data asks")
        check(DownloadPlan.mayPrefetch(.wifiOnly, unmetered: false, both), false, "no prefetch on mobile data")
        check(DownloadPlan.mayPrefetch(.wifiOnly, unmetered: true, both), true, "prefetch on Wi-Fi")
        check(DownloadPlan.mayPrefetch(.unset, unmetered: true, both), false, "no prefetch without consent")
        check(DownloadPlan.mayPrefetch(.anyNetwork, unmetered: false, none), false, "nothing to prefetch")
        // A model migration (E4B mobile-v1 installed, mobile-v2 missing): nothing waits, Wi-Fi only
        let up = DownloadPlan.pending(speechReady: true, speechBytes: 500, readerReady: false, readerBytes: 2000, replacedReady: true)
        check(up.readerUpgrade, true, "upgrade"); check(up.blocking, 0, "an upgrade blocks nothing")
        check(DownloadPlan.decide(.unset, unmetered: false, up), .proceed, "a run never waits for an upgrade")
        check(DownloadPlan.mayPrefetch(.anyNetwork, unmetered: false, up), false, "upgrade waits for Wi-Fi")
        check(DownloadPlan.mayPrefetch(.wifiOnly, unmetered: true, up), true, "upgrade on Wi-Fi")
        check(DownloadPlan.pending(speechReady: true, speechBytes: 0, readerReady: false, readerBytes: 2000).readerUpgrade, false, "no old model: not an upgrade")

        // ReaderChoice (Android ModelReuseTest): E4B v1 -> E4B-v2
        let rep = ["E4B": "E4B-v2"]
        check(ReaderChoice.current("E4B", replacedBy: rep), "E4B-v2", "choice migrates")
        check(ReaderChoice.current("E2B", replacedBy: rep), "E2B", "E2B stays")
        check(ReaderChoice.runnable("E4B", replacedBy: rep, ready: { $0 == "E4B" }), "E4B", "v1 reads until v2 is installed")
        check(ReaderChoice.runnable("E4B", replacedBy: rep, ready: { _ in true }), "E4B-v2", "both: v2")
        check(ReaderChoice.runnable("E4B", replacedBy: rep, ready: { _ in false }), "E4B-v2", "none: download v2")
        check(ReaderChoice.runnable("E2B", replacedBy: rep, ready: { $0 == "E4B" }), "E2B", "E2B unaffected")
        check(ReaderChoice.reclaimable(replacedBy: rep, installed: { $0 == "E4B" }), [], "v1 kept while v2 missing")
        check(ReaderChoice.reclaimable(replacedBy: rep, installed: { $0 == "E4B-v2" }), ["E4B"], "v1 goes once v2 is installed")

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
        check(g.knownTags, ["DECISION", "ACTION", "NUMBER", "OPEN-ISSUE", "PROPOSAL", "-"], "shipped known tags")
        check(g.ignoreActs, ["NEXT"], "shipped ignore acts"); check(g.maxOffProtocolRate, 0.2, "shipped rate")
        check(g.known(nil) && g.known("decision") && g.known("-") && !g.known("TODO"), true, "known()")
        // Android 20b9615c: "(-)" (the prompt's "other" type) is the tag, not part of the note text.
        let nl = "[0:40] (-) Next meeting on Thursday.", nr = ModelCard.default.reader.note
        let nm = nr.firstMatch(in: nl, range: NSRange(nl.startIndex..., in: nl))!
        check(Range(nm.range(at: 2), in: nl).map { String(nl[$0]) } == "-" && Range(nm.range(at: 3), in: nl).map { String(nl[$0]) } == "Next meeting on Thursday.", true, "(-) tag")
        let json = try! JSONEncoder().encode(ReaderGuard(lines: 3, notes: 1, nonNote: 2))
        check(try! JSONDecoder().decode(ReaderGuard.self, from: json), ReaderGuard(lines: 3, notes: 1, nonNote: 2), "guard round trip")

        // Timestamp repair (Android TimestampRepairTest)
        let known = ["3:41", "6:34", "8:03", "11:04", "1:02:15"]
        func fix(_ s: String) -> String { ReaderProtocol.repairTimestamps(s, known: known) }
        check(fix("a [3:41] b [1:02:15]"), "a [3:41] b [1:02:15]", "known tags stay")
        check(fix("[6:40]"), "[6:34]", "near miss snaps"); check(fix("[10:50]"), "[11:04]", "near miss snaps 2")
        check(fix("x [20:00] y"), "x  y", "far tag dropped")
        check(fix("[6:3]"), "[6:34]", "cut-off seconds"); check(fix("[5:6]"), "", "5:6 dropped"); check(fix("[7:8]"), "", "7:8 dropped")
        check(fix("[8:03, 11:04, 7]"), "[8:03] [11:04]", "list split"); check(fix("[21]"), "", "bare number")
        check(fix("[3:41-6:34]"), "[3:41] [6:34]", "range"); check(fix("[8:03，8:05]"), "[8:03]", "same note once")
        check(fix("[8：03]"), "[8:03]", "full-width colon"); check(fix("[1:02:20]"), "[1:02:15]", "hours snap"); check(fix("[1:62:15]"), "", "bad minutes")
        check(fix("[S1] [note] [[2]] [a1]"), "[S1] [note] [[2]] [a1]", "other brackets")
        check(ReaderProtocol.repairTimestamps("[5:03]", known: ["05:03"]), "[05:03]", "the notes' own spelling")

        // Meeting language and the card's variant (Android MeetingLanguageTest)
        check(MeetingLanguage.detect(["so we'll go with the yellow casing and the rubber push buttons then, and the logo goes on the inside of the case"]), "en", "english")
        check(MeetingLanguage.detect(["那我們就決定尾牙定在十二月十九號，地點在台北"]), "zh", "chinese")
        check(MeetingLanguage.detect(["這個 project 的 deadline 是下週五，UI 的部分要先給 PM 看過再說"]), "zh", "mixed stays chinese")
        check(MeetingLanguage.detect(["OK", "好"]), nil, "too little")
        check(MeetingLanguage.detect(["会议决定采用新的方案并由小王负责跟进，下周再确认"]), "zh", "simplified")
        let base = ModelCard.default, en = base.forLanguage("en")
        check(en.prose.prompt != base.prose.prompt && en.title.prompt != base.title.prompt, true, "english prompts")
        check(en.reader.directive != nil && en.reader.systemSuffix != nil, true, "english directive and system suffix")
        check(base.forLanguage("zh").reader.directive == nil && base.forLanguage("zh").reader.systemSuffix == nil, true, "chinese: upstream prompt")
        check(Set(base.ask.answerIn.keys).isSuperset(of: base.ask.system.keys), true, "ask answer_in for every system language")
        check(en.prose.maxTokens, base.prose.maxTokens, "prose budget unchanged"); check(en.title.maxTokens, base.title.maxTokens, "title budget unchanged")
        check(en.reader.sections.map(\.1), base.reader.sections.map(\.1), "same section tags")
        check(base.forLanguage("fr").prose.prompt, base.prose.prompt, "unlisted language"); check(base.forLanguage(nil).reader.directive, base.reader.directive, "nil language")
        final class Rec: ReaderLlm {
            var seq: [Int] = [], turns: [String] = []
            func tokenize(_ text: String, special: Bool) -> [Int] { text.unicodeScalars.map { Int($0.value) } }
            func append(_ tokens: [Int]) -> Int { seq += tokens; return seq.count }
            func generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Void) -> String {
                var v = String.UnicodeScalarView(); v.append(contentsOf: seq.compactMap { Unicode.Scalar(UInt32($0)) }); turns.append(String(v)); return "NEXT" + stop
            }
            var seqLength: Int { seq.count }
            func reset() { seq = [] }
        }
        func readLang(_ text: String) -> (Rec, MeetingReader, String) {
            let llm = Rec(), r = MeetingReader(llm: llm, systemPrompt: "sys", count: { $0.unicodeScalars.count }, budget: base.budget, card: base)
            try! r.start(); for i in 0..<5 { try! r.offer(Line(startS: i * 10, speaker: "S1", text: text)) }
            return (llm, r, try! r.finish())
        }
        let (enLlm, enR, enMin) = readLang("OK so we'll go with the yellow casing and the rubber push buttons, agreed")
        check(enR.language, "en", "reader language en")
        check(enLlm.turns.count == 1 && enLlm.turns[0].hasSuffix(en.reader.directive! + base.template.end + base.template.sep + base.template.model), true, "english directive before the turn")
        check(enMin.contains(en.reader.sections[0].0), true, "english minutes headers")
        let (zhLlm, zhR, _) = readLang("那我們就決定尾牙定在十二月十九號，地點在台北，大家同意")
        check(zhR.language, "zh", "reader language zh")
        check(zhLlm.turns[0].contains(en.reader.directive!), false, "no english directive for chinese")
        if let d = base.forLanguage("zh").reader.directive { check(zhLlm.turns[0].contains(d), true, "chinese directive") }
        // The system prompt waits for the language: an English meeting's FIRST window reads the card's
        // agreement rule (Android MeetingLanguageTest.firstWindowReadsTheLanguagesSystemPrompt).
        let tp = base.template
        check(enLlm.turns[0].hasPrefix(tp.p0 + "sys" + en.reader.systemSuffix! + tp.p1), true, "english first window has the system suffix")
        if let h = enLlm.turns[0].range(of: base.reader.windowHeader(1)), let pr = enLlm.turns[0].range(of: base.reader.primer), let l0 = enLlm.turns[0].range(of: "[0:00] S1:") {
            check(h.lowerBound > pr.lowerBound && l0.lowerBound > h.lowerBound, true, "held lines follow the prefix in order")
        } else { check(false, true, "held lines present") }
        // A Chinese meeting's conversation is exactly the card's own (as an upfront prefill fed it).
        func turns(_ card: ModelCard, _ n: Int) -> [String] {
            let llm = Rec(), r = MeetingReader(llm: llm, systemPrompt: "sys", count: { $0.unicodeScalars.count }, budget: base.budget, card: card)
            try! r.start(); for i in 0..<n { try! r.offer(Line(startS: i * 10, speaker: "S1", text: "那我們就決定尾牙定在十二月十九號，地點在台北，大家同意，第\(i)點")) }
            _ = try! r.finish(); return llm.turns
        }
        var plain = base; plain.languages = [:]
        check(turns(base, 40), turns(plain, 40), "chinese conversation unchanged by the wait")
        check(turns(base, 40)[0].hasPrefix(tp.p0 + "sys" + tp.p1), true, "chinese prefix without suffix")
        // The minutes' proposal guard reads the meeting's language (Android proposalGuardFollowsTheLanguage).
        func note(_ t: String, _ tag: String = "DECISION") -> Note { Note(id: 1, window: 1, ts: "1:00", tag: tag, text: t) }
        let enR2 = en.reader, zhR2 = base.forLanguage("zh").reader
        for t in ["Maybe use rubber push buttons.", "We could use a kinetic battery.", "Should we go for a curved case?",
                  "Let's think about a titanium case.", "S2 proposed a yellow casing.", "Marketing suggested a voice recognizer.",
                  "Not sure about the scroll wheel.", "Use a solar cell?"] {
            check(enR2.reclassify(note(t)).tag, "PROPOSAL", "en cue: \(t)")
            check(enR2.reclassify(note(t, "ACTION")).tag, "PROPOSAL", "en cue action: \(t)")
            check(zhR2.reclassify(note(t)).tag, "DECISION", "zh cues do not read english: \(t)")
        }
        for t in ["Use rubber push buttons and a yellow casing.", "The team agreed on S2's suggestion of a kinetic battery.",
                  "Decided: maybe later, the logo goes inside.", "Ed will email the specs by Friday."] {
            check(enR2.reclassify(note(t)).tag, "DECISION", "en decided: \(t)")
        }
        check(zhR2.reclassify(note("建議採用黃色外殼")).tag, "PROPOSAL", "zh cue")
        check(zhR2.reclassify(note("決定採用黃色外殼")).tag, "DECISION", "zh decided")

        print(failures == 0 ? "guard+plan PASS" : "guard+plan: \(failures) failure(s)"); exit(failures == 0 ? 0 : 1)
    }
}
