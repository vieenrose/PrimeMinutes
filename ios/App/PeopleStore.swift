import Foundation

/// The people directory on disk (iOS): Application Support/people/people.json (PeopleCodec, the same JSON as
/// Android). A meeting's links live in its own session JSON (`speakerNames` + `speakerPeople`), which is the
/// authority on iOS: a directory edit (rename everywhere, merge, delete, split) rewrites the affected session
/// files, so every reader of a session (transcript, export, search index, Ask) sees the directory's names.
/// Local only: Application Support is excluded from backups (Model.excludeFromBackup).
@MainActor final class PeopleStore: ObservableObject {
    static let shared = PeopleStore()
    @Published private(set) var doc: PeopleDoc
    /// The library whose sessions a directory edit rewrites (set by Model).
    weak var library: SessionLibrary?

    nonisolated static let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("people/people.json")
    /// The directory as last saved, for readers off the main actor (Ask retrieval).
    nonisolated(unsafe) private static var snapshot = PeopleDoc()   // guarded by snapLock
    private nonisolated static let snapLock = NSLock()
    nonisolated static var current: PeopleDoc { snapLock.lock(); defer { snapLock.unlock() }; return snapshot }

    private init() {
        let d = PeopleCodec.decode(try? Data(contentsOf: Self.url))
        doc = d
        Self.snapLock.lock(); Self.snapshot = d; Self.snapLock.unlock()
    }

    /// One script for Han names (繁/简) when comparing: character-level t2s, like the search's.
    private nonisolated static let cc = OpenCC.transcript(.simplified)
    nonisolated static func fold(_ s: String) -> String { cc.convert(s) }

