import Foundation
import AVFoundation

/// The parts of SessionFile that need the app's Session (SessionFileCore.swift holds the container itself).
extension SessionFile {
    static func manifest(_ s: Session) -> Manifest {
        var m = Manifest(); m.title = s.title; m.title_user_set = s.titleUserSet == true ? true : nil; m.summary = s.summary
        m.notes = s.notes.isEmpty ? s.legacyNotes : s.notes.map { ReaderProtocol.render($0) }.joined(separator: "\n")
        m.action_items = s.actionItems ?? "-"
        m.asr_backend = "ios"
        m.asr_model = "xasr-stream-q8-octav+sortformer-int8-f380"   // the LiteRT pair NemoEngine loads; same id as Android
        m.llm_model = s.reader
        m.speaker_names = Dictionary(uniqueKeysWithValues: (s.speakerNames ?? [:]).filter { !$0.value.isEmpty }.map { k, v in (k, Manifest.SN(name: v, confidence: "user", reason: "", person: s.speakerPeople?[k])) })
        m.utterances = s.lines.enumerated().map { Manifest.U(index: $0.offset, start: $0.element.start, end: $0.element.end, text: $0.element.text, speaker: $0.element.speaker, token_times: $0.element.tokenTimes) }
        return m
    }

    /// The AI notes journal as written by `manifest` / Android's agent: one "#id [m:ss] (TAG) text" per line.
    static func notes(_ t: String?) -> [Note] {
        guard let t else { return [] }
        let re = try! NSRegularExpression(pattern: #"^#(\d+)\s+\[([0-9:]+)\]\s+(?:\(([^)]+)\)\s+)?(.*)$"#)
        return t.split(separator: "\n").compactMap { l in
            let l = String(l); guard let m = re.firstMatch(in: l, range: NSRange(l.startIndex..., in: l)) else { return nil }
            func g(_ i: Int) -> String? { Range(m.range(at: i), in: l).map { String(l[$0]) } }
            return Note(id: Int(g(1) ?? "") ?? 0, window: 0, ts: g(2) ?? "", tag: g(3), text: g(4) ?? "")
        }
    }

    static func session(_ m: Manifest, audio: String?) -> Session {
        let lines = (m.utterances ?? []).map { Utterance(speaker: $0.speaker ?? 0, start: $0.start, end: $0.end, text: $0.text, tokenTimes: $0.token_times) }
        var names: [String: String] = [:]
        // Names only: a `person` id (a v1 file, or Android's own library) names nothing on this device.
        for (k, v) in m.speaker_names ?? [:] where !v.name.isEmpty { names[k] = v.name }
        var s = Session(title: m.title ?? "", summary: m.summary ?? "", seconds: lines.last?.end ?? 0, lines: lines, notes: notes(m.notes), audio: audio, speakerNames: names.isEmpty ? nil : names)
        // v2: the sender's meeting id (a re-import is recognized by it) and when the meeting took place.
        if let id = m.session_id.flatMap(UUID.init(uuidString:)) { s.id = id }
        if let d = parseIso(m.created_at) { s.date = d; s.captureStart = d.timeIntervalSince1970 }
        if m.user_edited?.summary == true { s.summaryUserSet = true }
        if m.user_edited?.lines == true { s.linesUserEdited = true }
        s.titleUserSet = m.title_user_set == true ? true : nil
        if s.notes.isEmpty, let t = m.notes, MeetingNotes.parse(t) != nil { s.legacyNotes = t }   // Android: v2 NOTES first, else the journal
        s.reader = m.llm_model   // Android sessionModels: the summary stays attributed to the model that wrote it
        s.receivedReader = m.llm_model   // the sender's summary: not stale here (UserEdits.summaryStale)
        s.received = true   // the library's "Received" source
        // Android saves its action items as shown, edits included: keep them when they are not what the notes give.
        if let a = m.action_items?.trimmingCharacters(in: .whitespacesAndNewlines), a != "-", !a.isEmpty, a != s.derivedActions { s.actionsEdited = a }
        return s
    }

    // MARK: export
    /// Where exported meetings wait for the share sheet: emptied before each export and at launch.
    static var shareDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true) }
    static func clearShareDir() {
        let fm = FileManager.default
        try? fm.removeItem(at: shareDir)
        for f in (try? fm.contentsOfDirectory(at: fm.temporaryDirectory, includingPropertiesForKeys: nil)) ?? [] where f.lastPathComponent.hasPrefix("primeminutes_raw_") { try? fm.removeItem(at: f) }
    }

    /// The meeting as a shared file: AAC .m4a with the share manifest (no people links; its id, date, script).
    /// `progress` gets 0…1 while the audio encodes; a cancelled Task cancels the encode and returns nil.
    static func export(_ s: Session, progress: @escaping @Sendable (Float) -> Void = { _ in }) async -> URL? {
        guard let a = s.audio else { return nil }
        let asset = AVURLAsset(url: JobQueue.audioDir.appendingPathComponent(a))
        clearShareDir()
        let dir = shareDir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let raw = dir.appendingPathComponent("primeminutes_raw_\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: raw) }
        guard let ex = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else { return nil }
        ex.outputURL = raw; ex.outputFileType = .m4a
        nonisolated(unsafe) let session = ex
        let poll = Task { while !Task.isCancelled { progress(session.progress); try? await Task.sleep(nanoseconds: 200_000_000) } }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in session.exportAsynchronously { c.resume() } }
        } onCancel: { session.cancelExport() }
        poll.cancel()
        guard ex.status == .completed, !Task.isCancelled else { return nil }
        progress(1)
        let man = forShare(manifest(s), sessionId: s.id.uuidString, createdAt: s.meetingStart, script: AppLanguage.current.script.rawValue,
                           userEdited: .init(summary: s.summaryUserSet == true, lines: s.linesUserEdited == true))
        guard let blob = blob(man) else { return nil }
        let safe = String(s.title.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || "._-".unicodeScalars.contains($0) ? Character($0) : "_" }.prefix(60))
        let name = safe.replacingOccurrences(of: "_{2,}", with: "_", options: .regularExpression)
        let out = dir.appendingPathComponent((name.trimmingCharacters(in: CharacterSet(charactersIn: "_")).isEmpty ? "primeminutes_session" : name) + "." + shareExtension)
        try? FileManager.default.removeItem(at: out)
        return write(src: raw, dest: out, title: s.title, summary: s.summary, lrc: Export.text(s, .lrc), blob: blob) ? out : nil
    }
}
