import SwiftUI
import MessageUI

/// What the participants sheet hands to the flow, which opens the mail composer (or the share sheet).
struct MailRequest {
    var to: [String]
    /// Who `to` are, for the share history (person id, or nil for a one-off address).
    var recipients: [ShareHistory.Recipient]
    var options: MeetingMail.Options
    var lang: String
    /// "pdf" | "txt" | "m4a", or nil.
    var attach: String?
}

/// The content defaults the sheet remembers (no personal data in them), as Android MailDefaults.
private enum MailDefaults {
    static var summary: Bool { get { UserDefaults.standard.object(forKey: "mail_summary") as? Bool ?? true } set { UserDefaults.standard.set(newValue, forKey: "mail_summary") } }
    static var minutes: Bool { get { UserDefaults.standard.object(forKey: "mail_minutes") as? Bool ?? true } set { UserDefaults.standard.set(newValue, forKey: "mail_minutes") } }
    static var only: Bool { get { UserDefaults.standard.bool(forKey: "mail_only") } set { UserDefaults.standard.set(newValue, forKey: "mail_only") } }
    static var attach: String? { get { UserDefaults.standard.string(forKey: "mail_attach") } set { UserDefaults.standard.set(newValue, forKey: "mail_attach") } }
}

/// The meeting as the mail renders it (Android EmailParticipantsFlow.input): the summary as shown, the minutes from
/// the reader's notes (headings in the user's script), the speakers' names; no model call.
@MainActor enum MeetingMailSource {
    static func meetingLang(_ s: Session) -> String? { MeetingLanguage.detect(s.lines.map(\.text)) }
    /// English for an English meeting; Chinese in the script the notes were written in (the interface's).
    static func mailLang(_ s: Session) -> String { meetingLang(s) == "en" ? "en" : AppLanguage.current.script.rawValue }
    static func title(_ s: Session) -> String {
        let t = SpeakerRefs.resolve(s.title, label: { s.name($0) }, known: Set(s.lines.map(\.speaker)))
        return t.trimmingCharacters(in: .whitespaces).isEmpty ? L("app_name") : t
    }
    static func input(_ s: Session) -> MeetingMail.Input {
        let lang = meetingLang(s)
        let card = ModelCard.default.forLanguage(lang).reader
        // The card's headings are Traditional; the notes are already in the user's script.
        let cc: OpenCC? = lang != "en" && AppLanguage.current.script == .simplified ? OpenCC.generated(.simplified) : nil
        var sections = MeetingMail.sectionsOf(s.notes.map { (tag: $0.tag, text: $0.text, ts: $0.ts) }, card.sections) { cc?.convert($0) ?? $0 }
        if sections.isEmpty, let acts = s.actionItems, let head = card.sections.first(where: { $0.1 == card.actionTag }) {
            // An older meeting with no notes journal: its action items, if any.
            sections = MeetingMail.parseMinutes("【\(head.0)】\n\(acts)", card.sections).map { var x = $0; x.heading = cc?.convert(x.heading) ?? x.heading; return x }
        }
        let speakers = Array(Set(s.lines.map(\.speaker))).sorted()
        let named = speakers.filter { !(s.speakerNames?[String($0)] ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
        var participants: [String] = []
        for k in named where !participants.contains(s.name(k)) { participants.append(s.name(k)) }
        let summary = s.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        return MeetingMail.Input(title: title(s), summary: summary.isEmpty || summary.hasPrefix("【") ? nil : s.summary, sections: sections,
                                 names: Dictionary(uniqueKeysWithValues: speakers.map { ($0, s.name($0)) }),
                                 participants: participants, unnamed: speakers.count - named.count,
                                 start: s.meetingStart ?? s.date, timeZone: .current,
                                 // A known capture runs start → start + the recording's length (the transcript can end before the audio).
                                 durationSec: max(s.lines.map(\.end).max() ?? 0, s.captureStart.map { $0 >= 0 ? s.seconds : 0 } ?? 0),
                                 timeKnown: s.meetingStart != nil)
    }
    /// The file names the mail's "Attached:" line lists (the preview; the mail itself names the real file).
    static func attachNames(_ attach: String?, _ title: String) -> [String] {
        guard let attach else { return [] }
        let safe = String(title.map { "/\\:*?\"<>|".contains($0) ? "_" : $0 }.prefix(60))
        return [(safe.isEmpty ? "Prime Minutes" : safe) + "." + attach]
    }
    static func audioBytes(_ s: Session) -> Int64 {
        guard let a = s.audio else { return 0 }
        return ((try? FileManager.default.attributesOfItem(atPath: JobQueue.audioDir.appendingPathComponent(a).path))?[.size] as? NSNumber)?.int64Value ?? 0
    }
}

private let onlyTags: Set<String> = ["DECISION", "ACTION"]

/// "Email participants" (docs/design/speaker-contacts.md §4.3, Android EmailParticipantsSheet): the meeting's people
/// pre-checked (Me not), a missing address added inline (saved to the person), the content, the mail language, a
/// preview, then the mail composer opens with everything filled in. The app never sends mail itself.
struct EmailParticipantsSheet: View {
    let session: Session
    /// Like Android: a run works on this meeting — the Name/Link rows stay visible but do not fire.
    let busy: Bool
    let onSend: (MailRequest) -> Void
    let onShareChat: (MailRequest) -> Void
    let onNameSpeaker: (Int) -> Void
    let onDismiss: () -> Void
    @ObservedObject private var store = PeopleStore.shared
    @State private var checked: [String: Bool] = [:]
    @State private var extras: [(name: String?, address: String)] = []
    @State private var summary = MailDefaults.summary
    @State private var minutes = MailDefaults.minutes
    @State private var only = MailDefaults.only
    @State private var attach: String? = MailDefaults.attach
    @State private var lang = ""
    @State private var history: [ShareHistory.Share] = []
    @State private var adding = false
    @State private var preview: MeetingMail.Mail?
    @State private var typing: String?
    @State private var typed = ""
    @State private var saved: Set<String> = []

    private var last: ShareHistory.Share? { history.last { $0.channel == "email" } }
    private var people: [Int: Person] { store.peopleOf(session) }
    private var anyMe: Bool { store.doc.people.contains { $0.me } }
    private var rows: [Recipients.Row] {
        Recipients.rows(people, anyMe: anyMe, last: last).map { r in
            var x = r; x.checked = r.address != nil && (checked[r.person.id] ?? r.checked); return x
        }
    }
    private func request() -> MailRequest {
        let rs = rows
        var rec = rs.filter { $0.checked && $0.address != nil }.map { ShareHistory.Recipient(person: $0.person.id, address: $0.address!) } + extras.map { ShareHistory.Recipient(person: nil, address: $0.address) }
        var seen = Set<String>(); rec = rec.filter { seen.insert(EmailRules.key($0.address)).inserted }
        let opts = MeetingMail.Options(summary: summary && !only, minutes: minutes || only, only: only ? onlyTags : nil)
        return MailRequest(to: Recipients.to(rs, extra: extras.map(\.address)), recipients: rec, options: opts, lang: lang, attach: attach)
    }
    private func saveDefaults() { MailDefaults.summary = summary; MailDefaults.minutes = minutes; MailDefaults.only = only; MailDefaults.attach = attach }
    private func langName(_ l: String) -> String { L(l == "zh-Hant" ? "mail_lang_hant" : l == "zh-Hans" ? "mail_lang_hans" : "mail_lang_en") }

    var body: some View {
        let rs = rows, meetingLang = MeetingMailSource.mailLang(session), bytes = MeetingMailSource.audioBytes(session)
        let speakers = Array(Set(session.lines.map(\.speaker))).sorted()
        let labels = Dictionary(uniqueKeysWithValues: speakers.compactMap { k -> (Int, String)? in
            let n = (session.speakerNames?[String(k)] ?? "").trimmingCharacters(in: .whitespaces)
            return n.isEmpty || people[k] != nil ? nil : (k, n)
        })
        NavigationStack {
            List {
                Section {
                    if rs.isEmpty && extras.isEmpty { Text(L("mail_no_people")).font(.footnote).foregroundStyle(.secondary) }
                    ForEach(rs, id: \.person.id) { r in recipientRow(r) }
                    ForEach(Array(extras.enumerated()), id: \.offset) { i, e in
                        HStack(spacing: 10) {
                            // A remove button, so it wears a ✕: the checked box here read as a selection
                            // and pressing it deleted the recipient (Android: same change).
                            Button { extras.remove(at: i) } label: { Image(systemName: "xmark.circle").foregroundStyle(.secondary).tapTarget() }.buttonStyle(.borderless).accessibilityLabel(L("email_remove"))
                            avatar(e.name ?? e.address)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(e.name ?? e.address).lineLimit(1)
                                Text(e.name != nil ? e.address + " · " + L("mail_one_off") : L("mail_one_off")).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    ForEach(Recipients.loose(speakers, names: labels, people: people), id: \.speaker) { l in
                        Button { onNameSpeaker(l.speaker) } label: {
                            HStack {
                                Text(l.label.map { L("mail_plain_label", $0) } ?? L("mail_unnamed", L("speaker_n", l.speaker + 1))).font(.footnote).foregroundStyle(.secondary)
                                Spacer()
                                Text(L(l.label == nil ? "mail_name_action" : "mail_link_action")).font(.subheadline.weight(.semibold)).foregroundStyle(Color.accentColor)
                            }
                        }.buttonStyle(.plain).disabled(busy)
                    }
                    Button { adding = true } label: { Label(L("mail_add_recipient"), systemImage: "plus") }.accessibilityIdentifier("mail-add-recipient")
                    if !anyMe && !rs.isEmpty {
                        Menu {
                            ForEach(rs, id: \.person.id) { r in Button(r.person.name) { checked[r.person.id] = false; store.setMe(r.person.id) } }
                        } label: { Text(L("mail_which_is_you")).font(.footnote) }
                    }
                } header: { Text(L("mail_recipients")) }

                Section {
                    HStack(spacing: 8) {
                        chip(L("mail_summary"), summary && !only) { summary.toggle(); only = false }
                        chip(L("mail_minutes"), minutes && !only) { minutes.toggle(); only = false }
                    }
                    chip(L("mail_only_decisions"), only) { only.toggle() }
                    VStack(alignment: .leading, spacing: 6) {
                        Label(L("mail_attach"), systemImage: "paperclip").font(.footnote).foregroundStyle(.secondary)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                chip(L("mail_attach_none"), attach == nil) { attach = nil }
                                chip("PDF", attach == "pdf") { attach = "pdf" }
                                chip("TXT", attach == "txt") { attach = "txt" }
                                if bytes > 0 { chip(".m4a · " + ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file), attach == "m4a") { attach = "m4a" } }
                            }
                        }
                        if attach == "m4a" && bytes > 20 * 1024 * 1024 { Text(L("mail_attach_large")).font(.caption).foregroundStyle(.orange) }
                    }
                    Menu {
                        Section(L("mail_language_note")) {
                            ForEach(MeetingMail.langs, id: \.self) { l in Button(langName(l)) { lang = l } }
                        }
                    } label: {
                        HStack {
                            Text(L("mail_language")).foregroundStyle(.secondary)
                            Spacer()
                            Text(langName(lang) + (lang == meetingLang ? " " + L("mail_language_meeting") : ""))
                        }
                    }
                    if let s = last {
                        let who = s.recipients.map { r in r.person.flatMap { store.doc.byId($0)?.name } ?? r.address }.joined(separator: ", ")
                        Label(L("mail_last_shared", Date(timeIntervalSince1970: TimeInterval(s.at)).formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(AppLanguage.current.locale)), who),
                              systemImage: "clock.arrow.circlepath").font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                } header: {
                    HStack {
                        Text(L("mail_content")); Spacer()
                        Button(L("mail_preview")) {
                            var o = request().options; o.attachments = MeetingMailSource.attachNames(attach, MeetingMailSource.title(session))
                            preview = MeetingMail.build(MeetingMailSource.input(session), lang: lang, o)
                        }.font(.subheadline).textCase(nil).accessibilityIdentifier("mail-preview")
                    }
                }

                Section {
                    let n = request().to.count
                    Button { saveDefaults(); onSend(request()) } label: {
                        Label(n > 0 ? L("mail_open_n", n) : L("mail_open"), systemImage: "envelope.fill").frame(maxWidth: .infinity).fontWeight(.semibold)
                    }.buttonStyle(.borderedProminent).controlSize(.large).listRowBackground(Color.clear).listRowInsets(EdgeInsets()).accessibilityIdentifier("mail-open")
                    Button { saveDefaults(); onShareChat(request()) } label: {
                        Label(L("mail_share_other"), systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                    }.buttonStyle(.bordered).controlSize(.large).listRowBackground(Color.clear).listRowInsets(EdgeInsets())
                } footer: { Text(L("mail_footnote")).frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.top, 8) }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L("mail_sheet_title")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { onDismiss() } }
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text(L("mail_sheet_title")).font(.headline)
                        Text(MeetingMailSource.title(session) + " · " + session.date.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted).locale(AppLanguage.current.locale)))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
        }
        .onAppear {
            if lang.isEmpty { lang = meetingLang }
            if attach == "m4a" && bytes == 0 { attach = nil }
            history = ShareHistory.load(session.id)
        }
        .sheet(isPresented: $adding) {
            AddRecipientView(candidates: store.doc.people.filter { p in !p.emails.isEmpty && !rs.contains { $0.person.id == p.id } }) { pick in
                adding = false
                if let pick, !extras.contains(where: { EmailRules.key($0.address) == EmailRules.key(pick.1) }) { extras.append((pick.0, pick.1)) }
            }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .sheet(isPresented: Binding(get: { preview != nil }, set: { if !$0 { preview = nil } })) {
            if let m = preview {
                NavigationStack {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 8) {
                            let to = request().to
                            if !to.isEmpty { Text(L("mail_preview_to", to.joined(separator: ", "))).font(.caption).foregroundStyle(.secondary) }
                            Text(m.subject).font(.headline)
                            Text(m.plain).font(.footnote).textSelection(.enabled).accessibilityIdentifier("mail-preview-body")
                        }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .navigationTitle(L("mail_preview")).navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("mail_preview_close")) { preview = nil } } }
                }
            }
        }
        .task {   // DEV: VOX_MAIL=preview opens the preview (README / guide screenshots); send presses "Open in mail app"
            if Dev.env["VOX_MAIL"] == "send" { try? await Task.sleep(nanoseconds: 1_500_000_000); onSend(request()) }
            if Dev.env["VOX_MAIL"] == "preview" {
                try? await Task.sleep(nanoseconds: 800_000_000)
                var o = request().options; o.attachments = MeetingMailSource.attachNames(attach, MeetingMailSource.title(session))
                preview = MeetingMail.build(MeetingMailSource.input(session), lang: lang, o)
            }
        }
    }

    @ViewBuilder private func recipientRow(_ r: Recipients.Row) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Button { checked[r.person.id] = !r.checked } label: {
                    Image(systemName: r.checked ? "checkmark.square.fill" : "square").foregroundStyle(r.address == nil ? Color.secondary : Color.accentColor)
                        .tapTarget()   // the bare glyph was a ~22 pt target beside the avatar
                }.buttonStyle(.borderless).disabled(r.address == nil)
                // The state is carried by the glyph alone, and "no address" by the box going grey:
                // VoiceOver has to say who, whether, and that this one cannot be picked at all.
                .accessibilityLabel(r.person.name)
                .accessibilityValue(r.address == nil ? L("a11y_unavailable") : (r.checked ? L("a11y_checked") : L("a11y_unchecked")))
                avatar(r.person.name)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(r.person.name).lineLimit(1)
                        if r.person.me && anyMe {
                            Text(L("mail_me")).font(.caption2.weight(.semibold)).padding(.horizontal, 6).padding(.vertical, 2).background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 6))
                        }
                    }
                    if let a = r.address {
                        Text(([a] + (r.person.me && anyMe ? [L("mail_not_to_self")] : []) + (saved.contains(r.person.id) ? [L("mail_saved_to", r.person.name)] : [])).joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    } else { Text(L("mail_no_address")).font(.caption).foregroundStyle(.orange) }
                }
                Spacer()
                if r.address == nil && typing != r.person.id {
                    Button { typed = ""; typing = r.person.id } label: { Label(L("mail_add_address"), systemImage: "plus") }.buttonStyle(.bordered).controlSize(.small)
                }
            }
            if r.address == nil && typing == r.person.id {
                let ok = EmailRules.isValid(typed)
                HStack {
                    TextField("name@example.com", text: $typed).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .textContentType(.emailAddress)   // QuickType offers the user's own addresses
                        .textFieldStyle(.roundedBorder).submitLabel(.done).onSubmit { if ok { addAddress(r.person.id, EmailRules.normalize(typed)) } }
                        .accessibilityIdentifier("mail-inline-address")
                    // The action adds this address (it is not a save): VoiceOver must say so.
                    Button { addAddress(r.person.id, EmailRules.normalize(typed)) } label: { Image(systemName: "paperplane.fill").tapTarget() }.disabled(!ok).buttonStyle(.borderless).accessibilityLabel(L("mail_add_address"))
                    Button { ContactPicker.present { _, chosen in addAddress(r.person.id, chosen) } } label: { Image(systemName: "person.crop.rectangle").tapTarget() }
                        .buttonStyle(.borderless).accessibilityLabel(L("contact_choose"))
                }.padding(.leading, 34)
                Text(L("mail_will_save", r.person.name)).font(.caption).foregroundStyle(.secondary).padding(.leading, 34)
            }
        }
    }
    private func addAddress(_ id: String, _ a: String) {
        // People == Contacts: the address is saved in the person's contact (the person's copy is its cache).
        if let c = store.doc.byId(id)?.contact, c.platform == "ios" { OSContacts.addEmails(c.key, [a]); store.addEmail(id, a, from: PersonEmail.fromContact) }
        else { store.addEmail(id, a) }
        checked[id] = true; saved.insert(id); typing = nil
    }
    private func avatar(_ name: String) -> some View {
        Text(String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased()).font(.subheadline.bold()).foregroundStyle(Color.accentColor)
            .frame(width: 34, height: 34).background(Color.accentColor.opacity(0.15), in: Circle())
    }
    private func chip(_ t: String, _ on: Bool, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            Text((on ? "✓ " : "") + t).font(.subheadline.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                .background(on ? Color.accentColor.opacity(0.15) : Color.clear, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(on ? Color.accentColor.opacity(0.4) : Color(.separator)))
                .foregroundStyle(on ? Color.accentColor : Color.secondary)
        }.buttonStyle(.borderless)
    }
}