    private func mutate(_ f: (PeopleDoc) -> PeopleDoc) {
        let nd = f(doc)
        guard nd != doc else { return }
        do {
            try FileManager.default.createDirectory(at: Self.url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try PeopleCodec.encode(nd).write(to: Self.url, options: .atomic)
        } catch { StatusLog.add("people: could not write the directory: \(error)") }
        doc = nd
        Self.snapLock.lock(); Self.snapshot = nd; Self.snapLock.unlock()
    }
    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970) }

    @discardableResult func create(_ name: String, me: Bool = false) -> Person {
        var made: Person!
        mutate { d in let (nd, p) = PeopleOps.create(d, name, now: Self.now(), me: me); made = p; return nd }
        return made
    }
    func touch(_ id: String) { mutate { PeopleOps.touch($0, id, now: Self.now()) } }
    func setNote(_ id: String, _ note: String?) { mutate { PeopleOps.setNote($0, id, note) } }
    func setVoiceEnabled(_ id: String, _ on: Bool) { mutate { PeopleOps.setVoiceEnabled($0, id, on) } }
    func dismissMigrationCard() { mutate { var d = $0; d.migrationFound = 0; return d } }

    // MARK: a session's links

    /// A session's named speakers and their person links (unknown ids kept: LinkOps.resolve reads them as plain names).
    nonisolated static func links(_ s: Session) -> [Int: SpeakerLink] {
        var out: [Int: SpeakerLink] = [:]
        for (k, v) in s.speakerNames ?? [:] {
            let n = v.trimmingCharacters(in: .whitespacesAndNewlines)
            if let i = Int(k), !n.isEmpty { out[i] = SpeakerLink(name: v, person: s.speakerPeople?[k]) }
        }
        return out
    }
    /// Writes `links` as the session's names and person links.
    nonisolated static func apply(_ links: [Int: SpeakerLink], to s: inout Session) {
        let names = Dictionary(uniqueKeysWithValues: links.map { (String($0.key), $0.value.name) })
        let people = Dictionary(uniqueKeysWithValues: links.compactMap { k, l in l.person.map { (String(k), $0) } })
        s.speakerNames = names.isEmpty ? nil : names
        s.speakerPeople = people.isEmpty ? nil : people
    }
    /// The meeting's linked speakers and their people (unnamed speakers and plain labels absent; Android peopleOf).
    func peopleOf(_ s: Session) -> [Int: Person] {
        var out: [Int: Person] = [:]
        for (k, l) in Self.links(s) { if let p = doc.byId(l.person) { out[k] = p } }
        return out
    }
    /// Person → the sessions it is linked in, newest first (only people of the directory).
    func meetingsByPerson(_ sessions: [Session]) -> [String: [Session]] {
        var out: [String: [Session]] = [:]
        for s in sessions {
            for p in Set((s.speakerPeople ?? [:]).values) where doc.byId(p) != nil { out[p, default: []].append(s) }
        }
        return out.mapValues { $0.sorted { $0.date > $1.date } }
    }
    func usage(_ sessions: [Session]) -> [String: PeoplePicker.Usage] {
        meetingsByPerson(sessions).mapValues { ss in
            PeoplePicker.Usage(meetings: ss.count, lastDate: Int64(ss[0].date.timeIntervalSince1970), lastTitle: ss[0].title)
        }
    }
    /// Ask: meeting id → the speakers there linked to a person the question names (empty when it names no one).
    nonisolated static func meetingsNaming(_ question: String, _ sessions: [Session]) -> [String: Set<Int>] {
        let d = current
        guard !d.people.isEmpty else { return [:] }
        let named = PeopleRouting.mentioned(question, d, fold: fold)
        guard !named.isEmpty else { return [:] }
        var links: [String: [Int: SpeakerLink]] = [:]
        for s in sessions where s.speakerPeople != nil { links[s.id.uuidString] = Self.links(s) }
        return PeopleRouting.meetingsOf(links, named)
    }

    // MARK: directory edits that touch meetings

    /// Rewrites every session's links with `f`; the changed ones are saved, re-indexed and announced (an open
    /// SessionView takes them through voxSessionSaved). Returns their ids.
    @discardableResult private func rewriteSessions(only: Set<UUID>? = nil, _ f: (UUID, [Int: SpeakerLink]) -> [Int: SpeakerLink]) async -> Set<UUID> {
        guard let library else { return [] }
        var touched = Set<UUID>()
        for var s in await library.store.all() where only?.contains(s.id) ?? true {
            let old = Self.links(s), nl = f(s.id, old)
            guard nl != old else { continue }
            Self.apply(nl, to: &s)
            do { try await library.store.save(s) } catch { StatusLog.add("people: could not save \(s.id): \(error)"); continue }
            touched.insert(s.id)
            LibraryIndexer.shared.request(s)
            NotificationCenter.default.post(name: .voxSessionSaved, object: s)
        }
        if !touched.isEmpty { await library.refresh() }
        return touched
    }

    func renameEverywhere(_ id: String, _ name: String) async {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        mutate { PeopleOps.rename($0, id, n) }
        await rewriteSessions { LinkOps.rename($1, id, n) }
    }
    func merge(from: String, into: String) async {
        guard from != into, let target = doc.byId(into) else { return }
        mutate { PeopleOps.merge($0, from: from, into: into) }
        await rewriteSessions { LinkOps.merge($1, from: from, into: target) }
        VoiceHooks.impl?.onMerge(from: from, into: into)
    }
    func delete(_ id: String, clearNames: Bool) async {
        mutate { PeopleOps.delete($0, id) }
        await rewriteSessions { LinkOps.delete($1, id, clearNames: clearNames) }
        VoiceHooks.impl?.onDeletePerson(id)
    }
    /// "This meeting's `personId` is someone else": its speakers there go to a new person `newName`.
    @discardableResult func split(_ session: UUID, from personId: String, newName: String) async -> Person? {
        let n = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return nil }
        let p = create(n)
        await rewriteSessions(only: [session]) { LinkOps.split($1, from: personId, to: p) }
        VoiceHooks.impl?.onSplit(session: session, from: personId, to: p.id)
        return p
    }
    /// A meeting was deleted (SessionLibrary.remove): its links went with its file; announce it (voice samples).
    func onMeetingDeleted(_ id: UUID) { objectWillChange.send(); VoiceHooks.impl?.onMeetingDeleted(id) }

    // MARK: migration

    /// Gathers the speaker names saved before the directory existed (once): identical names become one person
    /// (PeopleMigration). Only links are added: a name a meeting had stays, now with its person.
    func migrateIfNeeded() async {
        guard !doc.migrated, let library, Dev.env["VOX_ONLY"] == nil else { return }   // VOX_ONLY lists a subset: never migrate from it
        let sessions = await library.store.all()
        let meetings = sessions.compactMap { s -> PeopleMigration.Meeting? in
            let l = Self.links(s)
            return l.isEmpty ? nil : PeopleMigration.Meeting(id: s.id.uuidString, date: Int64(s.date.timeIntervalSince1970), names: l)
        }
        var result: PeopleMigration.Result!
        mutate { d in result = PeopleMigration.run(d, meetings, fold: Self.fold, now: Self.now()); return result.doc }
        let byId = result.links
        await rewriteSessions(only: Set(byId.keys.compactMap(UUID.init))) { sid, cur in
            var m = cur
            for (k, l) in byId[sid.uuidString] ?? [:] {
                if let c = cur[k], c.person == nil || self.doc.byId(c.person) == nil { m[k] = SpeakerLink(name: c.name, person: l.person) }
            }
            return m
        }
        StatusLog.add("people: migration made \(result.created) people from \(meetings.count) meetings")
    }
}

/// Phase 2 voice data hooks (no-ops until a voice store is attached), as Android VoiceHooks.
@MainActor enum VoiceHooks {
    protocol Impl: AnyObject {
        func onMerge(from: String, into: String)
        func onDeletePerson(_ id: String)
        func onSplit(session: UUID, from: String, to: String)
        func onMeetingDeleted(_ id: UUID)
    }
    static weak var impl: Impl?
}

