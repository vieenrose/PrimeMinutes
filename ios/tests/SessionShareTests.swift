import Foundation

/// Meetings shared between devices (manifest v2, SessionFileCore "sharing"). golden_share/ is byte-identical to
/// Android app/src/test/resources/share_golden/ (SessionShareTest): android_v2 / android_v1 / future_v3 are written
/// by Android, ios_v2 / voxsum_v1 here (VOX_SHARE_REGEN=1 rewrites them from golden_share/tone.m4a).
enum AppLanguage { case test; static let current = AppLanguage.test; var script: ChineseScript { .traditional } }   // OpenCC.swift's TextConv default

@main struct SessionShareTests {
    static let sid = "6F1C2B9E-3D4A-4C2B-9A55-0E7D2C1B8A01", created = "2026-10-21T02:30:00Z"
    typealias M = SessionFile.Manifest
    static func meetingV1() -> M {
        var m = M(); m.primeminutes_version = 1
        m.title = "預算會議"; m.title_user_set = true; m.summary = "討論了明年的預算與時程。"
        m.action_items = "- 王小明：週五前寄出報價"; m.notes = "#1 [0:00] (DECISION) 預算維持不變\n#2 [0:00] (ACTION) 王小明寄出報價"
        m.asr_model = "xasr-stream-q8-octav+sortformer-int8-f380"; m.asr_backend = "ios"; m.llm_model = "gemma4-e2b-mobile-v1"
        m.speaker_names = ["0": .init(name: "王小明", confidence: "user", reason: "", person: "person-7f3a"), "1": .init(name: "李華", confidence: "user", reason: "")]
        m.utterances = [.init(index: 0, start: 0, end: 0.5, text: "我們來討論預算。", speaker: 0, token_times: [0, 0.1, 0.2]),
                        .init(index: 1, start: 0.5, end: 1, text: "時程沒有問題。", speaker: 1, token_times: nil)]
        return m
    }
    static func main() {
        let dir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "golden_share"
        func f(_ n: String) -> URL { URL(fileURLWithPath: dir + "/" + n) }
        var fails = 0, n = 0
        func ok(_ c: Bool, _ what: String) { n += 1; if !c { fails += 1; print("FAIL \(what)") } }
        if ProcessInfo.processInfo.environment["VOX_SHARE_REGEN"] != nil {
            let v2 = SessionFile.forShare(meetingV1(), sessionId: sid, createdAt: SessionFile.parseIso(created), script: "zh-Hant", userEdited: .init(summary: true, lines: false))
            ok(SessionFile.write(src: f("tone.m4a"), dest: f("ios_v2.m4a"), title: v2.title!, summary: v2.summary!, lrc: "[00:00.00]x", blob: SessionFile.blob(v2)!), "write ios_v2")
            ok(SessionFile.write(src: f("tone.m4a"), dest: f("voxsum_v1.m4a"), title: "預算會議", summary: "", lrc: "", blob: SessionFile.blob(meetingV1())!, tag: (SessionFile.LEGACY_MEAN, SessionFile.LEGACY_FIELD)), "write voxsum_v1")
        }
        func check(_ m: M?, _ from: String) {
            guard let m else { ok(false, "\(from) unreadable"); return }
            ok(m.title == "預算會議" && m.summary == "討論了明年的預算與時程。", "\(from) title/summary")
            ok(m.utterances?.count == 2 && m.utterances?[0].text == "我們來討論預算。" && m.utterances?[0].speaker == 0, "\(from) utterances")
            ok(m.utterances?[0].token_times?.count == 3, "\(from) token_times")
            ok(m.speaker_names?["1"]?.name == "李華" && m.speaker_names?["0"]?.name == "王小明", "\(from) names")
            ok(m.notes?.contains("預算維持不變") == true, "\(from) notes")
            ok(SessionFile.supported(m), "\(from) supported")
        }
        for (name, v2) in [("android_v2.m4a", true), ("ios_v2.m4a", true), ("android_v1.m4a", false), ("voxsum_v1.m4a", false)] {
            let m = SessionFile.read(f(name)); check(m, name)
            if v2 {
                ok(m?.session_id?.uppercased() == sid, "\(name) session_id")
                ok(SessionFile.parseIso(m?.created_at) == SessionFile.parseIso(created), "\(name) created_at")
                ok(m?.speaker_names?.values.allSatisfy { $0.person == nil } == true, "\(name) no person")
                ok(m?.title_user_set == true, "\(name) title_user_set")
                ok(m?.user_edited == .init(summary: true, lines: false), "\(name) user_edited")
                // privacy: the raw JSON never names a person
                if let m, let j = try? JSONEncoder().encode(m) { ok(!String(decoding: j, as: UTF8.self).contains("person"), "\(name) json person") }
            } else { ok(m?.session_id == nil, "\(name) no id") }
        }
        let fut = SessionFile.read(f("future_v3.m4a")); ok(fut != nil && !SessionFile.supported(fut!), "future_v3 refused")
        // allowlist + identity
        let s = SessionFile.forShare(meetingV1(), sessionId: sid, createdAt: nil, script: "zh-Hans")
        ok(s.primeminutes_version == 2 && s.session_id == sid && s.created_at == nil && s.script == "zh-Hans", "forShare identity")
        ok(String(decoding: try! JSONEncoder().encode(s), as: UTF8.self).contains("person") == false, "forShare drops person")
        ok(SessionFile.iso(SessionFile.parseIso(created)!) == created, "iso round trip")
        // Han script on import (receiver zh-Hans), span-gated converters
        let c = SessionFile.converted(meetingV1(), generated: OpenCC.generated(.simplified).convert, transcript: OpenCC.transcript(.simplified).convert)
        ok(c.title == "预算会议", "convert title \(c.title ?? "")")
        ok(c.utterances?[0].text == "我们来讨论预算。", "convert line \(c.utterances?[0].text ?? "")")
        ok(c.speaker_names?["0"]?.name == "王小明", "convert name \(c.speaker_names?["0"]?.name ?? "")")
        ok(c.speaker_names?["1"]?.name == "李华", "convert name2")
        let back = SessionFile.converted(c, generated: OpenCC.generated(.traditional).convert, transcript: OpenCC.transcript(.traditional).convert)
        ok(back.title == "預算會議", "convert back \(back.title ?? "")")
        print(fails == 0 ? "session-share: \(n) checks PASS" : "session-share: \(fails)/\(n) FAIL"); exit(fails == 0 ? 0 : 1)
    }
}
