import SwiftUI
import Combine
import UniformTypeIdentifiers

// Library collections + scope, the UI half (rules: LibraryCollections.swift): the store (collections.json), the
// collection row, the scope pill and filter sheet, the add-to-collection sheet, the suggestion card and the user
// collection page. The channel page (LibrarySourcesView.swift) is the channel kind's page.

extension Notification.Name {
    /// Searches the whole library (object: the query): "Search in All" from a page.
    static let voxLibrarySearch = Notification.Name("voxLibrarySearch")
}

/// The user collections and the channels' seen episodes (Application Support/collections.json, written atomically
/// at every change). Local only, like the rest of Application Support (excluded from backup).
@MainActor final class CollectionStore: ObservableObject {
    static let shared = CollectionStore()
    nonisolated static var url: URL { JobQueue.support.appendingPathComponent("collections.json") }
    @Published private(set) var doc: CollectionsDoc
    /// The collection the next new job goes into (record / import from a collection page), until a deadline.
    private var target: (id: String, until: Date)?
    private var known: Set<UUID>?
    private var sub: AnyCancellable?
    private init() { doc = Collections.read(Self.url) }
    private func set(_ d: CollectionsDoc) { guard d != doc else { return }; doc = d; Collections.write(d, to: Self.url) }

    func collection(_ id: String) -> UserCollection? { doc.collections.first { $0.id == id } }
    func collectionOf(_ id: UUID) -> UserCollection? { Collections.collection(of: id.uuidString, in: doc) }
    @discardableResult func create(_ name: String, ids: [UUID]) -> String {
        let id = UUID().uuidString
        set(Collections.create(doc, id: id, name: name, ids: ids.map(\.uuidString)))
        StatusLog.add("collection created (\(ids.count) items)")
        return id
    }
    func assign(_ ids: [UUID], to cid: String) { set(Collections.assign(doc, ids: ids.map(\.uuidString), to: cid)) }
    func remove(_ ids: [UUID]) { set(Collections.remove(doc, ids: ids.map(\.uuidString))) }
    func rename(_ cid: String, _ name: String) { set(Collections.rename(doc, id: cid, name: name)) }
    func delete(_ cid: String) { set(Collections.delete(doc, id: cid)) }
    func skip(_ key: String) { var d = doc; if !d.skipped.contains(key) { d.skipped.append(key) }; set(d) }
    func markSeen(channel: String, ids: [UUID]) { set(Collections.markSeen(doc, channel: channel, ids: ids.map(\.uuidString))) }
    func seed(channels: [String: [String]]) { if !doc.seeded { set(Collections.seed(doc, channels: channels)) } }

    /// Record / import from a collection page: the next new job joins `cid` (10 min to pick a file or start).
    func arm(_ cid: String) { target = (cid, Date().addingTimeInterval(600)) }
    /// Watches the queue once: a job that appears while armed joins the armed collection.
    func attach(_ runner: JobRunner) {
        guard sub == nil else { return }
        sub = runner.$waiting.sink { [weak self] ws in
            let ids = Set(ws.map(\.id))
            Task { @MainActor in
                guard let self else { return }
                defer { self.known = (self.known ?? []).union(ids) }
                guard let known = self.known, let t = self.target else { return }
                let fresh = ids.subtracting(known)
                guard !fresh.isEmpty else { return }
                if t.until > Date() { self.assign(Array(fresh), to: t.id); StatusLog.add("collection: \(fresh.count) new item(s) filed") }
                self.target = nil
            }
        }
    }
}

/// A collection's leading tile: the channel artwork, or a stack on the accent tint (user collections).
struct CollectionIcon: View {
    let row: CollectionRow
    var size: CGFloat = 44
    var body: some View {
        if row.kind == .channel { ChannelArtwork(seriesId: row.key == LibrarySources.otherKey ? nil : row.key, url: row.artworkUrl, size: size) }
        else {
            Image(systemName: "square.stack.3d.up.fill").font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(Vox.accent)
                .frame(width: size, height: size).background(Vox.accentTint, in: RoundedRectangle(cornerRadius: size * 0.2, style: .continuous))
                .accessibilityHidden(true)
        }
    }
}