/// "Add another recipient": a person of the directory, a picked contact, or a typed address (this mail only, not saved).
private struct AddRecipientView: View {
    let candidates: [Person]
    let onDone: ((String?, String)?) -> Void
    @State private var q = ""
    var body: some View {
        let t = q.trimmingCharacters(in: .whitespaces)
        let hits = candidates.filter { p in t.isEmpty || p.name.localizedCaseInsensitiveContains(t) || p.emails.contains { $0.address.localizedCaseInsensitiveContains(t) } }
            .sorted { $0.lastUsed > $1.lastUsed }.prefix(8)
        NavigationStack {
            List {
                TextField(L("mail_add_hint"), text: $q).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.emailAddress).accessibilityIdentifier("mail-add-field")
                if EmailRules.isValid(q) { Button(L("mail_add_typed", EmailRules.normalize(q))) { onDone((nil, EmailRules.normalize(q))) }.fontWeight(.semibold) }
                ForEach(Array(hits)) { p in
                    Button { onDone((p.name, p.emails[0].address)) } label: {
                        VStack(alignment: .leading, spacing: 2) { Text(p.name).foregroundStyle(Color.primary); Text(p.emails[0].address).font(.caption).foregroundStyle(.secondary) }
                    }
                }
                Button { ContactPicker.present { c, chosen in onDone((c.name, chosen)) } } label: { Label(L("contact_choose"), systemImage: "person.crop.rectangle") }
                Text(L("mail_one_off_note")).font(.footnote).foregroundStyle(.secondary)
            }
            .navigationTitle(L("mail_add_recipient")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { onDone(nil) } } }
        }
    }
}

