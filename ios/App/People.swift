import Foundation

/// The people directory (docs/design/speaker-tags.md): the people the user has named, shared by every
/// meeting. A meeting's speaker is linked to a person by id; the name a meeting shows is the person's
/// name, so renaming a person renames them everywhere. Everything here is pure (Foundation only, host
/// tested): the store is `PeopleStore`, the Android twin is People.kt, and both read and write the same JSON.
struct Person: Codable, Hashable, Identifiable {
    /// UUID, never reused.
    var id: String
    /// Display name, in the user's script.
    var name: String
    /// Optional disambiguator ("Sales"), shown next to a homonym.
    var note: String? = nil
    /// Names merged into this person: Ask routing and the picker's search match them too.
    var aliases: [String] = []
    var created: Int64 = 0
    /// Last link made or confirmed (epoch seconds): the picker's "recent" order.
    var lastUsed: Int64 = 0
    /// False: never learn this voice (a role such as "Host"). Phase 2.
    var voiceEnabled = true
    /// The phone's owner ("Me"). Phase 2.
    var me = false
    /// E-mail addresses (docs/design/speaker-contacts.md §2.1), the first is the default (★).
    var emails: [PersonEmail] = []
    /// The address-book entry this person was linked to through the system picker, or nil.
    var contact: ContactLink? = nil
    /// The JSON object of the keys this version does not know (a later app's fields): written back unchanged, so an
    /// older app never drops them (Android Person.extra). Nil when there are none.
    var extra: String? = nil
}

/// One address of a person. `from`: "contact" (came from the address book) or "user" (typed).
struct PersonEmail: Codable, Hashable {
    var address: String
    var from: String = PersonEmail.fromUser
    static let fromUser = "user", fromContact = "contact"
}

/// A link to an address-book entry (§2.2): the platform's stable key and a snapshot of what the picker returned.
/// `platform` "android" | "ios": a link made on the other platform is kept but not used. `raw`: unknown keys.
struct ContactLink: Codable, Hashable {
    var platform: String
    var key: String
    /// Android CONTACT_ID; nil on iOS.
    var id: Int64? = nil
    var name: String? = nil
    var org: String? = nil
    var linked: Int64 = 0
    var synced: Int64 = 0
    var missing = false
    var raw: String? = nil
}

/// What the system contact picker handed over for one person (one tap, no permission).
struct PickedContact: Equatable {
    var name: String?
    var emails: [String]
    var key: String?
    var id: Int64? = nil
    var org: String? = nil
    var platform = "ios"
}

/// E-mail address rules (§7.3), the same as Android EmailRules: NFKC, one `@`, a 1–64 character local part, a dotted
/// domain with a 2+ character TLD, no spaces, 254 at most. The mail app does the final check.
enum EmailRules {
    static func normalize(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCompatibilityMapping.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static func isValid(_ s: String) -> Bool {
        let a = normalize(s)
        guard a.count <= 254, !a.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) }) else { return false }
        let parts = a.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        let local = parts[0], domain = parts[1]
        guard !local.isEmpty, local.count <= 64 else { return false }
        let labels = domain.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 2, !labels.contains(where: { $0.isEmpty }), let tld = labels.last, tld.count >= 2 else { return false }
        return tld.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || $0 == "-" }
    }
    /// Dedup key: the local part as typed, the domain lowercased.
    static func key(_ s: String) -> String {
        let a = normalize(s)
        guard let at = a.lastIndex(of: "@") else { return a }
        return String(a[..<at]) + "@" + a[a.index(after: at)...].lowercased()
    }
}

struct PeopleDoc: Equatable {
    var people: [Person] = []
    /// The names saved before the directory existed were gathered into it.
    var migrated = false
    /// How many people that migration created: the People screen's one-time card (0 once seen).
    var migrationFound = 0
    /// "Recognise voices (on this phone)": off until the user turns it on (Phase 2).
    var voice = false
    func byId(_ id: String?) -> Person? { id.flatMap { i in people.first { $0.id == i } } }
}

/// One named speaker of a meeting: its name there (kept with the meeting) and the person it is linked to.
struct SpeakerLink: Equatable, Hashable {
    var name: String
    var person: String? = nil
}