/// "12 episodes" / "5 meetings".
func collectionCount(_ r: CollectionRow) -> String { L(r.kind == .channel ? "coll_episodes_n" : "coll_meetings_n", r.sessionIds.count) }

/// A collection row of the library: icon, name, count, "N new", the latest item (or the processing state + bar).
struct CollectionRowView: View {
    let row: CollectionRow
    /// The row's items in the queue: (count, progress of the one running, if any).
    let processing: (count: Int, progress: Double?)
    let open: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize
    private var status: String {
        if let p = processing.progress { return L(row.kind == .channel ? "coll_processing_ep" : "coll_processing_mt", processing.count, Int(p * 100)) }
        if processing.count > 0 { return L("coll_queued", processing.count) }
        if row.latestTitle.isEmpty { return L("coll_empty") }
        return L("coll_latest", ScreenScript.title(row.latestTitle)) + " · " + row.latest.formatted(.dateTime.month(.defaultDigits).day())
    }
    var body: some View {
        Button(action: open) {
            (typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 12))) {
                CollectionIcon(row: row, size: 44)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(ScreenScript.gen(row.name)).font(Vox.fontRowTitle).foregroundStyle(Vox.ink).lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
                        Text(collectionCount(row)).font(Vox.fontMeta).foregroundStyle(Vox.ink2).fixedSize()
                        if row.newCount > 0 {
                            Text(L("coll_new_n", row.newCount)).font(.caption2.weight(.bold)).foregroundStyle(.white)
                                .padding(.horizontal, 6).padding(.vertical, 2).background(Vox.accent, in: Capsule()).fixedSize()
                                .accessibilityIdentifier("collection-new")
                        }
                    }
                    Text(status).font(Vox.fontMeta).foregroundStyle(Vox.ink2).lineLimit(2)
                    if let p = processing.progress { ProgressView(value: p).tint(Vox.accent).scaleEffect(x: 1, y: 0.6) }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Vox.ink3).accessibilityHidden(true)
            }.padding(.vertical, 6).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel([L(row.kind == .channel ? "coll_channel" : "coll_collection"), row.name, collectionCount(row), row.newCount > 0 ? L("coll_new_n", row.newCount) : "", status].filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("collection-row")
    }
}

/// The one scope pill under the search: "Scope: Podcasts · Ann · 21 items ✕". Tap: the filter panel; ✕: back to All.
struct ScopePill: View {
    let text: String
    let onTap: () -> Void
    let onClear: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Button(action: onTap) {
                Text(L("scope_label", text)).font(.subheadline.weight(.semibold)).lineLimit(2).multilineTextAlignment(.leading)
            }.buttonStyle(.plain).accessibilityHint(L("scope_filters")).accessibilityIdentifier("scope-pill")
            Button(action: onClear) {
                Image(systemName: "xmark").font(.caption2.weight(.bold)).foregroundStyle(Vox.raised)
                    .frame(width: 20, height: 20).background(Vox.accent, in: Circle()).tapTarget()
            }.buttonStyle(.plain).accessibilityLabel(L("scope_clear_cd")).accessibilityIdentifier("scope-clear")
        }
        .foregroundStyle(Vox.accent)
        .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 2)
        .background(Vox.accentTint, in: Capsule())
        .padding(.horizontal, Vox.gutter).padding(.vertical, 4)
    }
}

/// The scope's text: its parts joined by " · ", then the count.
@MainActor func scopeText(source: SourceFilter?, collection: String?, person: String?, date: DateScope, count: Int) -> String {
    var parts: [String] = []
    if let c = collection { parts.append(ScreenScript.gen(c)) }
    if let s = source, s != .all { parts.append(L("source_" + s.rawValue)) }
    if let p = person, let n = PeopleStore.shared.doc.people.first(where: { $0.id == p })?.name { parts.append(n) }
    if date != .any { parts.append(L("date_" + date.rawValue)) }
    parts.append(L("scope_items_n", count))
    return parts.joined(separator: " · ")
}

