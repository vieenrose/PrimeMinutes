import Foundation
import SwiftUI

/// One finished (or stopped) meeting, saved as `<id>.json`.
/// Action items (Android ReaderLane.actions): the reader's ACTION notes after proposal reclassification,
/// one "- text [m:ss]" line each; nil when there are none. A user edit (Android editingActions) replaces them
/// until the next reading, which builds a fresh Session.
extension Session {
    var actionItems: String? {
        if let e = actionsEdited { let t = e.trimmingCharacters(in: .whitespacesAndNewlines); return t.isEmpty || t == "-" ? nil : t }
        return derivedActions
    }
    var derivedActions: String? {
        let a = ModelCard.default.reader.actions(notes)
        return a.isEmpty ? nil : a.map { "- \($0.text.trimmingCharacters(in: CharacterSet(charactersIn: "。"))) [\($0.ts)]" }.joined(separator: "\n")
    }
}

struct Session: Identifiable, Codable, Hashable {
    var id = UUID()
    var date = Date()
    var title: String
    var summary: String
    var seconds: Double
    var lines: [Utterance]
    var notes: [Note]
    var audio: String? = nil      // file name in Application Support/audio
    var speakerNames: [String: String]? = nil   // speaker index -> custom name
    /// Speaker index → people directory id (People.swift): the named speakers linked to a person. Their name in
    /// speakerNames is the person's, rewritten here by every directory edit (this file is the authority on iOS).
    var speakerPeople: [String: String]? = nil
    var reader: String? = nil     // reader model id that wrote the summary (Android sessionModels.first)
    var actionsEdited: String? = nil   // the action items as the user edited them; nil = derived from the notes
    var legacyNotes: String? = nil     // v2 NOTES text of a session written by the older summarizer (MeetingNotes)
    var readerGuard: ReaderGuard? = nil   // the last reading's off-protocol counts (model guardrail, DEV diagnostics)
    /// When the meeting was captured, epoch seconds: the recording's start, an import's media creation date
    /// (MediaDate); -1: an import whose file has no date (the mail then leaves the time out); nil: a meeting saved
    /// before the field, read as `date` as before (Android Entry.captureStart).
    var captureStart: Double? = nil
    /// The meeting's start for the mail's date and time; nil when unknown (an import without a media date).
    var meetingStart: Date? { captureStart.map { $0 < 0 ? nil : Date(timeIntervalSince1970: $0) } ?? date }
    /// Processed, but nothing was said (SpeechCheck): shown as such, never as "Done", and never read by the reader.
    var noSpeech: Bool { !SpeechCheck.hasSpeech(lines.map(\.text)) }
    func name(_ spk: Int) -> String { Self.name(spk, speakerNames) }
    static func name(_ spk: Int, _ names: [String: String]?) -> String { names?[String(spk)].flatMap { $0.isEmpty ? nil : $0 } ?? L("speaker_n", spk + 1) }
    static func == (a: Session, b: Session) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

/// The meeting library: one JSON file per session in Application Support/sessions.
actor LibraryStore {
    let root: URL
    init(root: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("sessions")) {
        self.root = root
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    private func file(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString + ".json") }

    /// Newest first; an unreadable file is skipped, not fatal.
    func all() -> [Session] {
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { try? dec.decode(Session.self, from: Data(contentsOf: $0)) }
            .filter(Self.devShown)
            .sorted { $0.date > $1.date }
    }
    /// DEV (README media shot on a phone that holds a real library): VOX_ONLY=<token>,… lists only the meetings
    /// whose id starts with, or whose title contains, a token, or that are newer than a `since:<epoch>` token.
    private static func devShown(_ s: Session) -> Bool {
        guard let only = Dev.env["VOX_ONLY"] else { return true }
        return only.split(separator: ",").map(String.init).contains { t in
            if t.hasPrefix("since:"), let e = Double(t.dropFirst(6)) { return s.date.timeIntervalSince1970 >= e }
            return s.id.uuidString.hasPrefix(t) || s.title.contains(t)
        }
    }
    func save(_ s: Session) throws {
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
        try enc.encode(s).write(to: file(s.id), options: .atomic)   // atomic: a crash never leaves half a file
    }
    func delete(_ id: UUID) { try? FileManager.default.removeItem(at: file(id)); AskHistory.clear(id, root: root) }
}

/// One speaker → colour mapping for the live capture and the saved session (they differed: speaker 1
/// turned from red to blue when the recording stopped). The canonical list lives on `Vox.speaker`.
var speakerPalette: [Color] { Vox.speaker }