/// The JSON of `people/people.json` (same on Android and iOS).
enum PeopleCodec {
    static let version = 1
    static let known: Set<String> = ["id", "name", "note", "aliases", "created", "lastUsed", "voiceEnabled", "me", "emails", "contact"]
    static let contactKnown: Set<String> = ["platform", "key", "id", "name", "org", "linked", "synced", "missing"]

    static func encodeContact(_ c: ContactLink) -> [String: Any] {
        var o: [String: Any] = [:]
        if let r = c.raw?.data(using: .utf8), let x = (try? JSONSerialization.jsonObject(with: r)) as? [String: Any] {
            for (k, v) in x where !contactKnown.contains(k) { o[k] = v }
        }
        o["platform"] = c.platform; o["key"] = c.key
        if let i = c.id { o["id"] = i }
        if let n = c.name { o["name"] = n }
        if let g = c.org { o["org"] = g }
        o["linked"] = c.linked; o["synced"] = c.synced
        if c.missing { o["missing"] = true }
        return o
    }
    static func decodeContact(_ v: Any?) -> ContactLink? {
        guard let o = v as? [String: Any], let key = o["key"] as? String, !key.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        func s(_ k: String) -> String? { (o[k] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty }
        let x = o.filter { !contactKnown.contains($0.key) }
        let raw = x.isEmpty ? nil : (try? JSONSerialization.data(withJSONObject: x, options: [.sortedKeys])).map { String(decoding: $0, as: UTF8.self) }
        return ContactLink(platform: s("platform") ?? "android", key: key, id: (o["id"] as? NSNumber)?.int64Value, name: s("name"), org: s("org"),
                           linked: int64(o["linked"]), synced: int64(o["synced"]), missing: (o["missing"] as? Bool) ?? false, raw: raw)
    }
    static func decodeEmails(_ v: Any?) -> [PersonEmail] {
        var out: [PersonEmail] = []
        for e in (v as? [Any]) ?? [] {
            // A plain string is accepted too (a hand-written or older file).
            let pe: PersonEmail?
            if let o = e as? [String: Any] {
                pe = PersonEmail(address: ((o["address"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                                 from: (o["from"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? PersonEmail.fromUser)
            } else if let t = e as? String { pe = PersonEmail(address: t.trimmingCharacters(in: .whitespacesAndNewlines)) } else { pe = nil }
            if let pe, !pe.address.isEmpty, !out.contains(where: { EmailRules.key($0.address) == EmailRules.key(pe.address) }) { out.append(pe) }
        }
        return out
    }

    static func encode(_ doc: PeopleDoc) -> Data {
        let people: [[String: Any]] = doc.people.map { p in
            var o: [String: Any] = [:]
            if let e = p.extra?.data(using: .utf8), let x = (try? JSONSerialization.jsonObject(with: e)) as? [String: Any] {
                // An unknown-shaped `contact` (not an object) rides in `extra` and is written back as it was.
                for (k, v) in x where !known.contains(k) || (k == "contact" && p.contact == nil) { o[k] = v }
            }
            o.merge(["id": p.id, "name": p.name, "created": p.created, "lastUsed": p.lastUsed, "voiceEnabled": p.voiceEnabled] as [String: Any]) { _, new in new }
            if let n = p.note { o["note"] = n }
            if !p.aliases.isEmpty { o["aliases"] = p.aliases }
            if p.me { o["me"] = true }
            if !p.emails.isEmpty { o["emails"] = p.emails.map { ["address": $0.address, "from": $0.from] } }
            if let c = p.contact { o["contact"] = encodeContact(c) }
            return o
        }
        let o: [String: Any] = ["version": version, "people": people, "migrated": doc.migrated, "migrationFound": doc.migrationFound, "voice": doc.voice]
        return (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])) ?? Data()
    }

    /// Tolerant: unknown keys are ignored, a broken entry is skipped, a broken file is an empty directory.
    static func decode(_ data: Data?) -> PeopleDoc {
        guard let data, let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return PeopleDoc() }
        var seen = Set<String>()
        let people: [Person] = ((o["people"] as? [Any]) ?? []).compactMap { e in
            guard let p = e as? [String: Any], let id = p["id"] as? String, !id.trimmingCharacters(in: .whitespaces).isEmpty,
                  let name = (p["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty,
                  seen.insert(id).inserted else { return nil }
            let note = (p["note"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return Person(id: id, name: name, note: note?.isEmpty == false ? note : nil,
                          aliases: ((p["aliases"] as? [Any]) ?? []).compactMap { $0 as? String }.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty },
                          created: int64(p["created"]), lastUsed: int64(p["lastUsed"]),
                          voiceEnabled: (p["voiceEnabled"] as? Bool) ?? true, me: (p["me"] as? Bool) ?? false,
                          emails: decodeEmails(p["emails"]), contact: decodeContact(p["contact"]), extra: extra(p))
        }
        return PeopleDoc(people: people, migrated: (o["migrated"] as? Bool) ?? false, migrationFound: (o["migrationFound"] as? NSNumber)?.intValue ?? 0,
                         voice: (o["voice"] as? Bool) ?? false)
    }
    static func decode(_ text: String) -> PeopleDoc { decode(text.data(using: .utf8)) }
    private static func extra(_ p: [String: Any]) -> String? {
        let x = p.filter { !known.contains($0.key) || ($0.key == "contact" && !($0.value is [String: Any])) }
        guard !x.isEmpty, let d = try? JSONSerialization.data(withJSONObject: x, options: [.sortedKeys]) else { return nil }
        return String(decoding: d, as: UTF8.self)
    }
    private static func int64(_ v: Any?) -> Int64 { (v as? NSNumber)?.int64Value ?? 0 }
}

/// Name matching: the same rule on both platforms. `fold` maps Han text to one script (OpenCC 繁→简).
enum PersonNames {
    typealias Fold = (String) -> String
    private static let spaces = try! NSRegularExpression(pattern: "\\s+")
    /// The default labels in the three UI languages: never a person's name.
    private static let defaultLabel = try! NSRegularExpression(pattern: "^(speaker|語者|语者|說話人|说话人|說話者|说话者)\\s*\\d+$")

    static func normalize(_ s: String, _ fold: Fold = { $0 }) -> String {
        let t = fold(s.precomposedStringWithCompatibilityMapping).lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        return spaces.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: " ")
    }
    static func isDefaultLabel(_ s: String) -> Bool {
        let n = normalize(s); return defaultLabel.firstMatch(in: n, range: NSRange(n.startIndex..., in: n)) != nil
    }
    /// A user-given name worth a person: not blank, not a default label.
    static func isPersonName(_ s: String) -> Bool { !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isDefaultLabel(s) }
    static func same(_ a: String, _ b: String, _ fold: Fold = { $0 }) -> Bool { normalize(a, fold) == normalize(b, fold) }
    /// `query` matches `p` by a substring of its name or one of its aliases (case-, width- and script-insensitive).
    static func matches(_ query: String, _ p: Person, _ fold: Fold = { $0 }) -> Bool {
        let q = normalize(query, fold)
        return q.isEmpty || ([p.name] + p.aliases).contains { normalize($0, fold).contains(q) }
    }
    static func prefix(_ query: String, _ p: Person, _ fold: Fold = { $0 }) -> Bool {
        let q = normalize(query, fold)
        return ([p.name] + p.aliases).contains { normalize($0, fold).hasPrefix(q) }
    }
}

/// Directory edits, as pure functions of the document (the store persists the result).
enum PeopleOps {
    static func newId() -> String { UUID().uuidString.lowercased() }

    static func create(_ doc: PeopleDoc, _ name: String, now: Int64, id: String = newId(), me: Bool = false) -> (PeopleDoc, Person) {
        let p = Person(id: id, name: name.trimmingCharacters(in: .whitespacesAndNewlines), created: now, lastUsed: now, me: me)
        var d = doc; d.people.append(p); return (d, p)
    }
    private static func edit(_ doc: PeopleDoc, _ id: String, _ f: (inout Person) -> Void) -> PeopleDoc {
        var d = doc; for i in d.people.indices where d.people[i].id == id { f(&d.people[i]) }; return d
    }
    static func rename(_ doc: PeopleDoc, _ id: String, _ name: String) -> PeopleDoc {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if n.isEmpty { return doc }
        return edit(doc, id) { $0.name = n; $0.aliases.removeAll { $0 == n } }
    }
    static func setNote(_ doc: PeopleDoc, _ id: String, _ note: String?) -> PeopleDoc {
        let n = note?.trimmingCharacters(in: .whitespacesAndNewlines)
        return edit(doc, id) { $0.note = n?.isEmpty == false ? n : nil }
    }
    static func touch(_ doc: PeopleDoc, _ id: String, now: Int64) -> PeopleDoc { edit(doc, id) { $0.lastUsed = max($0.lastUsed, now) } }
    static func setVoiceEnabled(_ doc: PeopleDoc, _ id: String, _ on: Bool) -> PeopleDoc { edit(doc, id) { $0.voiceEnabled = on } }

    /// `from` is the same human as `into`: `from` goes, its name and aliases become `into`'s aliases.
    static func merge(_ doc: PeopleDoc, from: String, into: String) -> PeopleDoc {
        guard from != into, let a = doc.byId(from), let b = doc.byId(into) else { return doc }
        var seen = Set<String>()
        var m = b
        m.aliases = (b.aliases + [a.name] + a.aliases).filter { seen.insert($0).inserted && $0 != b.name }
        m.lastUsed = max(a.lastUsed, b.lastUsed); m.created = min(a.created, b.created)
        m.note = b.note ?? a.note; m.me = a.me || b.me
        var keys = Set<String>()
        m.emails = (b.emails + a.emails).filter { keys.insert(EmailRules.key($0.address)).inserted }
        m.contact = b.contact ?? a.contact
        var d = doc
        d.people = doc.people.filter { $0.id != from }.map { $0.id == into ? m : $0 }
        return d
    }
    static func delete(_ doc: PeopleDoc, _ id: String) -> PeopleDoc { var d = doc; d.people.removeAll { $0.id == id }; return d }

    // MARK: contact side (docs/design/speaker-contacts.md §2)

    /// Adds `address` (normalised) unless the person has it already; `makeDefault` puts it first.
    static func addEmail(_ doc: PeopleDoc, _ id: String, _ address: String, from: String = PersonEmail.fromUser, makeDefault: Bool = false) -> PeopleDoc {
        guard EmailRules.isValid(address) else { return doc }
        let a = EmailRules.normalize(address), k = EmailRules.key(a)
        return edit(doc, id) { p in
            let had = p.emails.first { EmailRules.key($0.address) == k }
            let rest = p.emails.filter { EmailRules.key($0.address) != k }
            let e = had ?? PersonEmail(address: a, from: from)
            if makeDefault || (had == nil && p.emails.isEmpty) { p.emails = [e] + rest } else if had == nil { p.emails.append(e) }
        }
    }
    static func removeEmail(_ doc: PeopleDoc, _ id: String, _ address: String) -> PeopleDoc {
        edit(doc, id) { p in p.emails.removeAll { EmailRules.key($0.address) == EmailRules.key(address) } }
    }
    static func makeDefault(_ doc: PeopleDoc, _ id: String, _ address: String) -> PeopleDoc {
        edit(doc, id) { p in
            let k = EmailRules.key(address)
            p.emails = p.emails.filter { EmailRules.key($0.address) == k } + p.emails.filter { EmailRules.key($0.address) != k }
        }
    }
    /// Links `id` to the picked contact: its addresses are added (the tapped one first, after the person's own), the
    /// link and its snapshot set. The person's name never changes; the organisation fills an empty note.
    static func linkContact(_ doc: PeopleDoc, _ id: String, _ c: PickedContact, now: Int64, chosen: String? = nil) -> PeopleDoc {
        var d = doc
        for e in [chosen].compactMap({ $0 }) + c.emails { d = addEmail(d, id, e, from: PersonEmail.fromContact) }
        return edit(d, id) { p in
            if let k = c.key {
                p.contact = ContactLink(platform: c.platform, key: k, id: c.id, name: c.name?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
                                        org: c.org?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty, linked: now, synced: now)
            }
            if p.note == nil { p.note = c.org?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty }
        }
    }
    /// "Unlink from contact": the link goes; its addresses stay as the user's, or go too (`removeEmails`).
    static func unlinkContact(_ doc: PeopleDoc, _ id: String, removeEmails: Bool) -> PeopleDoc {
        edit(doc, id) { p in
            p.contact = nil
            p.emails = removeEmails ? p.emails.filter { $0.from != PersonEmail.fromContact } : p.emails.map { PersonEmail(address: $0.address, from: PersonEmail.fromUser) }
        }
    }
    /// Other people linked to the same address-book entry as `p` (usually the same human named twice).
    static func sameContact(_ doc: PeopleDoc, _ p: Person) -> [Person] {
        guard let c = p.contact else { return [] }
        return doc.people.filter { $0.id != p.id && $0.contact?.platform == c.platform && $0.contact?.key == c.key }
    }
}

extension String {
    /// Nil when empty.
    var nonEmpty: String? { isEmpty ? nil : self }
}

/// How a directory edit rewrites one meeting's links (pure; the store applies it to every meeting).
enum LinkOps {
    typealias Links = [Int: SpeakerLink]
    /// The person was renamed: the meeting's copy of the name follows.
    static func rename(_ links: Links, _ id: String, _ name: String) -> Links {
        links.mapValues { $0.person == id ? SpeakerLink(name: name, person: id) : $0 }
    }
    static func merge(_ links: Links, from: String, into: Person) -> Links {
        links.mapValues { $0.person == from ? SpeakerLink(name: into.name, person: into.id) : $0 }
    }
    /// The person was deleted: the speakers keep the name as a plain label, or lose it (`clearNames`).
    static func delete(_ links: Links, _ id: String, clearNames: Bool) -> Links {
        clearNames ? links.filter { $0.value.person != id } : links.mapValues { $0.person == id ? SpeakerLink(name: $0.name) : $0 }
    }
    /// "This meeting's Mark is someone else": its speakers linked to `from` go to `to`.
    static func split(_ links: Links, from: String, to: Person) -> Links {
        links.mapValues { $0.person == from ? SpeakerLink(name: to.name, person: to.id) : $0 }
    }
    /// The display name of each linked speaker: the directory's, else the meeting's own (a deleted person).
    static func resolve(_ links: Links, _ doc: PeopleDoc) -> Links {
        links.mapValues { l in doc.byId(l.person).map { SpeakerLink(name: $0.name, person: $0.id) } ?? SpeakerLink(name: l.name) }
    }
}

/// The first launch with the directory: every user-given speaker name of every meeting becomes a
/// person, identical names (after `PersonNames.normalize`) one person — the user approved the
/// automatic merge, Split undoes a wrong one. Links are only added: a rerun is harmless.
enum PeopleMigration {
    /// A meeting's user-given names. `date`: epoch seconds.
    struct Meeting { var id: String; var date: Int64; var names: [Int: SpeakerLink] }
    struct Result { var doc: PeopleDoc; var links: [String: [Int: SpeakerLink]]; var created: Int }

    static func run(_ doc: PeopleDoc, _ meetings: [Meeting], fold: PersonNames.Fold, now: Int64, newId: () -> String = PeopleOps.newId) -> Result {
        var d = doc, created = 0
        var byKey: [String: Person] = [:]
        for p in d.people where byKey[PersonNames.normalize(p.name, fold)] == nil { byKey[PersonNames.normalize(p.name, fold)] = p }
        var out: [String: [Int: SpeakerLink]] = [:]
        // Newest meeting first: its spelling of a name becomes the person's.
        for m in meetings.sorted(by: { $0.date > $1.date }) {
            var links: [Int: SpeakerLink] = [:]
            for (sid, l) in m.names.sorted(by: { $0.key < $1.key }) {
                if let pid = l.person, d.byId(pid) != nil { links[sid] = l; continue }
                if !PersonNames.isPersonName(l.name) { continue }
                let key = PersonNames.normalize(l.name, fold)
                let p: Person
                if let e = byKey[key] { p = e } else {
                    let (nd, np) = PeopleOps.create(d, l.name, now: m.date, id: newId())
                    d = nd; created += 1; byKey[key] = np; p = np
                }
                d = PeopleOps.touch(d, p.id, now: m.date)
                links[sid] = SpeakerLink(name: l.name.trimmingCharacters(in: .whitespacesAndNewlines), person: p.id)
            }
            if !links.isEmpty { out[m.id] = links }
        }
        d.migrated = true; d.migrationFound += created
        return Result(doc: d, links: out, created: created)
    }
}

/// What the rename sheet lists (docs/design/speaker-tags.md §2.1), pure so both platforms rank alike.
enum PeoplePicker {
    /// Per person: in how many meetings it is linked, and the newest of them (for the homonym hint).
    struct Usage: Equatable { var meetings = 0; var lastDate: Int64 = 0; var lastTitle: String? = nil }
    struct Row: Equatable, Identifiable { var person: Person; var meetings: Int; var inMeetingAs: Int? = nil; var id: String { person.id } }
    struct Sections: Equatable {
        /// People already linked to another speaker of this meeting (picking one asks "merge?").
        var inMeeting: [Row]
        var recent: [Row]
        var all: [Row]
        /// The typed text is a new name to offer ("Add …").
        var canAdd: Bool
        /// A person whose name equals the typed text (the "same person or homonym?" question).
        var sameName: [Person]
    }
    static let recentCount = 5

    /// `inMeeting`: person → the OTHER speaker of this meeting linked to it. `exclude`: people never
    /// listed (the one the speaker is already linked to). Blank `query`: in-meeting, then the 5 most
    /// recently used, then the rest by meetings then name. A query: one filtered list (name or alias
    /// substring; prefix matches first, then recency).
    static func sections(_ doc: PeopleDoc, usage: [String: Usage], inMeeting: [String: Int], query: String,
                         fold: PersonNames.Fold = { $0 }, exclude: Set<String> = []) -> Sections {
        let people = doc.people.filter { !exclude.contains($0.id) }
        func row(_ p: Person) -> Row { Row(person: p, meetings: usage[p.id]?.meetings ?? 0, inMeetingAs: inMeeting[p.id]) }
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !q.isEmpty {
            let sameName = doc.people.filter { PersonNames.same($0.name, q, fold) }
            let hits = stable(people.filter { PersonNames.matches(q, $0, fold) }) { a, b in
                let pa = PersonNames.prefix(q, a, fold), pb = PersonNames.prefix(q, b, fold)
                if pa != pb { return pa }
                if a.lastUsed != b.lastUsed { return a.lastUsed > b.lastUsed }
                return a.name < b.name
            }
            return Sections(inMeeting: hits.filter { inMeeting[$0.id] != nil }.map(row), recent: [],
                            all: hits.filter { inMeeting[$0.id] == nil }.map(row), canAdd: PersonNames.isPersonName(q), sameName: sameName)
        }
        let rest = people.filter { inMeeting[$0.id] == nil }
        let recent = Array(stable(rest.filter { $0.lastUsed > 0 }) { $0.lastUsed > $1.lastUsed }.prefix(recentCount))
        let recentIds = Set(recent.map(\.id))
        let others = stable(rest.filter { !recentIds.contains($0.id) }) { a, b in
            let ma = usage[a.id]?.meetings ?? 0, mb = usage[b.id]?.meetings ?? 0
            if ma != mb { return ma > mb }
            return PersonNames.normalize(a.name, fold) < PersonNames.normalize(b.name, fold)
        }
        return Sections(inMeeting: people.filter { inMeeting[$0.id] != nil }.map(row), recent: recent.map(row), all: others.map(row), canAdd: false, sameName: [])
    }

    /// People sharing a normalized name with someone else: these rows need a disambiguator.
    static func homonyms(_ doc: PeopleDoc, fold: PersonNames.Fold = { $0 }) -> Set<String> {
        Set(Dictionary(grouping: doc.people) { PersonNames.normalize($0.name, fold) }.values.filter { $0.count > 1 }.flatMap { $0.map(\.id) })
    }
    /// Kotlin's sortedWith is stable; Swift's sort is not guaranteed to be.
    private static func stable<T>(_ a: [T], _ less: (T, T) -> Bool) -> [T] {
        a.enumerated().sorted { x, y in less(x.element, y.element) ? true : less(y.element, x.element) ? false : x.offset < y.offset }.map(\.element)
    }
}

/// Ask: the people a question names ("what did Mark say"), by name or alias. Retrieval only, no prompt change.
enum PeopleRouting {
    private static let latin = try! NSRegularExpression(pattern: "[\\p{Latin}\\p{Nd}]")

    static func mentioned(_ question: String, _ doc: PeopleDoc, fold: PersonNames.Fold = { $0 }) -> Set<String> {
        let q = PersonNames.normalize(question, fold)
        if q.isEmpty { return [] }
        let qr = NSRange(q.startIndex..., in: q)
        return Set(doc.people.filter { p in
            ([p.name] + p.aliases).contains { n in
                let k = PersonNames.normalize(n, fold)
                if k.isEmpty { return false }
                if latin.firstMatch(in: k, range: NSRange(k.startIndex..., in: k)) != nil {
                    // Latin names: a whole word ("Mark" must not match "market").
                    let re = try? NSRegularExpression(pattern: "(?<![\\p{L}\\p{Nd}])" + NSRegularExpression.escapedPattern(for: k) + "(?![\\p{L}\\p{Nd}])")
                    return re?.firstMatch(in: q, range: qr) != nil
                }
                // Han names: two characters at least (one character is a word, not a name).
                return k.count >= 2 && q.contains(k)
            }
        }.map(\.id))
    }

    /// Meeting → its speakers linked to one of `people` (the meetings Ask routes first).
    static func meetingsOf(_ links: [String: [Int: SpeakerLink]], _ people: Set<String>) -> [String: Set<Int>] {
        if people.isEmpty { return [:] }
        var out: [String: Set<Int>] = [:]
        for (id, m) in links {
            let ks = Set(m.filter { $0.value.person.map(people.contains) ?? false }.keys)
            if !ks.isEmpty { out[id] = ks }
        }
        return out
    }
}

/// Phase 2: a person the voice of a speaker resembles. `level` 3 = likely (●●●), 2 = possible (●●○).
struct VoiceSuggestion: Equatable { var person: String; var level: Int; var score: Float }

/// A re-run renumbers the speakers. Names (and person links) are carried over by time overlap, no ML
/// (docs/design/speaker-tags.md §3.4): a new speaker inherits an old speaker's name when the old one
/// covers at least `newShare` of the new speaker's speech AND the new one covers at least `oldShare`
/// of the old speaker's. Anything less leaves the speaker unnamed.
enum SpeakerCarryOver {
    static let newShare = 0.6
    static let oldShare = 0.5
    struct Turn { var start: Double; var end: Double; var speaker: Int? }

    /// old speaker id → new speaker id, for the old speakers that pass both shares.
    static func mapping(_ old: [Turn], _ new: [Turn]) -> [Int: Int] {
        var oldTime: [Int: Double] = [:], newTime: [Int: Double] = [:]
        for t in old { if let s = t.speaker { oldTime[s, default: 0] += max(0, t.end - t.start) } }
        for t in new { if let s = t.speaker { newTime[s, default: 0] += max(0, t.end - t.start) } }
        struct K: Hashable { let o: Int, n: Int }
        var overlap: [K: Double] = [:]
        let a = old.filter { $0.speaker != nil && $0.end > $0.start }.sorted { $0.start < $1.start }
        let b = new.filter { $0.speaker != nil && $0.end > $0.start }.sorted { $0.start < $1.start }
        var j0 = 0
        for x in a {
            while j0 < b.count && b[j0].end <= x.start { j0 += 1 }
            var j = j0
            while j < b.count && b[j].start < x.end {
                let y = b[j], ov = min(x.end, y.end) - max(x.start, y.start)
                if ov > 0 { overlap[K(o: x.speaker!, n: y.speaker!), default: 0] += ov }
                j += 1
            }
        }
        var out: [Int: Int] = [:]
        for (k, ov) in overlap.sorted(by: { $0.value != $1.value ? $0.value > $1.value : ($0.key.o, $0.key.n) < ($1.key.o, $1.key.n) }) {
            if out[k.o] != nil || out.values.contains(k.n) { continue }
            if ov >= newShare * (newTime[k.n] ?? 0) && ov >= oldShare * (oldTime[k.o] ?? 0) { out[k.o] = k.n }
        }
        return out
    }

    /// The old names re-keyed by the new speaker ids (dropped where no new speaker inherits).
    static func carry<N>(_ old: [Turn], _ oldNames: [Int: N], _ new: [Turn]) -> [Int: N] {
        if oldNames.isEmpty || new.isEmpty { return [:] }
        let m = mapping(old, new)
        var out: [Int: N] = [:]
        for (o, n) in oldNames { if let k = m[o] { out[k] = n } }
        return out
    }
    static func turns(_ lines: [Utterance]) -> [Turn] { lines.map { Turn(start: $0.start, end: $0.end, speaker: $0.speaker) } }
}

/// Which audio of a speaker a voice vector is computed from (docs/design/speaker-tags.md §4.3): its
/// lines that overlap no other speaker's line, trimmed `trim` s at each end and at least `minSeg` s
/// long, taken round-robin over the meeting's thirds (longest first in each third, so a voice that
/// drifts during the meeting is sampled everywhere), up to `cap` s. Under `floor` s: no vector.
enum CleanSpeech {
    static let minSeg = 1.5
    static let trim = 0.1
    static let cap = 60.0
    static let floor = 10.0
    struct Span: Equatable { var start: Double; var end: Double; var sec: Double { end - start } }

    static func spans(_ turns: [SpeakerCarryOver.Turn], speaker: Int, cap: Double = cap) -> [Span] {
        var out: [Span] = [], have = 0.0
        for s in ordered(turns, speaker: speaker) {
            if have >= cap { break }
            let take = min(s.sec, cap - have)
            out.append(Span(start: s.start, end: s.start + take)); have += take
        }
        return out.sorted { $0.start < $1.start }
    }

    /// Every clean line of `speaker` in the order a voice vector consumes them: round-robin over the
    /// meeting's thirds, longest first in each third. The voice lane reads them in this order through
    /// `VoiceGate` until it has `cap` s of actual voice (a line can hold long silences).
    static func ordered(_ turns: [SpeakerCarryOver.Turn], speaker: Int) -> [Span] {
        let sorted = turns.filter { $0.end > $0.start }.sorted { $0.start < $1.start }
        let others = sorted.filter { $0.speaker != speaker }
        var clean: [Span] = []
        var j = 0
        for t in sorted where t.speaker == speaker {
            while j < others.count && others[j].end <= t.start { j += 1 }
            var k = j, overlaps = false
            while k < others.count && others[k].start < t.end { if others[k].end > t.start { overlaps = true; break }; k += 1 }
            if overlaps { continue }
            let s = Span(start: t.start + trim, end: t.end - trim)
            if s.sec >= minSeg { clean.append(s) }
        }
        if clean.isEmpty { return [] }
        let total = max(sorted.map(\.end).max() ?? 0, 1e-6)
        var thirds: [[Span]] = (0..<3).map { th in
            clean.filter { min(max(Int(($0.start / total) * 3), 0), 2) == th }.enumerated()
                .sorted { $0.element.sec != $1.element.sec ? $0.element.sec > $1.element.sec : $0.offset < $1.offset }.map(\.element)
        }
        var out: [Span] = []
        while thirds.contains(where: { !$0.isEmpty }) {
            for i in 0..<3 where !thirds[i].isEmpty { out.append(thirds[i].removeFirst()) }
        }
        return out
    }

    /// A line with less text per second than this share of the meeting's median is mostly pause.
    static let sparse = 0.4
    static let nobody = -9

    /// The turns a voice vector may use: a line far sparser in text than the meeting's usual (a long
    /// pause around a whiteboard, a slide change, room noise) keeps its time (it still marks overlap)
    /// but belongs to nobody. Relative to the meeting, so the same rule holds in every language.
    static func voiceTurns(_ lines: [Utterance]) -> [SpeakerCarryOver.Turn] {
        func rate(_ u: Utterance) -> Double { Double(u.text.filter { !$0.isWhitespace }.count) / max(0.5, u.end - u.start) }
        let rates = lines.filter { $0.end > $0.start }.map(rate).sorted()
        if rates.isEmpty { return SpeakerCarryOver.turns(lines) }
        let median = rates[rates.count / 2]
        return lines.map { SpeakerCarryOver.Turn(start: $0.start, end: $0.end, speaker: rate($0) >= sparse * median ? $0.speaker : nobody) }
    }
    /// Seconds of clean speech `speaker` has (capped), for the "not enough speech" note.
    static func seconds(_ lines: [Utterance], speaker: Int) -> Double { spans(SpeakerCarryOver.turns(lines), speaker: speaker).reduce(0) { $0 + $1.sec } }
    /// Where to play a short clip of `speaker`: its longest clean line, else its first line.
    static func sampleStart(_ lines: [Utterance], speaker: Int) -> Double? {
        spans(SpeakerCarryOver.turns(lines), speaker: speaker, cap: .greatestFiniteMagnitude).max { $0.sec < $1.sec }?.start
            ?? lines.first { $0.speaker == speaker }?.start
    }
}
