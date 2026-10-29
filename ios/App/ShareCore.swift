import Foundation

/// Share v2 (docs: one "Share" entry, pick WHAT then WHERE): the decisions behind the sheet, without UI so the host
/// tests check them. WHAT = the whole session (.pmin), the PDF (± the audio as a separate .m4a), or the audio alone.
enum ShareWhat: String, CaseIterable, Identifiable {
    case session, pdf, audio
    var id: String { rawValue }
}

/// WHERE the files go. `cloud` = the document picker in export mode, which lists iCloud Drive and every installed
/// File Provider (Google Drive, Dropbox, OneDrive…); `icloud` opens the same picker for iCloud Drive.
enum ShareWhere: String, CaseIterable { case nearby, mail, icloud, cloud, other }

enum ShareCore {
    /// Above this the audio switch of the PDF option starts off (the size is shown either way).
    static let largeAudio: Int64 = 25_000_000
    /// Mail servers commonly refuse messages over ~20–25 MB: above this the mail row warns.
    static let mailLimit: Int64 = 20_000_000
    /// The cloud apps whose File Provider extension shows in the document picker once installed. iOS gives no reliable
    /// way to tell which ones are installed (no URL-scheme query for them without LSApplicationQueriesSchemes, and
    /// the providers' presence is the picker's business), so they share one row that opens the picker.
    static let cloudProviders = ["Google Drive", "Dropbox", "OneDrive"]

    /// What the meeting can give: the session needs audio and a transcript, the PDF a transcript, the audio a file.
    static func available(hasLines: Bool, hasAudio: Bool) -> [ShareWhat] {
        ShareWhat.allCases.filter { w in
            switch w { case .session: return hasAudio && hasLines; case .pdf: return hasLines; case .audio: return hasAudio }
        }
    }

    /// The preselected option: a recorded (or received) meeting → the whole session; an online-source meeting (podcast)
    /// → the PDF without audio (the session and the audio carry someone else's recording and need an explicit choice).
    static func defaultWhat(online: Bool, hasLines: Bool, hasAudio: Bool) -> ShareWhat? {
        let a = available(hasLines: hasLines, hasAudio: hasAudio)
        let order: [ShareWhat] = online ? [.pdf, .session, .audio] : [.session, .pdf, .audio]
        return order.first { a.contains($0) }
    }

    /// The PDF's "attach the recording" switch starts on only for an own recording of a reasonable size.
    static func defaultAttachAudio(online: Bool, audioBytes: Int64) -> Bool { !online && audioBytes > 0 && audioBytes <= largeAudio }

    /// Whether the choice carries the recording.
    static func carriesAudio(_ w: ShareWhat, attachAudio: Bool) -> Bool { w != .pdf || attachAudio }

    enum Hint: Equatable { case none, voices, copyright }
    /// The hint under the options: a recording's voices are the participants' own; an online source's audio is
    /// someone else's work.
    static func hint(_ w: ShareWhat, attachAudio: Bool, online: Bool) -> Hint {
        guard carriesAudio(w, attachAudio: attachAudio) else { return .none }
        return online ? .copyright : .voices
    }

    /// The size of the .m4a the share writes: an AAC source is copied as is; anything else (the recorder's WAV,
    /// MP3…) is re-encoded by AVAssetExportPresetAppleM4A at about `aacBytesPerSecond`.
    static let aacBytesPerSecond: Double = 8_000
    static func audioEstimate(sourceBytes: Int64, sourceExtension: String, seconds: Double) -> Int64 {
        if ["m4a", "aac", "mp4", "pmin"].contains(sourceExtension.lowercased()) { return sourceBytes }
        let enc = Int64(max(0, seconds) * aacBytesPerSecond)
        return sourceBytes > 0 ? min(sourceBytes, max(enc, 1)) : enc
    }
    /// The .pmin: the encoded audio plus the manifest (the transcript, as JSON, about 3× its text).
    static func sessionEstimate(audio: Int64, textChars: Int) -> Int64 { audio + Int64(textChars * 3) + 4_096 }

    /// The bytes of a choice (the PDF's own size is known once rendered; `pdf` is 0 until then).
    static func total(_ w: ShareWhat, attachAudio: Bool, session: Int64, pdf: Int64, audio: Int64) -> Int64 {
        switch w { case .session: return session; case .pdf: return pdf + (attachAudio ? audio : 0); case .audio: return audio }
    }

    /// The shared files' base name: `2026-10-29 Weekly sync`, characters Files / mail / FAT refuse replaced, at most
    /// 60 title characters; no title → `2026-10-29 Prime Minutes`.
    static func fileBase(title: String, date: Date?, timeZone: TimeZone = .current) -> String {
        var t = title.map { "/\\:*?\"<>|\n\r\t".contains($0) ? " " : $0 }.reduce("") { $0 + String($1) }
        t = t.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: " ."))
        t = String(t.prefix(60)).trimmingCharacters(in: .whitespaces)
        if t.isEmpty { t = "Prime Minutes" }
        guard let date else { return t }
        var c = Calendar(identifier: .gregorian); c.timeZone = timeZone
        let d = c.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d ", d.year ?? 0, d.month ?? 0, d.day ?? 0) + t
    }

    // MARK: PDF internal links

    /// `m:ss` / `h:mm:ss` → seconds.
    static func seconds(_ t: String) -> Int { t.split(separator: ":").reduce(0) { $0 * 60 + (Int($1) ?? 0) } }
    /// The transcript turn a cite at `sec` jumps to: the last one starting at or before it (else the first).
    static func target(_ sec: Int, _ starts: [Int]) -> Int? {
        guard !starts.isEmpty else { return nil }
        var best = 0
        for (i, s) in starts.enumerated() where s <= sec { best = i }
        return best
    }
    /// A transcript turn's PDF destination name (one per start second).
    static func turnDest(_ sec: Int) -> String { "t-\(sec)" }
    static let summaryDest = "summary"
    /// Where a turn's "↑" goes back to: the first cite that points at it (`c-<n>`), else the summary's heading.
    static func backDest(turnSec: Int, citeTargets: [(cite: Int, turnSec: Int)]) -> String {
        citeTargets.first { $0.turnSec == turnSec }.map { "c-\($0.cite)" } ?? summaryDest
    }
    /// The times inside a cite (`[0:15, 1:02]` → ["0:15", "1:02"]).
    static func citeTimes(_ inner: String) -> [String] {
        let re = try! NSRegularExpression(pattern: #"\d+:\d{2}(?::\d{2})?"#), ns = inner as NSString
        return re.matches(in: inner, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range) }
    }
}