/// The filter panel (the pill's tap, or the filter button): source (library only), person, date.
struct ScopeFilterSheet: View {
    var source: Binding<SourceFilter>? = nil
    var sources: [SourceFilter] = SourceFilter.allCases
    @Binding var person: String?
    @Binding var date: DateScope
    let sessions: [Session]
    @ObservedObject private var people = PeopleStore.shared
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                if let source {
                    Picker(L("scope_source"), selection: source) { ForEach(sources) { Text(L("source_" + $0.rawValue)).tag($0) } }
                }
                let by = people.meetingsByPerson(sessions)
                let ps = people.doc.people.filter { by[$0.id] != nil }.sorted { $0.name < $1.name }
                if !ps.isEmpty {
                    Picker(L("person_filter"), selection: $person) {
                        Text(L("person_any")).tag(String?.none)
                        ForEach(ps, id: \.id) { p in Text(p.name).tag(Optional(p.id)) }
                    }
                }
                Picker(L("date_filter"), selection: $date) { ForEach(DateScope.allCases) { Text(L("date_" + $0.rawValue)).tag($0) } }
            }
            .navigationTitle(L("scope_filters")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// "Add to collection…" / "Move to collection…": the user collections, New collection…, and Remove when filed.
struct AddToCollectionSheet: View {
    let ids: [UUID]
    var onDone: (String?) -> Void = { _ in }
    @ObservedObject private var store = CollectionStore.shared
    @State private var naming = false
    @State private var name = ""
    @Environment(\.dismiss) private var dismiss
    private var filedIn: Set<String> { Set(ids.compactMap { store.collectionOf($0)?.id }) }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { name = ""; naming = true } label: { Label(L("coll_new"), systemImage: "plus") }.accessibilityIdentifier("coll-new")
                }
                if !store.doc.collections.isEmpty {
                    Section {
                        ForEach(store.doc.collections) { c in
                            Button { store.assign(ids, to: c.id); Haptics.success(); onDone(c.id); dismiss() } label: {
                                HStack {
                                    Image(systemName: "square.stack.3d.up").foregroundStyle(Vox.accent).accessibilityHidden(true)
                                    Text(ScreenScript.gen(c.name)).foregroundStyle(Vox.ink)
                                    Spacer()
                                    Text(L("coll_meetings_n", c.members.count)).font(Vox.fontMeta).foregroundStyle(Vox.ink2)
                                    if filedIn == [c.id] { Image(systemName: "checkmark").foregroundStyle(Vox.accent) }
                                }
                            }.accessibilityIdentifier("coll-pick")
                        }
                    }
                }
                if !filedIn.isEmpty {
                    Section { Button(L("coll_remove_from"), role: .destructive) { store.remove(ids); onDone(nil); dismiss() } }
                }
            }
            .navigationTitle(L(filedIn.isEmpty ? "coll_add_to" : "coll_move_to")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } } }
            .alert(L("coll_new"), isPresented: $naming) {
                TextField(L("coll_name"), text: $name).accessibilityIdentifier("coll-name")
                Button(L("coll_create")) {
                    let n = name.trimmingCharacters(in: .whitespacesAndNewlines); guard !n.isEmpty else { return }
                    let id = store.create(n, ids: ids); Haptics.success(); onDone(id); dismiss()
                }
                Button(L("cancel"), role: .cancel) {}
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// The suggestion card (once per group): "5 meetings titled “Weekly”. Make a collection?" [Create] [Skip].
struct CollectionSuggestionCard: View {
    let s: CollectionSuggestion
    let create: () -> Void
    let skip: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "square.stack.3d.up.fill").foregroundStyle(Vox.accent).accessibilityHidden(true)
                Text(L("coll_suggest", s.ids.count, ScreenScript.gen(s.name))).font(.subheadline).foregroundStyle(Vox.ink)
            }
            HStack {
                Spacer()
                Button(L("coll_skip"), action: skip).buttonStyle(.bordered).tint(Vox.ink2).accessibilityIdentifier("suggest-skip")
                Button(L("coll_create"), action: create).buttonStyle(.borderedProminent).accessibilityIdentifier("suggest-create")
            }.controlSize(.small)
        }
        .padding(Vox.inset)
        .background(Vox.raised, in: RoundedRectangle(cornerRadius: Vox.radiusGroup, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Vox.radiusGroup, style: .continuous).stroke(Vox.hairline))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("collection-suggestion")
    }
}

