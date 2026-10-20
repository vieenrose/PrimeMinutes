import SwiftUI

/// A speaker of the meeting, as the picker shows it. `person`: the person it is linked to, if any.
struct PickerSpeaker: Equatable, Identifiable { var id: Int; var label: String; var person: String? }

/// What the user chose in PersonPickerSheet.
enum PersonPick {
    /// A person of the directory (possibly one already linked to another speaker of this meeting).
    case existing(Person)
    /// A new person (the "same person or someone else?" question was already asked).
    case new(String)
    /// Back to the default "Speaker n" label.
    case clear
    /// A contact from the system picker and the address the user tapped (the caller confirms the name).
    case contact(PickedContact, String)
}

private func defaultLabel(_ id: Int) -> String { L("speaker_n", id + 1) }
func peopleMeetingsText(_ n: Int) -> String { n == 0 ? L("people_no_meetings") : L(n == 1 ? "people_meetings_one" : "people_meetings_other", n) }

/// The speaker picker (docs/design/speaker-tags.md §2.1, Android PersonPickerSheet), reusable by any screen that
/// names a speaker. It lists the people of the directory — voice suggestions (Phase 2), people already in this
/// meeting (greyed, "already Speaker 3"), recent, then all — with search and "Add ‹text› as a new person". It never
/// edits anything: the choice comes back through `onPick` and the caller applies it (SpeakerNameFlow asks "merge?").
/// `current`: the speaker being named (nil: a person for a speaker that does not exist yet). `speakers`: every speaker
/// of the meeting. `sessions`: the library (meeting counts). `onPlaySample`: plays a clip of `current` (nil hides it).
struct PersonPickerSheet: View {
    let title: String
    let current: PickerSpeaker?
    let speakers: [PickerSpeaker]
    let sessions: [Session]
    var suggestions: [VoiceSuggestion] = []
    /// Under the voice section: why there is none ("Not enough speech to compare voices"), or nil.
    var voiceNote: String? = nil
    var onPlaySample: (() -> Void)? = nil
    var allowClear: Bool? = nil
    /// "Choose from contacts…" (the system picker, no permission): a new person, or an existing one, with an address.
    var allowContacts = true
    let onPick: (PersonPick) -> Void
    @ObservedObject private var store = PeopleStore.shared
    @State private var query = Dev.env["VOX_PICKQ"] ?? ""
    @State private var sameAsk: (String, Person)?
    @FocusState private var focused: Bool

    private var inMeeting: [String: Int] {
        Dictionary(speakers.filter { $0.id != current?.id && $0.person != nil }.map { ($0.person!, $0.id) }, uniquingKeysWith: { a, _ in a })
    }
    private var exclude: Set<String> { Set([current?.person].compactMap { $0 }) }
    private var clearable: Bool { allowClear ?? (current.map { $0.person != nil || $0.label != defaultLabel($0.id) } ?? false) }