/// Opens the mail (Android EmailParticipantsFlow): the system composer when Mail is set up (HTML body, attachment, the
/// outcome known), else a mailto: link to the default mail app (plain body within the link budget, no attachment),
/// else the share sheet with the addresses copied. "sent" / "saved" / "opened" go to the meeting's share history.
@MainActor enum MailOpener {
    final class Delegate: NSObject, MFMailComposeViewControllerDelegate {
        let done: @MainActor (MFMailComposeResult) -> Void
        init(_ done: @escaping @MainActor (MFMailComposeResult) -> Void) { self.done = done }
        nonisolated func mailComposeController(_ c: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            MainActor.assumeIsolated { c.dismiss(animated: true); done(result); MailOpener.active = nil }
        }
    }
    static var active: Delegate?

    /// The attachment file (nil: none, or it could not be written).
    static func attachment(_ s: Session, _ kind: String?) async -> URL? {
        switch kind {
        case "pdf": return Export.file(s, .pdf)
        case "txt": return Export.file(s, .txt)
        case "m4a": return await SessionFile.export(s)
        default: return nil
        }
    }

    static func record(_ s: Session, _ channel: String, _ status: String, _ r: MailRequest) {
        ShareHistory.record(s.id, ShareHistory.Share(at: Int64(Date().timeIntervalSince1970), channel: channel, status: status,
                                                     recipients: channel == "email" ? r.recipients : [],
                                                     content: .init(summary: r.options.summary, minutes: r.options.minutes, only: r.options.only.map { $0.sorted() }, attach: [r.attach].compactMap { $0 }),
                                                     lang: r.lang))
    }