/// The Ask scope switch (≤ 3 levels plus this meeting): capsules with the count.
struct AskScopeBar: View {
    let scopes: [AskScope]
    @Binding var index: Int
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Array(scopes.enumerated()), id: \.offset) { i, s in
                    let on = i == index
                    Button { index = i } label: {
                        Text(s.count.map { "\(s.label) · \($0)" } ?? s.label).font(.caption.weight(on ? .semibold : .regular))
                            .padding(.horizontal, 11).padding(.vertical, 5)
                            .foregroundStyle(on ? Vox.accent : Vox.ink2)
                            .background(on ? Vox.accentTint : Color.clear, in: Capsule())
                            .overlay(Capsule().strokeBorder(on ? Color.clear : Vox.hairline, lineWidth: 1))
                    }.buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : []).accessibilityIdentifier("ask-scope-\(i)")
                }
            }
        }
    }
}

/// A user collection: its header, Ask this collection, search in it, record / import into it, its meetings
/// (person / date filters folded into the scope pill).
struct UserCollectionPage: View {
    let id: String
    let onOpen: (UUID) -> Void
    private var m: Model { Model.shared }
    @ObservedObject private var store = CollectionStore.shared
    @ObservedObject private var library: SessionLibrary
    @ObservedObject private var runner: JobRunner
    @State private var query = ""
    @State private var person: String?
    @State private var date: DateScope = .any
    @State private var filters = false
    @State private var asking: String?
    @State private var renaming = false
    @State private var deleting = false
    @State private var draft = ""
    @State private var moving: UUID?
    @State private var picking = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    init(id: String, onOpen: @escaping (UUID) -> Void) {
        self.id = id; self.onOpen = onOpen
        _library = ObservedObject(wrappedValue: Model.shared.library); _runner = ObservedObject(wrappedValue: Model.shared.runner)
    }
    private var coll: UserCollection? { store.collection(id) }
    private var members: [Session] { let ids = Set(coll?.members ?? []); return library.sessions.filter { ids.contains($0.id.uuidString) } }
    private var scoped: [Session] {
        members.filter { s in (person.map { p in s.speakerPeople?.values.contains(p) == true } ?? true) && date.contains(s.date) }
    }
    private var shown: [Session] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return scoped }
        let order = Dictionary(LibrarySearch.lexical(scoped, query: q).enumerated().map { ($0.element.session.id, $0.offset) }, uniquingKeysWith: { a, _ in a })
        return scoped.filter { order[$0.id] != nil }.sorted { order[$0.id]! < order[$1.id]! }
    }
    private var elsewhere: Int {
        let q = query.trimmingCharacters(in: .whitespaces); guard !q.isEmpty else { return 0 }
        return max(0, LibrarySearch.lexical(library.sessions, query: q).count - shown.count)
    }
    private var queued: [Job] { let ids = Set(coll?.members ?? []); return runner.waiting.filter { ids.contains($0.id.uuidString) } }
    private var minutes: Int { Int(members.reduce(0) { $0 + $1.seconds } / 60) }
    private var askScopes: [AskScope] {
        let all = library.sessions, ix = LibrarySources.index(EpisodeSources.all())
        let meetings = all.filter { LibrarySources.kind(received: $0.isReceived, source: ix[$0.id.uuidString]) == .meeting }
        return [AskScope(id: "collection", label: L("ask_scope_collection"), ids: Set(scoped.map(\.id)), count: scoped.count),
                AskScope(id: "meetings", label: L("source_meetings"), ids: Set(meetings.map(\.id)), count: meetings.count),
                AskScope(id: "all", label: L("source_all"), ids: nil, count: all.count)].enumerated()
            .filter { i, l in i != 1 || ((l.count ?? 0) > (scoped.count) && (l.count ?? 0) < all.count) }.map(\.element)
    }
    var body: some View {
        List {
            Section {
                HStack(alignment: .top, spacing: 14) {
                    if let c = coll { CollectionIcon(row: CollectionRow(kind: .user, key: c.id, name: c.name, sessionIds: [], latest: Date(), latestTitle: ""), size: 72) }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(ScreenScript.gen(coll?.name ?? "")).font(.title3.bold())
                        Text(L("coll_meetings_n", members.count) + (minutes > 0 ? " · " + L("coll_minutes_n", minutes) : "")).font(.caption).foregroundStyle(.secondary)
                    }
                }.accessibilityElement(children: .combine)
                Button { asking = "" } label: { Label(L("ask_this_collection"), systemImage: "sparkles").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(scoped.isEmpty || !AskEngine.available).accessibilityIdentifier("ask-collection")
                HStack {
                    Button { store.arm(id); Haptics.toggle(true); m.downloads.withModels { m.capture.toggleRecord() } } label: { Label(L("coll_record_here"), systemImage: "mic.fill").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).accessibilityIdentifier("coll-record")
                    Button { store.arm(id); picking = true } label: { Label(L("coll_import_here"), systemImage: "square.and.arrow.down").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered).accessibilityIdentifier("coll-import")
                }
            }
            if person != nil || date != .any {
                ScopePill(text: scopeText(source: nil, collection: coll?.name, person: person, date: date, count: scoped.count), onTap: { filters = true }, onClear: { person = nil; date = .any })
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
            }
            if !queued.isEmpty {
                Section { ForEach(queued) { j in Button { onOpen(j.id) } label: { VStack(alignment: .leading) { Text(j.title ?? L("meeting")); Text(m.jobStatus(j)).font(.caption).foregroundStyle(.secondary) } }.buttonStyle(.plain) } }
            }
            Section {
                if shown.isEmpty {
                    VStack(spacing: 10) {
                        Vox.hint(query.isEmpty ? L("coll_empty") : L("search_none_in_scope"))
                        if elsewhere > 0 { Button(L("search_in_all")) { searchAll() }.buttonStyle(.borderedProminent).accessibilityIdentifier("search-in-all") }
                    }.frame(maxWidth: .infinity).padding(.vertical, 12)
                }
                ForEach(shown) { s in
                    Button { onOpen(s.id) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Vox.head(s.title.isEmpty ? L("recent_untitled") : ScreenScript.title(s.title)).lineLimit(typeSize.isAccessibilitySize ? 4 : 2)
                            Vox.meta("\(s.date.formatted(date: .abbreviated, time: .shortened)) · \(Export.mmss(s.seconds))")
                        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    .contextMenu {
                        Button(L("coll_move_to"), systemImage: "square.stack.3d.up") { moving = s.id }
                        Button(L("coll_remove_from"), systemImage: "minus.circle", role: .destructive) { store.remove([s.id]) }
                    }
                    .swipeActions { Button(L("coll_remove_from"), role: .destructive) { store.remove([s.id]) } }
                    .accessibilityIdentifier("collection-item")
                }
            } footer: {
                if !shown.isEmpty, elsewhere > 0 { Button(L("search_in_all_n", elsewhere)) { searchAll() }.font(.footnote).accessibilityIdentifier("search-in-all") }
            }
        }
        .listStyle(.insetGrouped)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text(L("search_in_collection")))
        .navigationTitle(ScreenScript.gen(coll?.name ?? "")).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button(L("scope_filters"), systemImage: "line.3.horizontal.decrease.circle") { filters = true }
                    Button(L("coll_rename"), systemImage: "pencil") { draft = coll?.name ?? ""; renaming = true }
                    Button(L("coll_delete"), systemImage: "trash", role: .destructive) { deleting = true }
                } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(L("cd_more_options")) }
            }
        }
        .sheet(isPresented: $filters) { ScopeFilterSheet(person: $person, date: $date, sessions: members) }
        .sheet(item: Binding(get: { moving.map { IdBox(id: $0) } }, set: { moving = $0?.id })) { b in AddToCollectionSheet(ids: [b.id]) }
        .sheet(isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } })) {
            AskSheet(m: m, initial: asking ?? "", open: { s, t in asking = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { m.openAt = (s.id, t); onOpen(s.id) } }, scopes: askScopes)
        }
        // Import into this collection (the same types as the library's importer).
        .fileImporter(isPresented: $picking, allowedContentTypes: [.audio, .mpeg4Audio, UTType(exportedAs: "tw.com.pesi.primeminutes.session")]) { if case .success(let u) = $0 { m.imports.open(u) { a in m.downloads.withModels(a) } } }
        .alert(L("coll_rename"), isPresented: $renaming) {
            TextField(L("coll_name"), text: $draft)
            Button(L("done")) { store.rename(id, draft) }
            Button(L("cancel"), role: .cancel) {}
        }
        .alert(L("coll_delete"), isPresented: $deleting) {
            Button(L("delete"), role: .destructive) { store.delete(id); dismiss() }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("coll_delete_note")) }
        .onAppear { if let a = Dev.env["VOX_COLL_ASK"] { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { asking = a } } }
    }
    private func searchAll() { let q = query; dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { NotificationCenter.default.post(name: .voxLibrarySearch, object: q) } }
}

