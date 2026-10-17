import Foundation

/// The participants e-mail against the design's golden files (same fixtures as Android MeetingMailTest), the people
/// contact side, the share history and the recipients rules. ios/tests/run.sh
struct Utterance { var speaker: Int, start: Double, end: Double, text: String }   // the app's (Transcript.swift) without the bridge

@main struct MeetingMailTests {
    nonisolated(unsafe) static var failures = 0
    static func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL \(what)") } }
    static let dir = URL(fileURLWithPath: CommandLine.arguments[1])
    static func res(_ n: String) -> String { (try? String(contentsOf: dir.appendingPathComponent(n), encoding: .utf8)) ?? "<missing \(n)>" }
    static let card = (try? JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2])))) as? [String: Any] ?? [:]
    static func sections(_ lang: String) -> [(String, String)] {
        let raw = lang == "en" ? ((card["languages"] as? [String: Any])?["en"] as? [String: Any])?["sections"] : (card["reader"] as? [String: Any])?["sections"]
        return ((raw as? [[String]]) ?? []).map { ($0[0], $0[1]) }
    }

    static func input(_ name: String) -> MeetingMail.Input {
        let fx = (try? JSONSerialization.jsonObject(with: Data(res("\(name).json").utf8))) as? [String: Any] ?? [:]
        let lang = fx["lang"] as? String ?? "zh"
        var names: [Int: String] = [:]
        for (k, v) in fx["people"] as? [String: [String: Any]] ?? [:] { names[Int(k)!] = v["name"] as? String }
        let utts = fx["utterances"] as? [[String: Any]] ?? []
        let known = Set(utts.compactMap { ($0["speaker"] as? NSNumber)?.intValue })
        let dur = utts.compactMap { ($0["end"] as? NSNumber)?.doubleValue }.max() ?? 0
        let iso = ISO8601DateFormatter()
        let started = fx["started"] as! String
        let tz = TimeZone(secondsFromGMT: Int(started.suffix(6).prefix(3))! * 3600)!
        return MeetingMail.Input(title: fx["title"] as! String, summary: fx["summary"] as? String,
                                 sections: MeetingMail.parseMinutes(fx["minutes_raw"] as! String, sections(lang)),
                                 names: names, participants: names.sorted { $0.key < $1.key }.map(\.value),
                                 unnamed: known.filter { names[$0] == nil }.count, start: iso.date(from: started)!, timeZone: tz, durationSec: dur)
    }
    static func body(_ n: String) -> String {
        let t = res(n)
        guard t.hasPrefix("To:"), let r = t.range(of: "\n\n") else { return t }
        return String(t[r.upperBound...])
    }
    static func plain(_ name: String, _ lang: String, _ o: MeetingMail.Options, chat: Bool = false) -> String {
        let tpl = MeetingMail.Templates.shipped
        let (m, _) = MeetingMail.model(input(name), lang: lang, o, budgetTemplate: chat ? tpl.chat : tpl.txt)
        return MeetingMail.plain(m, chat ? tpl.chat : tpl.txt)
    }
    static func html(_ name: String, _ lang: String, _ o: MeetingMail.Options) -> String {
        let (m, _) = MeetingMail.model(input(name), lang: lang, o, htmlHeadings: true)
        return MeetingMail.html(m, MeetingMail.Templates.shipped.html)
    }
    static func same(_ a: String, _ b: String, _ what: String) {
        if a == b { return }
        let x = a.components(separatedBy: "\n"), y = b.components(separatedBy: "\n")
        let i = zip(x, y).enumerated().first { $0.element.0 != $0.element.1 }?.offset ?? min(x.count, y.count)
        check(false, "\(what): line \(i)\n  got  \(i < x.count ? x[i] : "<end>")\n  want \(i < y.count ? y[i] : "<end>")")
    }

    static func main() {
        same(plain("move", "zh-Hant", .init()), body("move.zh-TW.txt"), "zh txt")
        same(html("move", "zh-Hant", .init()), res("move.zh-TW.html"), "zh html")
        let only = MeetingMail.Options(summary: false, only: ["DECISION", "ACTION"])
        same(plain("move", "zh-Hant", only), body("move.zh-TW.actions-only.txt"), "actions-only txt")
        same(html("move", "zh-Hant", only), res("move.zh-TW.actions-only.html"), "actions-only html")
        let att = MeetingMail.Options(attachments: ["Remote-control-concept.pdf"])
        same(plain("is1009c", "en", att), body("is1009c.en.txt"), "en txt")
        same(html("is1009c", "en", att), res("is1009c.en.html"), "en html")
        let cut = MeetingMail.Options(budget: 2000)
        check(MeetingMail.model(input("is1009c"), lang: "en", cut).1, "truncated")
        same(plain("is1009c", "en", cut), body("is1009c.en.mailto.txt"), "mailto txt")
        same(plain("move", "zh-Hant", MeetingMail.Options(summary: false), chat: true), body("move.zh-TW.chat.txt"), "chat")
        let m = MeetingMail.build(input("move"), lang: "zh-Hans", .init())
        check(m.subject.hasPrefix("会议纪要｜") && m.plain.contains("注意：本纪要由 AI 根据录音自动生成"), "zh-Hans frame \(m.subject)")
        check(MeetingMail.resolveRefs("S2 需請法務", [1: "林雅婷"]) == "林雅婷需請法務", "refs han")
        check(MeetingMail.resolveRefs("Ask S1 now, S9 too", [0: "Mark"]) == "Ask Mark now, S9 too", "refs latin")
        check(MeetingMail.defaultLang(meeting: "zh", ui: Locale(identifier: "zh-Hans-CN")) == "zh-Hans", "lang hans")
        check(MeetingMail.defaultLang(meeting: "zh", ui: Locale(identifier: "en-US")) == "zh-Hant", "lang default")

        // People contact side (Android PeopleContactTest).
        check(EmailRules.isValid("ａｂ＠example.com") && EmailRules.normalize(" ａｂ＠example.com ") == "ab@example.com", "nfkc")
        for bad in ["", "a", "a@b", "a@b.c", "a b@c.com", "a@@c.com", "@c.com", "a@.com", "a@c..com", "a@c.com.", String(repeating: "x", count: 65) + "@c.com"] {
            check(!EmailRules.isValid(bad), "invalid \(bad)")
        }
        let text = #"{"version":1,"people":[{"id":"a","name":"林雅婷","emails":[{"address":"y@x.com","from":"contact"},"home@x.org","y@X.COM"],"contact":{"platform":"android","key":"K1","id":12,"name":"林雅婷","org":"總務部","linked":5,"synced":6,"future":1},"later":{"k":2}}]}"#
        let d = PeopleCodec.decode(text)
        check(d.people[0].emails == [PersonEmail(address: "y@x.com", from: "contact"), PersonEmail(address: "home@x.org")], "emails decode \(d.people[0].emails)")
        check(d.people[0].contact?.id == 12 && d.people[0].contact?.org == "總務部", "contact decode")
        check(PeopleCodec.decode(PeopleCodec.encode(d)) == d, "contact round trip")
        let enc = String(decoding: PeopleCodec.encode(d), as: UTF8.self)
        check(enc.contains(#""future":1"#) && enc.contains(#""later""#), "unknown keys kept")
        var p = PeopleDoc(people: [Person(id: "a", name: "Mark", emails: [PersonEmail(address: "typed@x.com")]), Person(id: "b", name: "Marcus")])
        let c = PickedContact(name: "Mark Chen", emails: ["mark@acme.com", "mark@home.org"], key: "K", id: nil, org: "ACME")
        p = PeopleOps.linkContact(p, "a", c, now: 100, chosen: "mark@home.org")
        check(p.byId("a")!.emails.map(\.address) == ["typed@x.com", "mark@home.org", "mark@acme.com"] && p.byId("a")!.note == "ACME" && p.byId("a")!.name == "Mark", "link")
        p = PeopleOps.linkContact(p, "b", c, now: 101)
        check(PeopleOps.sameContact(p, p.byId("b")!).map(\.id) == ["a"], "same contact")
        p = PeopleOps.addEmail(p, "b", "marcus@x.com")
        p = PeopleOps.merge(p, from: "b", into: "a")
        check(p.byId("a")!.emails.map(\.address) == ["typed@x.com", "mark@home.org", "mark@acme.com", "marcus@x.com"] && p.byId("a")!.contact?.linked == 100, "merge")
        check(PeopleOps.unlinkContact(p, "a", removeEmails: true).byId("a")!.emails.map(\.address) == ["typed@x.com", "marcus@x.com"], "unlink remove")
        check(PeopleOps.makeDefault(p, "a", "marcus@x.com").byId("a")!.emails.first?.address == "marcus@x.com", "default")
        check(PeopleOps.removeEmail(p, "a", "typed@X.com").byId("a")!.emails.count == 3, "remove")

        // Share history and recipients.
        let s = ShareHistory.Share(at: 1792226400, channel: "email", status: "opened",
                                   recipients: [.init(person: "p1", address: "yating.lin@example.com"), .init(person: nil, address: "guest@Example.com")],
                                   content: .init(summary: true, minutes: true, only: nil, attach: ["pdf"]), lang: "zh-Hant")
        let back = ShareHistory.decode(ShareHistory.encode([s]))
        check(back == [s], "history round trip")
        check(ShareHistory.scrub(back, person: "p9", addresses: [EmailRules.key("guest@example.com")])[0].recipients.map(\.address) == ["yating.lin@example.com"], "scrub")
        let me = Person(id: "m", name: "我", me: true, emails: [PersonEmail(address: "me@x.com")])
        let y = Person(id: "y", name: "Y", emails: [PersonEmail(address: "y@x.com")]), n = Person(id: "n", name: "N")
        let rows = Recipients.rows([0: me, 1: y, 2: n, 3: y], anyMe: true)
        check(rows.map(\.person.id) == ["m", "y", "n"] && rows.map(\.checked) == [false, true, false] && rows[1].speakers == [1, 3], "rows")
        check(Recipients.rows([0: me], anyMe: false)[0].checked, "no Me yet: checked")
        check(Recipients.to(rows, extra: ["Y@x.com", "z@x.com"]) == ["y@x.com", "Y@x.com", "z@x.com"], "to")

        print(failures == 0 ? "meeting mail: all passed" : "meeting mail: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