/// Phase 2: a name the user confirmed (or removed) teaches (or unteaches) that person's voice. No-op without voice.
@MainActor enum VoiceLinks {
    protocol Impl: AnyObject {
        func confirmed(session: UUID, speaker: Int, person: String)
        func unlinked(session: UUID, speaker: Int, person: String)
    }
    static weak var impl: Impl?
}

extension PeopleStore {
    /// DEV (device checks without taps): VOX_PEOPLE_DO=op;op… run once at launch, then Documents/people-dev.txt lists
    /// the directory and every linked meeting. Meetings are named by id prefix. Ops: link:<id>:<speaker>:<name>
    /// (an existing person by name, else a new one), clear:<id>:<speaker>, rename:<name>:<new>, merge:<from>:<into>,
    /// delete:<name>:keep|clear, split:<id>:<name>:<new>, remigrate, dump.
    func devRun(_ spec: String) async {
        guard let library else { return }
        func person(_ n: String) -> Person? { doc.people.first { PersonNames.same($0.name, n, Self.fold) } }
        func session(_ p: String) async -> Session? { await library.store.all().first { $0.id.uuidString.hasPrefix(p.uppercased()) } }
        for op in spec.split(separator: ";").map(String.init) {
            let a = op.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            switch a.first {
            case "link" where a.count == 4:
                guard var s = await session(a[1]), let k = Int(a[2]) else { continue }
                let p = person(a[3]) ?? create(a[3])
                var l = Self.links(s); l[k] = SpeakerLink(name: p.name, person: p.id); Self.apply(l, to: &s)
                try? await library.store.save(s); touch(p.id); LibraryIndexer.shared.request(s)
            case "clear" where a.count == 3:
                guard var s = await session(a[1]), let k = Int(a[2]) else { continue }
                var l = Self.links(s); l[k] = nil; Self.apply(l, to: &s); try? await library.store.save(s); LibraryIndexer.shared.request(s)
            case "rename" where a.count == 3: if let p = person(a[1]) { await renameEverywhere(p.id, a[2]) }
            case "merge" where a.count == 3: if let f = person(a[1]), let i = person(a[2]) { await merge(from: f.id, into: i.id) }
            case "delete" where a.count == 3: if let p = person(a[1]) { await delete(p.id, clearNames: a[2] == "clear") }
            case "split" where a.count == 4: if let s = await session(a[1]), let p = person(a[2]) { await split(s.id, from: p.id, newName: a[3]) }
            case "name" where a.count == 4:   // a plain name, no person (as saved before the directory)
                guard var s = await session(a[1]), let k = Int(a[2]) else { continue }
                var l = Self.links(s); l[k] = SpeakerLink(name: a[3]); Self.apply(l, to: &s); try? await library.store.save(s)
            case "clone" where a.count == 3:   // a test copy of a meeting (same audio file), titled SDTEST <title>
                guard var s = await session(a[1]) else { continue }
                s.id = UUID(); s.title = "SDTEST " + a[2]; s.speakerNames = nil; s.speakerPeople = nil; s.date = Date()
                try? await library.store.save(s)
            case "dropclones":   // removes the test copies (their files only: the audio belongs to the original)
                for s in await library.store.all() where s.title.hasPrefix("SDTEST ") { await library.store.delete(s.id); LibraryIndexer.shared.forget(s.id) }
            case "forget" where a.count == 2: if let p = person(a[1]) { mutate { PeopleOps.delete($0, p.id) } }
            case "forgetall": mutate { var d = $0; d.people = []; d.migrationFound = 0; return d }   // test cleanup
            case "rerun" where a.count == 2: if let s = await session(a[1]) { Model.shared.runner.rerun(s, transcribe: true) }
            case "remigrate": mutate { var d = $0; d.migrated = false; return d }; await migrateIfNeeded()
            default: break
            }
        }
        await library.refresh()
        var out = "ops \(spec)\npeople \(doc.people.count) migrated=\(doc.migrated) found=\(doc.migrationFound)\n"
        for p in doc.people { out += "  \(p.id.prefix(8)) \(p.name)" + (p.aliases.isEmpty ? "" : " aliases=\(p.aliases)") + (p.note.map { " note=\($0)" } ?? "") + " last=\(p.lastUsed)\n" }
        if spec.contains("list") { for s in library.sessions { out += "# \(s.id.uuidString.prefix(8)) \(Int(s.seconds))s spk=\(Set(s.lines.map(\.speaker)).count) audio=\(s.audio != nil) \(s.title.prefix(30))\n" } }
        for s in library.sessions where s.speakerNames != nil {
            out += "\(s.id.uuidString.prefix(8)) \(s.title.prefix(30)): " + Self.links(s).sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value.name)" + ($0.value.person.map { "→\($0.prefix(8))" } ?? "") }.joined(separator: ", ") + "\n"
        }
        try? out.write(to: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("people-dev.txt"), atomically: true, encoding: .utf8)
    }
}