    var body: some View {
        let doc = store.doc, fold = PeopleStore.fold
        let usage = store.usage(sessions)
        // A person suggested by voice is listed once, in the voice section (while not searching).
        let voiceIds = query.trimmingCharacters(in: .whitespaces).isEmpty ? Set(suggestions.map(\.person).filter { inMeeting[$0] == nil }) : []
        let sec = PeoplePicker.sections(doc, usage: usage, inMeeting: inMeeting, query: query, fold: fold, exclude: exclude.union(voiceIds))
        let homonyms = PeoplePicker.homonyms(doc, fold: fold)
        func hint(_ p: Person) -> String? {
            if let n = p.note { return n }
            if homonyms.contains(p.id), let t = usage[p.id]?.lastTitle { return L("people_last", t) }
            return nil
        }
        let suggested = suggestions.compactMap { s in doc.byId(s.person).map { ($0, s) } }.filter { !exclude.contains($0.0.id) && inMeeting[$0.0.id] == nil }
        func addNew(_ name: String) {
            let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard PersonNames.isPersonName(n) else { return }
            let same = doc.people.filter { PersonNames.same($0.name, n, fold) && $0.id != current?.person }
            if let p = same.max(by: { $0.lastUsed < $1.lastUsed }) { sameAsk = (n, p) } else { onPick(.new(n)) }
        }
        return VStack(spacing: 0) {
            HStack {
                Text(title).font(.headline).lineLimit(2)
                Spacer()
                if let onPlaySample { Button(action: onPlaySample) { Image(systemName: "play.circle.fill").font(.title2) }.accessibilityLabel(L("people_play_sample")) }
            }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 8)
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                TextField(L("people_search_hint"), text: $query).focused($focused).submitLabel(.done).autocorrectionDisabled()
                    .onSubmit {
                        if let exact = (sec.inMeeting + sec.recent + sec.all).first(where: { PersonNames.same($0.person.name, query, fold) }) { onPick(.existing(exact.person)) }
                        else { addNew(query) }
                    }
                // 取消 (Cancel) described a field-clearing button; it says 清除搜尋 now (Android: same fix).
                if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }.accessibilityLabel(L("cd_clear_search")) }
            }.padding(10).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 16)
            List {
                if query.trimmingCharacters(in: .whitespaces).isEmpty && (!suggested.isEmpty || voiceNote != nil) {
                    Section(L("people_section_voice")) {
                        ForEach(suggested, id: \.0.id) { p, s in row(p.name, hint(p) ?? peopleMeetingsText(usage[p.id]?.meetings ?? 0), dots: s.level) { onPick(.existing(p)) } }
                        if suggested.isEmpty, let voiceNote { Text(voiceNote).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
                if !sec.inMeeting.isEmpty {
                    Section(L("people_section_here")) {
                        ForEach(sec.inMeeting) { r in row(r.person.name, L("people_already_as", r.inMeetingAs.map(defaultLabel) ?? ""), greyed: true) { onPick(.existing(r.person)) } }
                    }
                }
                if !sec.recent.isEmpty {
                    Section(L("people_section_recent")) {
                        ForEach(sec.recent) { r in row(r.person.name, hint(r.person) ?? peopleMeetingsText(r.meetings)) { onPick(.existing(r.person)) } }
                    }
                }
                if !sec.all.isEmpty {
                    Section(L(query.trimmingCharacters(in: .whitespaces).isEmpty ? "people_section_all" : "people_section_matches")) {
                        ForEach(sec.all) { r in row(r.person.name, hint(r.person) ?? peopleMeetingsText(r.meetings)) { onPick(.existing(r.person)) } }
                    }
                }
                if doc.people.isEmpty && query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(L("people_picker_empty")).font(.footnote).foregroundStyle(.secondary)
                }
                if allowContacts && query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Section {
                        Button { chooseContact() } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Label(L("contact_choose"), systemImage: "person.crop.rectangle")
                                Text(L("contact_choose_hint")).font(.caption).foregroundStyle(.secondary)
                            }
                        }.accessibilityIdentifier("picker-contacts")
                    }
                }
                if sec.canAdd || (clearable && current != nil) {
                    Section {
                        if sec.canAdd {
                            Button { addNew(query) } label: { Label(L("people_add_new", query.trimmingCharacters(in: .whitespacesAndNewlines)), systemImage: "plus").fontWeight(.semibold) }
                        }
                        if clearable, let c = current {
                            Button { onPick(.clear) } label: { Text(L("people_clear_name", defaultLabel(c.id))).foregroundStyle(.secondary) }
                        }
                    }
                }
            }.listStyle(.insetGrouped)
        }
        .background(Color(.systemGroupedBackground))
        .alert(sameAsk.map { a in
            let w = usage[a.1.id]?.lastTitle ?? a.1.note ?? ""
            return w.isEmpty ? L("people_same_title_bare", a.1.name) : L("people_same_title", a.1.name, w)
        } ?? "", isPresented: Binding(get: { sameAsk != nil }, set: { if !$0 { sameAsk = nil } }), presenting: sameAsk) { a in
            Button(L("people_same_yes")) { sameAsk = nil; onPick(.existing(a.1)) }
            Button(L("people_same_no", a.0)) { sameAsk = nil; onPick(.new(a.0)) }
        }
        .task {   // DEV: VOX_CONTACT_TAP=speaker taps "Choose from contacts…" (device checks without taps)
            guard allowContacts, Dev.env["VOX_CONTACT_TAP"] == "speaker" else { return }
            try? await Task.sleep(nanoseconds: 2_000_000_000); chooseContact()
        }
    }

    private func chooseContact() { ContactPicker.present { c, chosen in onPick(.contact(c, chosen)) } }

    private func row(_ name: String, _ sub: String?, greyed: Bool = false, dots: Int = 0, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            HStack(spacing: 10) {
                if dots > 0 { ConfidenceDots(level: dots) }
                Text(name).fontWeight(.medium).foregroundStyle(greyed ? Color.secondary : Color.primary).lineLimit(1)
                Spacer(minLength: 8)
                if let sub { Text(sub).font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
            }.contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// Three dots for a voice suggestion's confidence: ●●● likely, ●●○ possible (never a percentage).
struct ConfidenceDots: View {
    let level: Int
    var body: some View {
        let l = min(max(level, 0), 3)
        Text(String(repeating: "●", count: l) + String(repeating: "○", count: 3 - l)).font(.caption).foregroundStyle(Color.accentColor)
    }
}

/// The rename flow of the session screen (Android SpeakerNameFlow): PersonPickerSheet for `speaker`, then, when the
/// person picked is already another speaker of this meeting, "Mark is already Speaker 3 here" (Merge / Both are Mark
/// / Cancel). `onMerge` merges `speaker` into that speaker; `onLink` links it.
struct SpeakerNameFlow: View {
    let speaker: PickerSpeaker
    let speakers: [PickerSpeaker]
    let sessions: [Session]
    var suggestions: [VoiceSuggestion] = []
    var voiceNote: String? = nil
    var onPlaySample: (() -> Void)? = nil
    let onLink: (Person) -> Void
    let onNew: (String) -> Void
    let onClear: () -> Void
    let onMerge: (Int) -> Void
    let onDismiss: () -> Void
    @State private var mergeAsk: (Person, PickerSpeaker)?
    /// A picked contact: its name to confirm (the person's name in the app), then "same person?" on a homonym.
    @State private var contactAsk: (PickedContact, String)?
    @State private var contactName = ""
    @State private var contactSame: (PickedContact, String, String, Person)?

    private func picked(_ pick: PersonPick) {
        switch pick {
        case .existing(let p):
            if let other = speakers.first(where: { $0.id != speaker.id && $0.person == p.id }) { mergeAsk = (p, other) } else { onLink(p) }
        case .new(let n): onNew(n)
        case .clear: onClear()
        case .contact(let c, let chosen):
            contactName = c.name ?? ""
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { contactAsk = (c, chosen) }   // after the picker is gone
        }
    }
    private func contactNamed(_ c: PickedContact, _ chosen: String, _ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard PersonNames.isPersonName(n) else { return }
        let store = PeopleStore.shared
        if let same = store.doc.people.filter({ PersonNames.same($0.name, n, PeopleStore.fold) }).max(by: { $0.lastUsed < $1.lastUsed }) {
            contactSame = (c, chosen, n, same)
        } else { picked(.existing(store.createFromContact(n, c, chosen: chosen))) }
    }
    var body: some View {
        PersonPickerSheet(title: L("people_who_is", speaker.label), current: speaker, speakers: speakers, sessions: sessions,
                          suggestions: suggestions, voiceNote: voiceNote, onPlaySample: onPlaySample, onPick: picked)
            .alert(mergeAsk.map { L("people_merge_title", $0.0.name, defaultLabel($0.1.id)) } ?? "",
                   isPresented: Binding(get: { mergeAsk != nil }, set: { if !$0 { mergeAsk = nil } }), presenting: mergeAsk) { a in
                Button(L("speaker_merge")) { mergeAsk = nil; onMerge(a.1.id) }
                Button(L("people_both", a.0.name)) { mergeAsk = nil; onLink(a.0) }
                Button(L("cancel"), role: .cancel) { mergeAsk = nil; onDismiss() }
            } message: { a in Text(L("people_merge_text", speaker.label, a.1.label)) }
            .task(id: contactAsk?.1) {   // DEV: VOX_CONTACT_SAVE=<s> presses Save on "Name in Prime Minutes" after <s> seconds
                guard let a = contactAsk, let t = Double(Dev.env["VOX_CONTACT_SAVE"] ?? "") else { return }
                try? await Task.sleep(nanoseconds: UInt64(t * 1e9)); contactAsk = nil; contactNamed(a.0, a.1, contactName)
            }
            .alert(L("contact_name_title"), isPresented: Binding(get: { contactAsk != nil }, set: { if !$0 { contactAsk = nil } }), presenting: contactAsk) { a in
                TextField(L("speaker_name"), text: $contactName)
                Button(L("people_save")) { contactAsk = nil; contactNamed(a.0, a.1, contactName) }
                Button(L("cancel"), role: .cancel) { contactAsk = nil }
            } message: { a in Text(a.1) }
            .alert(contactSame.map { L("people_same_title_bare", $0.3.name) } ?? "", isPresented: Binding(get: { contactSame != nil }, set: { if !$0 { contactSame = nil } }), presenting: contactSame) { a in
                Button(L("people_same_yes")) { contactSame = nil; PeopleStore.shared.linkContact(a.3.id, a.0, chosen: a.1); picked(.existing(PeopleStore.shared.doc.byId(a.3.id) ?? a.3)) }
                Button(L("people_same_no", a.2)) { contactSame = nil; picked(.existing(PeopleStore.shared.createFromContact(a.2, a.0, chosen: a.1))) }
                Button(L("cancel"), role: .cancel) { contactSame = nil }
            }
            .task {   // DEV: VOX_PICKAUTO=<name> picks that person after 2.5 s (device checks without taps)
                guard let n = Dev.env["VOX_PICKAUTO"] else { return }
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if let p = PeopleStore.shared.doc.people.first(where: { PersonNames.same($0.name, n, PeopleStore.fold) }) { picked(.existing(p)) } else { picked(.new(n)) }
            }
    }
}

/// The People screen (docs/design/speaker-tags.md §2.2, Android PeopleScreen): every person of the directory with
/// their meetings; a person page renames everywhere, merges, splits one meeting off, deletes, and lists the meetings
/// (a tap opens the meeting at the person's first line). Pushed from Settings.
struct PeopleScreen: View {
    @ObservedObject var library: SessionLibrary
    /// Opens a meeting at a time (closes Settings).
    let openMeeting: (Session, Double) -> Void
    @ObservedObject private var store = PeopleStore.shared
    @State private var openId: String? = nil
    @State private var devOpened = false

    var body: some View {
        let doc = store.doc
        let meetings = store.meetingsByPerson(library.sessions)
        let homonyms = PeoplePicker.homonyms(doc, fold: PeopleStore.fold)
        let sorted = doc.people.sorted { a, b in
            let ma = meetings[a.id]?.count ?? 0, mb = meetings[b.id]?.count ?? 0
            return ma != mb ? ma > mb : PersonNames.normalize(a.name) < PersonNames.normalize(b.name)
        }
        List {
            if doc.migrationFound > 0 {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(doc.migrationFound == 1 ? L("people_migration_card_one") : L("people_migration_card", doc.migrationFound)).font(.subheadline)
                        HStack { Spacer(); Button(L("people_migration_ok")) { store.dismissMigrationCard() }.bold() }
                    }
                }
            }
            VoiceSettingsCard()
            if doc.people.isEmpty { Text(L("people_none")).foregroundStyle(.secondary) }
            Section {
                ForEach(sorted) { p in
                    let ms = meetings[p.id] ?? []
                    let sub = ([p.note ?? (homonyms.contains(p.id) ? ms.first?.title : nil)].compactMap { $0 } + [peopleMeetingsText(ms.count)]).joined(separator: " · ")
                    NavigationLink(value: p.id) {
                        HStack(spacing: 12) {
                            Image(systemName: "person.fill").foregroundStyle(.secondary).accessibilityHidden(true)   // the name beside it is the label
                            VStack(alignment: .leading, spacing: 2) { Text(p.name).fontWeight(.medium); Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                        }
                    }
                }
            }
        }
        .navigationTitle(L("people_title"))
        .navigationDestination(for: String.self) { id in PersonPage(id: id, library: library, openMeeting: openMeeting) }
        .navigationDestination(isPresented: Binding(get: { openId != nil }, set: { if !$0 { openId = nil } })) {
            if let id = openId { PersonPage(id: id, library: library, openMeeting: openMeeting) }
        }
        .task {   // DEV: VOX_PEOPLE=<name> opens that person's page
            guard !devOpened, let n = Dev.env["VOX_PEOPLE"], n != "1" else { return }
            devOpened = true
            try? await Task.sleep(nanoseconds: 600_000_000)
            openId = store.doc.people.first { PersonNames.same($0.name, n, PeopleStore.fold) }?.id
        }
    }
}

/// One person: Rename everywhere, Note, Merge into…, Delete, and the meetings they are linked in.
private struct PersonPage: View {
    let id: String
    @ObservedObject var library: SessionLibrary
    let openMeeting: (Session, Double) -> Void
    @ObservedObject private var store = PeopleStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var noting = false
    @State private var merging = false
    // Both merge paths ask first (a merge is one-way). Two states, not one: the picker's confirm has
    // to live inside the sheet, and an alert on the page behind a dismissing sheet can be swallowed.
    @State private var mergeConfirm: Person?
    @State private var pickConfirm: Person?
    @State private var deleting = false
    @State private var splitting: Session?
    @State private var draft = ""

    var body: some View {
        if let p = store.doc.byId(id) { page(p) } else { Color.clear.onAppear { dismiss() } }
    }

    private func page(_ p: Person) -> some View {
        let meetings = store.meetingsByPerson(library.sessions)[p.id] ?? []
        return List {
            if let n = p.note { Text(n).foregroundStyle(.secondary) }
            Section {
                Button(L("people_rename")) { draft = p.name; renaming = true }
                Button(L("people_note_short")) { draft = p.note ?? ""; noting = true }
                if store.doc.people.count > 1 { Button(L("people_merge_into")) { merging = true } }
                Button(L("people_delete"), role: .destructive) { deleting = true }
            }
            // A merge is one-way (the person loses its note, contact link and voice samples), so the
            // contact card's "Merge" only chooses: the alert below asks for the ok (Android: same fix).
            PersonContactSections(person: p) { o in mergeConfirm = o }
            PersonVoiceRow(person: p)
            Section(L("people_meetings_of", p.name)) {
                if meetings.isEmpty { Text(L("people_no_meetings")).foregroundStyle(.secondary) }
                ForEach(meetings) { s in
                    let spk = Set((s.speakerPeople ?? [:]).filter { $0.value == p.id }.compactMap { Int($0.key) })
                    HStack {
                        Button {
                            openMeeting(s, s.lines.first { spk.contains($0.speaker) }?.start ?? 0)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.title).lineLimit(2).foregroundStyle(Color.primary)
                                Text(s.date.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted).locale(AppLanguage.current.locale))).font(.caption).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        Menu {
                            Button(L("people_split", p.name)) { draft = ""; splitting = s }
                        } label: { Image(systemName: "ellipsis.circle").foregroundStyle(.secondary) }.accessibilityLabel(L("cd_more_options"))
                    }
                }
            }
        }
        .navigationTitle(p.name).navigationBarTitleDisplayMode(.inline)
        .alert(L("people_rename"), isPresented: $renaming) {
            TextField(L("speaker_name"), text: $draft)
            Button(L("people_save")) { let n = draft.trimmingCharacters(in: .whitespacesAndNewlines); if !n.isEmpty, n != p.name { Task { await store.renameEverywhere(p.id, n) } } }
            Button(L("cancel"), role: .cancel) {}
        }
        .alert(L("people_note"), isPresented: $noting) {
            TextField(L("people_note_short"), text: $draft)
            Button(L("people_save")) { store.setNote(p.id, draft) }
            Button(L("cancel"), role: .cancel) {}
        }
        .alert(L("people_split_title"), isPresented: Binding(get: { splitting != nil }, set: { if !$0 { splitting = nil } }), presenting: splitting) { s in
            TextField(L("speaker_name"), text: $draft)
            Button(L("people_save")) { let n = draft; splitting = nil; Task { await store.split(s.id, from: p.id, newName: n) } }
            Button(L("cancel"), role: .cancel) { splitting = nil }
        }
        .alert(L("people_delete_title", p.name), isPresented: $deleting) {
            Button(L("people_delete"), role: .destructive) { Task { await store.delete(p.id, clearNames: false) } }
            if !meetings.isEmpty { Button(L("people_delete_clear", meetings.count), role: .destructive) { Task { await store.delete(p.id, clearNames: true) } } }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("people_delete_text")) }
        .alert(L("people_merge_pick", p.name), isPresented: Binding(get: { mergeConfirm != nil }, set: { if !$0 { mergeConfirm = nil } }), presenting: mergeConfirm) { o in
            Button(L("contact_merge"), role: .destructive) { mergeConfirm = nil; mergeNow(p.id, o.id) }
            Button(L("cancel"), role: .cancel) { mergeConfirm = nil }
        } message: { o in Text(L("people_merge_text", p.name, o.name)) }
        .sheet(isPresented: $merging) {
            NavigationStack {
                List(store.doc.people.filter { $0.id != p.id }.sorted { PersonNames.normalize($0.name) < PersonNames.normalize($1.name) }) { o in
                    Button(o.name + (o.note.map { " · " + $0 } ?? "")) { pickConfirm = o }
                }
                .navigationTitle(L("people_merge_pick", p.name)).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { merging = false } } }
                // Confirmed inside the sheet: the picker stays put, and the page behind never races it.
                .alert(L("people_merge_pick", p.name), isPresented: Binding(get: { pickConfirm != nil }, set: { if !$0 { pickConfirm = nil } }), presenting: pickConfirm) { o in
                    Button(L("contact_merge"), role: .destructive) { pickConfirm = nil; merging = false; mergeNow(p.id, o.id) }
                    Button(L("cancel"), role: .cancel) { pickConfirm = nil }
                } message: { o in Text(L("people_merge_text", p.name, o.name)) }
            }.presentationDetents([.medium, .large])
        }
    }

    private func mergeNow(_ from: String, _ into: String) {
        Task { await store.merge(from: from, into: into) }
    }
}