    /// When nothing takes the mail, the share sheet opens and the addresses go to the clipboard (`toast` says so).
    static func send(_ s: Session, _ r: MailRequest, toast: @escaping (String) -> Void) async {
        let input = MeetingMailSource.input(s)
        let file = await attachment(s, r.attach)
        if r.attach != nil && file == nil { toast(L("export_share_failed")) }
        if MFMailComposeViewController.canSendMail(), let top = ContactPicker.topController() {
            var o = r.options; o.attachments = [file?.lastPathComponent].compactMap { $0 }; o.budget = MeetingMail.composerBudget
            let m = MeetingMail.build(input, lang: r.lang, o)
            let c = MFMailComposeViewController()
            c.setToRecipients(r.to); c.setSubject(m.subject); c.setMessageBody(m.html, isHTML: true)
            if let file, let d = try? Data(contentsOf: file) { c.addAttachmentData(d, mimeType: mime(file), fileName: file.lastPathComponent) }
            let d = Delegate { res in
                StatusLog.add("mail: composer \(res.rawValue) (\(r.to.count) recipient(s))")
                switch res { case .sent: record(s, "email", "sent", r); case .saved: record(s, "email", "saved", r); default: break }
            }
            active = d; c.mailComposeDelegate = d
            StatusLog.add("mail: composer, \(r.to.count) recipient(s), \(m.plain.count) chars, attach=\(r.attach ?? "-"), truncated=\(m.truncated)")
            top.present(c, animated: true)
            if let t = Double(Dev.env["VOX_MAIL_CLOSE"] ?? "") {   // DEV: closes the composer unsent (device checks: never send)
                DispatchQueue.main.asyncAfter(deadline: .now() + t) { c.dismiss(animated: true); active = nil; StatusLog.add("mail: DEV composer closed unsent") }
            }
            return
        }
        // No Mail account: the default mail app through mailto: (plain body within the link budget, no attachment).
        var o = r.options; o.budget = r.lang == "en" ? MeetingMail.mailtoBudgetEn : MeetingMail.mailtoBudgetZh
        let m = MeetingMail.build(input, lang: r.lang, o)
        var c = URLComponents(); c.scheme = "mailto"; c.path = r.to.joined(separator: ",")
        c.queryItems = [URLQueryItem(name: "subject", value: m.subject), URLQueryItem(name: "body", value: m.plain)]
        // + and & in the values: percent-encode what URLComponents leaves.
        c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        let ok: Bool = await withCheckedContinuation { k in
            guard let u = c.url else { k.resume(returning: false); return }
            UIApplication.shared.open(u, options: [:]) { k.resume(returning: $0) }
        }
        StatusLog.add("mail: mailto ok=\(ok), \(r.to.count) recipient(s), \(m.plain.count) chars, truncated=\(m.truncated)")
        if ok { record(s, "email", "opened", r); return }
        // Nothing takes mailto: the share sheet, the addresses on the clipboard.
        if !r.to.isEmpty { UIPasteboard.general.string = r.to.joined(separator: ", "); toast(L("mail_addresses_copied")); Haptics.success() }
        share(m.plain, file: file)
        record(s, "share-sheet", "opened", r)
    }

    static func shareChat(_ s: Session, _ r: MailRequest) async {
        let m = MeetingMail.build(MeetingMailSource.input(s), lang: r.lang, r.options)
        share(m.chat, file: await attachment(s, r.attach))
        record(s, "share-sheet", "opened", r)
    }

    private static func share(_ text: String, file: URL?) {
        guard let top = ContactPicker.topController() else { return }
        let v = UIActivityViewController(activityItems: [text] + [file].compactMap { $0 }, applicationActivities: nil)
        v.popoverPresentationController?.sourceView = top.view
        top.present(v, animated: true)
    }
    private static func mime(_ u: URL) -> String {
        switch u.pathExtension.lowercased() { case "pdf": return "application/pdf"; case "txt": return "text/plain"; case "m4a": return "audio/mp4"; default: return "application/octet-stream" }
    }
}