/// One level of the Ask scope switch: its label, the meetings it covers (nil: the whole library) and their count.
struct AskScope: Identifiable {
    let id: String
    let label: String
    let ids: Set<UUID>?
    let count: Int?
    /// "meeting": the in-meeting Ask over its own meeting (the AskSheet's `meeting`).
    var isMeeting: Bool { id == "meeting" }
    func filter(_ sessions: [Session]) -> [Session] { ids.map { ids in sessions.filter { ids.contains($0.id) } } ?? sessions }

    /// The in-meeting levels: this meeting, then its channel / collection (else its source type), then All.
    @MainActor static func levels(for mt: Session, library all: [Session]) -> [AskScope] {
        let ix = LibrarySources.index(EpisodeSources.all())
        func kind(_ s: Session) -> SourceKind { LibrarySources.kind(received: s.isReceived, source: ix[s.id.uuidString]) }
        var out = [AskScope(id: "meeting", label: L("ask_scope_meeting"), ids: [mt.id], count: nil)]
        let k = kind(mt)
        if k == .podcast {
            let g = LibrarySources.groupKey(ix[mt.id.uuidString])
            let ids = Set(all.filter { kind($0) == .podcast && LibrarySources.groupKey(ix[$0.id.uuidString]) == g }.map(\.id))
            out.append(AskScope(id: "channel", label: L("ask_scope_channel"), ids: ids, count: ids.count))
        } else if let c = CollectionStore.shared.collectionOf(mt.id) {
            let ids = Set(all.filter { c.members.contains($0.id.uuidString) }.map(\.id))
            out.append(AskScope(id: "collection", label: L("ask_scope_collection"), ids: ids, count: ids.count))
        } else {
            let ids = Set(all.filter { kind($0) == k }.map(\.id))
            out.append(AskScope(id: "source", label: L(k == .meeting ? "source_meetings" : "source_received"), ids: ids, count: ids.count))
        }
        out.append(AskScope(id: "all", label: L("source_all"), ids: nil, count: all.count))
        return dedup(out, all: all.count)
    }

    /// Like Android's askLevels: a middle level that holds just this meeting or the whole library adds nothing.
    static func dedup(_ levels: [AskScope], all: Int) -> [AskScope] {
        levels.enumerated().filter { i, l in i == 0 || i == levels.count - 1 || ((l.count ?? 0) > 1 && (l.count ?? 0) < all) }.map(\.element)
    }
}

/// A UUID as a sheet item.
struct IdBox: Identifiable { let id: UUID }