/// "Meetings with X" (Android StudioScreen people chips): Everyone, then one chip per person linked in a listed
/// meeting, the most present first.
struct PeopleFilterChips: View {
    let sessions: [Session]
    @Binding var selected: String?
    @ObservedObject private var store = PeopleStore.shared
    var body: some View {
        let by = store.meetingsByPerson(sessions)
        let people = store.doc.people.compactMap { p in by[p.id].map { (p, $0.count) } }
            .sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0.name < $1.0.name }
        if !people.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    chip(L("people_filter_all"), selected == nil) { selected = nil }
                    ForEach(people, id: \.0.id) { p, n in chip("\(p.name) · \(n)", selected == p.id) { selected = selected == p.id ? nil : p.id } }
                }.padding(.horizontal, 16)
            }.padding(.bottom, 8)
            .onChange(of: people.map(\.0.id)) { _, ids in if let s = selected, !ids.contains(s) { selected = nil } }
        }
    }
    private func chip(_ t: String, _ on: Bool, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            Text(t).font(.subheadline.weight(.semibold)).padding(.horizontal, 14).padding(.vertical, 7)
                .background(on ? Color.accentColor : Color(.secondarySystemGroupedBackground), in: Capsule())
                .foregroundStyle(on ? Color.white : Color.primary)
        }.buttonStyle(.plain)
    }
}
