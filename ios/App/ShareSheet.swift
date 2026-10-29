import SwiftUI
import MessageUI
import UniformTypeIdentifiers
import QuickLook

/// A meeting to share and whether it comes from an online source (podcast): the copyright default.
struct ShareTarget: Identifiable {
    let session: Session
    let online: Bool
    var id: UUID { session.id }
    @MainActor static func of(_ s: Session) -> ShareTarget {
        ShareTarget(session: s, online: Dev.env["VOX_SHARE_ONLINE"] != nil || LibrarySources.index(EpisodeSources.all())[s.id.uuidString] != nil)
    }
}

/// Share v2: the one "Share" entry of a meeting (session ⋯, the library row). Pick WHAT (the whole session as
/// .pmin, the summary + transcript PDF ± the recording, the recording alone), then WHERE (AirDrop, e-mail with the
/// files attached, any other app).
struct MeetingShareSheet: View {
    let target: ShareTarget
    var onDone: () -> Void = {}
    @State private var what: ShareWhat?
    @State private var attachAudio = false
    @State private var pdfURL: URL?
    @State private var pdfBytes: Int64 = 0
    @State private var docxURL: URL?
    @State private var docxBytes: Int64 = 0
    /// The document format of option 2, remembered: "pdf" | "docx".
    @AppStorage("share_doc_format") private var docFormat = "pdf"
    private var docBytes: Int64 { docFormat == "docx" ? docxBytes : pdfBytes }
    @State private var progress: Float?
    @State private var task: Task<Void, Never>?
    @State private var failed: String?
    @State private var done = false
    private var s: Session { target.session }

    private var audioURL: URL? {
        guard let a = s.audio else { return nil }
        let u = JobQueue.audioDir.appendingPathComponent(a)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }
    private var available: [ShareWhat] { ShareCore.available(hasLines: !s.lines.isEmpty && !s.noSpeech, hasAudio: audioURL != nil) }
    private var audioBytes: Int64 {
        guard let u = audioURL else { return 0 }
        let n = (try? FileManager.default.attributesOfItem(atPath: u.path)[.size] as? NSNumber)?.int64Value ?? 0
        return ShareCore.audioEstimate(sourceBytes: n, sourceExtension: u.pathExtension, seconds: s.seconds)
    }
    private var sessionBytes: Int64 { ShareCore.sessionEstimate(audio: audioBytes, textChars: s.lines.reduce(0) { $0 + $1.text.count } + s.summary.count) }
    private func bytes(_ w: ShareWhat) -> Int64 { ShareCore.total(w, attachAudio: attachAudio, session: sessionBytes, pdf: docBytes, audio: audioBytes) }
    private func size(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: max(n, 1), countStyle: .file) }
    private var base: String { ShareCore.fileBase(title: Export.refs(s.title, s), date: s.meetingStart) }

    var body: some View {
        NavigationStack {
            List {
                Section(L("share_what")) {
                    if available.contains(.session) {
                        whatRow(.session, "shippingbox", L("share_what_session"), L("share_what_session_desc"), "≈ " + size(sessionBytes))
                    }
                    if available.contains(.pdf) {
                        whatRow(.pdf, "doc.richtext", L("share_what_pdf"), L("share_what_pdf_desc"), docBytes > 0 ? size(docBytes) : "…")
                        if what == .pdf {
                            Picker(L("share_doc_format"), selection: $docFormat) { Text("PDF").tag("pdf"); Text("Word").tag("docx") }
                                .pickerStyle(.segmented).padding(.leading, 40).accessibilityIdentifier("share-doc-format")
                        }
                        if what == .pdf, audioURL != nil {
                            Toggle(isOn: $attachAudio) { Text(L("share_attach_audio", "≈ " + size(audioBytes))).font(.subheadline) }
                                .padding(.leading, 40).accessibilityIdentifier("share-attach-audio")
                        }
                    }
                    if available.contains(.audio) {
                        whatRow(.audio, "waveform", L("share_what_audio"), L("share_what_audio_desc"), "≈ " + size(audioBytes))
                    }
                    if let w = what {
                        switch ShareCore.hint(w, attachAudio: attachAudio, online: target.online) {
                        case .voices: hint("person.wave.2", L("share_hint_voices"), .secondary)
                        case .copyright: hint("exclamationmark.triangle", L("share_hint_copyright"), Vox.warning.color)
                        case .none: EmptyView()
                        }
                    }
                }
                Section(L("share_where")) {
                    whereRow(.nearby, "airplayaudio", L("share_nearby"), L("share_nearby_desc"))
                    let total = what.map(bytes) ?? 0
                    whereRow(.mail, "envelope", L("share_mail"), total > ShareCore.mailLimit ? L("share_mail_big", size(total)) : L("share_mail_desc"), warn: total > ShareCore.mailLimit)
                    whereRow(.other, "ellipsis.circle", L("share_other"), L("share_other_desc"))
                }
            }
            .navigationTitle(L("share_title")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { task?.cancel(); onDone() } } }
            .overlay { if let p = progress { SessionExporting(progress: p, title: L("share_preparing"), onCancel: { task?.cancel() }) } }
            .alert(failed ?? "", isPresented: Binding(get: { failed != nil }, set: { if !$0 { failed = nil } })) { Button(L("ok")) {} }
        }
        .presentationDetents([.large]).presentationDragIndicator(.visible)
        .task { await setUp() }
    }

    private func whatRow(_ w: ShareWhat, _ icon: String, _ title: String, _ desc: String, _ sz: String) -> some View {
        Button { withAnimation { what = w } } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: what == w ? "checkmark.circle.fill" : "circle").font(.title3)
                    .foregroundStyle(what == w ? Color.accentColor : Color.secondary).frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline) {
                        Label(title, systemImage: icon).font(.body.weight(.semibold)).foregroundStyle(.primary)
                        Spacer(minLength: 8)
                        Text(sz).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Text(desc).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }.contentShape(Rectangle())
        }.buttonStyle(.plain)
            .accessibilityAddTraits(what == w ? .isSelected : []).accessibilityIdentifier("share-what-\(w.rawValue)")
    }
    private func hint(_ icon: String, _ text: String, _ color: Color) -> some View {
        Label(text, systemImage: icon).font(.footnote).foregroundStyle(color).accessibilityIdentifier("share-hint")
    }
    private func whereRow(_ w: ShareWhere, _ icon: String, _ title: String, _ desc: String, warn: Bool = false) -> some View {
        Button { go(w) } label: {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.title3).foregroundStyle(Color.accentColor).frame(width: 28).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body).foregroundStyle(.primary)
                    Text(desc).font(.footnote).foregroundStyle(warn ? Vox.warning.color : .secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary).accessibilityHidden(true)
            }.contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(what == nil || progress != nil).accessibilityIdentifier("share-to-\(w.rawValue)")
    }

    /// Defaults (ShareCore), then the PDF rendered once so its real size shows (and it is ready to go).
    private func setUp() async {
        SessionFile.clearShareDir()
        let online = target.online
        what = Dev.env["VOX_SHARE_WHAT"].flatMap(ShareWhat.init(rawValue:)) ?? ShareCore.defaultWhat(online: online, hasLines: available.contains(.pdf), hasAudio: audioURL != nil)
        attachAudio = ShareCore.defaultAttachAudio(online: online, audioBytes: audioBytes)
        guard available.contains(.pdf) else { return }
        try? await Task.sleep(nanoseconds: 150_000_000)   // the sheet first
        if let u = Export.pdfFile(s, base: base, dir: SessionFile.shareDir) {
            pdfURL = u
            pdfBytes = (try? FileManager.default.attributesOfItem(atPath: u.path)[.size] as? NSNumber)?.int64Value ?? 0
            if Dev.env["VOX_SHARE_PDF"] != nil {   // DEV: a copy in Documents for the device checks (pulled with devicectl)
                let d = URL(fileURLWithPath: NSHomeDirectory() + "/Documents/share.pdf")
                try? FileManager.default.removeItem(at: d); try? FileManager.default.copyItem(at: u, to: d)
                StatusLog.add("share: pdf \(pdfBytes) bytes")
            }
            if Dev.env["VOX_SHARE_QL"] != nil { try? await Task.sleep(nanoseconds: 800_000_000); SharePresenter.quickLook(u) }   // DEV: the PDF in Quick Look
        }
        if let u = Export.docxFile(s, base: base, dir: SessionFile.shareDir) {
            docxURL = u
            docxBytes = (try? FileManager.default.attributesOfItem(atPath: u.path)[.size] as? NSNumber)?.int64Value ?? 0
            if Dev.env["VOX_SHARE_PDF"] != nil {
                let d = URL(fileURLWithPath: NSHomeDirectory() + "/Documents/share.docx")
                try? FileManager.default.removeItem(at: d); try? FileManager.default.copyItem(at: u, to: d)
            }
        }
        if let f = Dev.env["VOX_SHARE_FORMAT"] { docFormat = f }
        if let w = Dev.env["VOX_SHARE_TO"].flatMap(ShareWhere.init(rawValue:)) { try? await Task.sleep(nanoseconds: 1_500_000_000); go(w) }
    }

    /// The chosen files, written into the share dir (the audio encodes with progress and Cancel).
    private func files() async -> [URL]? {
        guard let w = what else { return nil }
        let report: @Sendable (Float) -> Void = { p in Task { @MainActor in if progress != nil { progress = p } } }
        switch w {
        case .session:
            progress = 0
            guard let u = await SessionFile.export(s, base: base, progress: report) else { return nil }
            return [u]
        case .audio:
            progress = 0
            guard let u = await SessionFile.exportAudio(s, base: base, progress: report) else { return nil }
            return [u]
        case .pdf:
            let doc = docFormat == "docx" ? (docxURL ?? Export.docxFile(s, base: base, dir: SessionFile.shareDir)) : (pdfURL ?? Export.pdfFile(s, base: base, dir: SessionFile.shareDir))
            guard let pdf = doc else { return nil }
            guard attachAudio, audioURL != nil else { return [pdf] }
            progress = 0
            guard let a = await SessionFile.exportAudio(s, base: base, progress: report) else { return nil }
            return [pdf, a]
        }
    }

    private func go(_ w: ShareWhere) {
        task = Task {
            let fs = await files()
            let cancelled = Task.isCancelled
            progress = nil; task = nil
            if cancelled { return }
            guard let fs else { failed = L("export_share_failed"); return }
            StatusLog.add("share: \(what?.rawValue ?? "-")/\(docFormat) → \(w.rawValue), \(fs.count) file(s)")
            SharePresenter.present(w, files: fs, subject: Export.refs(s.title, s)) { ok in
                if ok { Haptics.success(); onDone() }
            }
        }
    }
}

/// The UIKit side of the destinations: activity sheet (AirDrop only / any app), the mail composer. Presented over
/// the topmost controller (the share sheet itself).
@MainActor enum SharePresenter {
    private static var retained: NSObject?

    static func present(_ w: ShareWhere, files: [URL], subject: String, done: @escaping @MainActor (Bool) -> Void) {
        guard let top = ContactPicker.topController() else { done(false); return }
        switch w {
        case .nearby: activity(files, airDropOnly: true, top, done)
        case .other: activity(files, airDropOnly: false, top, done)
        case .mail:
            guard MFMailComposeViewController.canSendMail() else { StatusLog.add("share: mail not set up → share sheet"); activity(files, airDropOnly: false, top, done); return }
            let c = MFMailComposeViewController()
            c.setSubject(subject)
            c.setMessageBody(L("share_mail_body", subject), isHTML: false)
            for f in files { if let d = try? Data(contentsOf: f, options: .mappedIfSafe) { c.addAttachmentData(d, mimeType: mime(f), fileName: f.lastPathComponent) } }
            let d = MailDelegate { r in StatusLog.add("share: mail composer \(r.rawValue)"); done(r == .sent || r == .saved) }
            retained = d; c.mailComposeDelegate = d
            top.present(c, animated: true)
            if let t = Double(Dev.env["VOX_MAIL_CLOSE"] ?? "") {   // DEV: closes the composer unsent (device checks: never send)
                DispatchQueue.main.asyncAfter(deadline: .now() + t) { c.dismiss(animated: true); retained = nil; StatusLog.add("share: DEV composer closed unsent") }
            }
        }
    }
    /// DEV: Quick Look on the PDF (the link checks tap a citation there).
    static func quickLook(_ u: URL) {
        guard let top = ContactPicker.topController() else { return }
        let ds = QLSource(u); let q = QLPreviewController(); q.dataSource = ds; retained = ds
        top.present(q, animated: true)
    }
    final class QLSource: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(_ u: URL) { url = u }
        nonisolated func numberOfPreviewItems(in c: QLPreviewController) -> Int { 1 }
        nonisolated func previewController(_ c: QLPreviewController, previewItemAt i: Int) -> QLPreviewItem { url as NSURL }
    }

    private static func activity(_ files: [URL], airDropOnly: Bool, _ top: UIViewController, _ done: @escaping @MainActor (Bool) -> Void) {
        let v = UIActivityViewController(activityItems: files, applicationActivities: nil)
        if airDropOnly {
            // Everything the system lets an app exclude, so AirDrop is what remains (app extensions cannot be hidden).
            var x: [UIActivity.ActivityType] = [.message, .mail, .print, .copyToPasteboard, .assignToContact, .saveToCameraRoll, .addToReadingList,
                                                .postToFacebook, .postToTwitter, .postToWeibo, .postToVimeo, .postToFlickr, .postToTencentWeibo,
                                                .openInIBooks, .markupAsPDF, .sharePlay, .collaborationInviteWithLink, .collaborationCopyLink]
            if #available(iOS 17.0, *) { x.append(.addToHomeScreen) }
            v.excludedActivityTypes = x
        }
        v.completionWithItemsHandler = { _, ok, _, _ in MainActor.assumeIsolated { done(ok) } }
        v.popoverPresentationController?.sourceView = top.view
        top.present(v, animated: true)
    }
    private static func mime(_ u: URL) -> String {
        switch u.pathExtension.lowercased() { case "pdf": return "application/pdf"; case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; case "m4a", "pmin": return "audio/mp4"; default: return "application/octet-stream" }
    }

    final class MailDelegate: NSObject, MFMailComposeViewControllerDelegate {
        let done: @MainActor (MFMailComposeResult) -> Void
        init(_ done: @escaping @MainActor (MFMailComposeResult) -> Void) { self.done = done }
        nonisolated func mailComposeController(_ c: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            MainActor.assumeIsolated { c.dismiss(animated: true); done(result); SharePresenter.retained = nil }
        }
    }
}
