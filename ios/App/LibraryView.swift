import SwiftUI

/// Home screen (Android library): search, filter chips, sessions grouped by day as cards, row menu, queue link, record button.
struct LibraryView: View {
    @ObservedObject var m: Model
    @Binding var path: [Session]
    let onAdd: () -> Void
    let onSettings: () -> Void
    @State private var query = ""
    @State private var watching = Dev.env["VOX_WATCH"] != nil
    @State private var filter = Filter.all
    @State private var renaming: Session?
    @State private var deleting: Session?
    @State private var draft = ""
    @State private var selected = Set<UUID>()
    @State private var deletingMany = false
    @State private var sharing: URL?
    @AppStorage("seenSessions") private var seenRaw = ""
    @ObservedObject private var indexer = LibraryIndexer.shared
    @State private var hits: [LibrarySearch.Hit] = []
    @State private var searchTask: Task<Void, Never>?
    /// The query whose semantic half is still running: until it lands, no hit means "searching", not "no match".
    @State private var densePending: String?
    @State private var asking: String?
    /// Fused search over the library, debounced; lexical at once, semantic once the embedder answers.
    private func runSearch() {
        searchTask?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { hits = []; densePending = nil; return }
        let sessions = m.sessions, rec = m.recording
        hits = LibrarySearch.lexical(sessions, query: q)
        densePending = q.count >= 2 && EmbedHost.ready && EmbedHost.mayLoad(recording: rec) ? q : nil
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 250_000_000); if Task.isCancelled { return }
            let r = await Task.detached(priority: .userInitiated) { LibrarySearch.search(sessions, query: q, recording: rec) }.value
            if !Task.isCancelled { hits = r; if densePending == q { densePending = nil } }
        }
    }
    private var askReady: Bool { Prefs.askEnabled && AskEngine.available }
    private func openAt(_ s: Session, _ t: Double) { Self.markSeen(s.id); seenRaw = UserDefaults.standard.string(forKey: "seenSessions") ?? ""; m.openAt = (s.id, t); path.append(s) }

    enum Filter: String, CaseIterable { case all, new, done }
    private var seen: Set<String> { Set(seenRaw.split(separator: ",").map(String.init)) }
    static func markSeen(_ id: UUID) {
        var s = Set((UserDefaults.standard.string(forKey: "seenSessions") ?? "").split(separator: ",").map(String.init))
        s.insert(id.uuidString); UserDefaults.standard.set(s.joined(separator: ","), forKey: "seenSessions")
    }
    /// Android: NEW = recorded but not yet processed. Archived sessions are all processed (waiting jobs show in the queue line), so they are Done.
    private func isNew(_ s: Session) -> Bool { false }
    private var shown: [Session] {
        let base = m.sessions.filter { s in
            switch filter { case .all: true; case .new: isNew(s); case .done: !isNew(s) }
        }
        if query.trimmingCharacters(in: .whitespaces).isEmpty { return base }
        let order = Dictionary(uniqueKeysWithValues: hits.enumerated().map { ($0.element.session.id, $0.offset) })
        return base.filter { order[$0.id] != nil }.sorted { order[$0.id]! < order[$1.id]! }
    }
    private var days: [(Date, [Session])] {
        if !query.trimmingCharacters(in: .whitespaces).isEmpty { return shown.isEmpty ? [] : [(Date.distantPast, shown)] }   // search: ranked, not by day
        let g = Dictionary(grouping: shown) { Calendar.current.startOfDay(for: $0.date) }
        return g.keys.sorted(by: >).map { ($0, g[$0]!) }
    }
    /// Android: the passages of a search hit under the card, ≈ marking the ones only the meaning found.
    private func passages(_ s: Session) -> some View {
        let ps = hits.first { $0.session.id == s.id }?.passages.filter { $0.kind != .meeting || $0.semanticOnly } ?? []
        return VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(ps.enumerated()), id: \.offset) { _, p in
                Button { openAt(s, p.timeSec) } label: {
                    HStack(alignment: .top, spacing: 6) {
                        if p.semanticOnly { Text("≈").font(.caption.bold()).foregroundStyle(Color.accentColor).accessibilityLabel(L("search_semantic_marker")) }
                        if p.kind != .meeting { Text(Export.mmss(p.timeSec)).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
                        Text((p.speaker.map { $0 + ": " } ?? "") + p.snippet).font(.caption).lineLimit(2).multilineTextAlignment(.leading).foregroundStyle(.primary)
                    }
                }.buttonStyle(.plain)
            }
        }
    }
    private func label(_ d: Date) -> String {
        if Calendar.current.isDateInToday(d) { return L("today") }
        if Calendar.current.isDateInYesterday(d) { return L("yesterday") }
        return d.formatted(date: .abbreviated, time: .omitted)
    }
    private func chip(_ f: Filter) -> some View {
        let n = f == .all ? m.sessions.count : m.sessions.filter { f == .new ? isNew($0) : !isNew($0) }.count
        return Button { filter = f } label: {
            Text(L("filter_" + f.rawValue, n)).font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(filter == f ? Color.accentColor : Color(.secondarySystemGroupedBackground), in: Capsule())
                .foregroundStyle(filter == f ? Color.white : Color.primary)
        }.buttonStyle(.plain)
    }
    private func card(_ s: Session) -> some View {
        HStack(spacing: 12) {
            Image(systemName: isNew(s) ? "waveform" : "checkmark")
                .font(.title3.weight(.semibold)).frame(width: 40, height: 40)
                .foregroundStyle(isNew(s) ? Color.secondary : Color.green)
                .background((isNew(s) ? Color.secondary : Color.green).opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel(L(isNew(s) ? "cd_status_new" : "cd_status_done"))
            VStack(alignment: .leading, spacing: 2) {
                Text(s.title.isEmpty ? L("recent_untitled") : s.title).font(.headline).lineLimit(2)
                Text("\(s.date.formatted(date: .omitted, time: .shortened)) · \(Export.mmss(s.seconds))")
                    .font(.caption).foregroundStyle(.secondary)
                if !query.isEmpty { passages(s) }
                if indexer.indexing.contains(s.id) { Text(L("studio_indexing")).font(.caption2).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 0)
            if !selected.isEmpty {
                Image(systemName: selected.contains(s.id) ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(selected.contains(s.id) ? Color.accentColor : Color.secondary)
            } else { Menu {
                if let a = s.audio, FileManager.default.fileExists(atPath: JobQueue.audioDir.appendingPathComponent(a).path) {
                    Button(L("action_share_audio"), systemImage: "square.and.arrow.up") { sharing = JobQueue.audioDir.appendingPathComponent(a) }
                }
                Button(L("action_open"), systemImage: "play.fill") { Self.markSeen(s.id); seenRaw = UserDefaults.standard.string(forKey: "seenSessions") ?? ""; path.append(s) }
                Button(L("rename"), systemImage: "pencil") { draft = s.title; renaming = s }
                Button(L("delete"), systemImage: "trash", role: .destructive) { deleting = s }
            } label: { Image(systemName: "ellipsis").padding(10).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
                .accessibilityLabel(L("more")) }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.accentColor, lineWidth: selected.contains(s.id) ? 2 : 0))
        .contentShape(Rectangle())
        .onLongPressGesture { toggle(s) }
        .onTapGesture { if !selected.isEmpty { toggle(s); return }; Self.markSeen(s.id); seenRaw = UserDefaults.standard.string(forKey: "seenSessions") ?? ""; path.append(s) }
    }

    /// Row identity: Session equality is by id only, so a card whose search passages changed could keep
    /// its old body (seen for freshly imported meetings: no passages at all); the key changes with them.
    private func rowKey(_ s: Session) -> String {
        let ps = hits.first { $0.session.id == s.id }?.passages ?? []
        return s.id.uuidString + "|" + s.title + "|" + ps.map { "\($0.kind)\($0.uttIndex)\($0.semanticOnly)" }.joined(separator: ",")
    }
    private func jobCard(_ j: Job) -> some View {
        let active = j.id == m.activeJob
        return HStack(spacing: 12) {
            Group { if active { ProgressView() } else { Image(systemName: "clock").font(.title3.weight(.semibold)).foregroundStyle(.orange) } }
                .frame(width: 40, height: 40).background(Color.orange.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel(L(active ? "cd_status_processing" : "cd_status_queued"))
            VStack(alignment: .leading, spacing: 2) {
                Text(j.title ?? L("meeting")).font(.headline).lineLimit(2)
                Text(active ? m.status : m.failed[j.id] ?? L(m.parked.contains(j.id) ? "stop_resumable" : "cd_status_queued")).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer(minLength: 0)
            if active {
                Menu { Button(L("action_stop_processing"), systemImage: "stop.circle", role: .destructive) { m.stopProcessing() } }
                    label: { Image(systemName: "ellipsis").padding(10).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
            } else if m.parked.contains(j.id) {
                Menu { Button(L(m.failed[j.id] != nil ? "retry" : "action_resume"), systemImage: m.failed[j.id] != nil ? "arrow.clockwise" : "play.circle") { m.resume(j) }; Button(L("action_remove_from_queue"), systemImage: "xmark.circle", role: .destructive) { m.unqueue(j) } }
                    label: { Image(systemName: "ellipsis").padding(10).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
            } else if j.id != m.recordingJobId {
                Menu { Button(L("action_process_now"), systemImage: "text.line.first.and.arrowtriangle.forward") { m.processNext(j) }; Button(L("action_remove_from_queue"), systemImage: "xmark.circle", role: .destructive) { m.unqueue(j) } }
                    label: { Image(systemName: "ellipsis").padding(10).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
            }
        }
        .padding(12).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }
    private func toggle(_ s: Session) { if selected.contains(s.id) { selected.remove(s.id) } else { selected.insert(s.id) } }

    private func pillar(_ icon: String, _ key: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(.blue).frame(width: 32, height: 32).background(Color.blue.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(L(key + "_title")).font(.subheadline.weight(.semibold))
                Text(L(key + "_desc")).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
    var body: some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: 0).onAppear { if let q = Dev.env["VOX_LIBQUERY"] { DispatchQueue.main.asyncAfter(deadline: .now() + (Double(Dev.env["VOX_LIBQUERY_AT"] ?? "") ?? 1)) { query = q } }; if let q = Dev.env["VOX_ASK"] { UserDefaults.standard.set(true, forKey: "askEnabled"); DispatchQueue.main.asyncAfter(deadline: .now() + (Double(Dev.env["VOX_ASK_AT"] ?? "") ?? 2)) { asking = q } }; if let d = Dev.env["VOX_DIALOG"], let s = m.sessions.first { DispatchQueue.main.asyncAfter(deadline: .now() + 1) { if d == "rename" { draft = s.title; renaming = s } else if d == "delete" { deleting = s } } }; if Dev.env["VOX_SELECT"] != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { selected = Set(m.sessions.prefix(2).map(\.id)) } } }
            if !selected.isEmpty {
                HStack(spacing: 16) {
                    Button { selected = [] } label: { Image(systemName: "xmark").font(.title3) }.accessibilityLabel(L("cd_exit_selection"))
                    Text(L("selection_count", selected.count)).font(.title3.bold())
                    Spacer()
                    Button(L("select_all")) { selected = Set(shown.map(\.id)) }
                    Button(role: .destructive) { deletingMany = true } label: { Image(systemName: "trash").font(.title3) }
                }.padding(.horizontal, 16).padding(.vertical, 8)
            } else {
            HStack {
                BrandMark(size: 34)
                Text(L("app_name")).font(.title2.bold())
                Spacer()
                Button(action: onAdd) { Image(systemName: "plus").font(.title3) }.accessibilityLabel(L("import_audio"))
                Button(action: onSettings) { Image(systemName: "slider.horizontal.3").font(.title3) }.accessibilityLabel(L("settings")).padding(.leading, 12)
            }.padding(.horizontal, 16).padding(.vertical, 8)
            }
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField(L(EmbedHost.ready ? "studio_search_hint" : "search_sessions"), text: $query).submitLabel(askReady ? .send : .search)
                    .onSubmit { if askReady, !query.trimmingCharacters(in: .whitespaces).isEmpty { asking = query } }
                    .onChange(of: query) { _, _ in runSearch() }
                if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }.accessibilityLabel(L("search_close")) }
                if askReady && !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button { asking = query } label: { Image(systemName: "paperplane.fill") }.accessibilityLabel(L("ask_cd"))
                }
            }.padding(12).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 16)
            if !EmbedHost.ready && !m.sessions.isEmpty {
                if let p = m.embedProgress { Text(L("search_semantic_downloading", p)).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16).padding(.top, 6) }
                else { Button(L("search_semantic_download", ByteCountFormatter.string(fromByteCount: ReaderModel.embed.files.reduce(0) { $0 + $1.size }, countStyle: .file))) { m.downloadEmbed() }.font(.caption).padding(.horizontal, 16).padding(.top, 6) }
            }
            if !indexer.indexing.isEmpty || indexer.pending > 0 {
                HStack(spacing: 6) { ProgressView().controlSize(.mini); Text(L("studio_indexing") + (indexer.pending > 0 ? " (\(indexer.pending + indexer.indexing.count))" : "")).font(.caption).foregroundStyle(.secondary) }.padding(.horizontal, 16).padding(.top, 6)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack { ForEach(Filter.allCases.filter { $0 == .all || !m.sessions.isEmpty && ($0 == .done || m.sessions.contains { isNew($0) }) }, id: \.self) { chip($0) } }.padding(.horizontal, 16)
            }.padding(.vertical, 10)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if m.sessions.isEmpty && m.waiting.isEmpty {
                        VStack(spacing: 18) {
                            BrandMark(size: 72)
                            Text(L("empty_headline")).font(.title3.bold()).multilineTextAlignment(.center)
                            Text(L("empty_subtitle")).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            VStack(alignment: .leading, spacing: 12) {
                                pillar("lock.fill", "pillar_private")
                                pillar("icloud.slash.fill", "pillar_offline")
                                pillar("banknote.fill", "pillar_cost")
                            }.padding(.top, 6)
                            Text(L("library_empty")).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.top, 4)
                        }.frame(maxWidth: .infinity).padding(.top, 40)
                    }
                    if !m.sessions.isEmpty && days.isEmpty { Text(L(densePending != nil ? "studio_searching" : "studio_no_match")).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.top, 40) }
                    ForEach(m.waiting) { jobCard($0).id("job-" + $0.id.uuidString) }   // the session keeps the job's id: without a distinct identity SwiftUI keeps the job card's body when the row becomes a session
                    ForEach(days, id: \.0) { day, items in
                        if day != .distantPast { Text(label(day)).font(.footnote.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 6) }
                        ForEach(items) { s in card(s).id(rowKey(s)) }
                    }
                }.padding(.horizontal, 16)
            }
            if let d = m.downloading {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(d.isEmpty ? L("import_download_title") : d).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Spacer()
                        Button(L("cancel")) { m.cancelDownload() }.font(.subheadline)
                    }
                    if let f = m.downloadFraction { ProgressView(value: f) } else { ProgressView().progressViewStyle(.linear) }
                    Text(L("podcast_downloading", Int((m.downloadFraction ?? 0) * 100))).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }.padding(12).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 16).padding(.bottom, 6)
            }
            if m.activeJob != nil {
                HwStatusLine()
                Button { watching = true } label: { HStack(spacing: 8) { ProgressView().controlSize(.small); VStack(alignment: .leading, spacing: 2) { Text(L("studio_processing_banner", m.status)).font(.caption).lineLimit(1); if !m.notes.isEmpty { Text(L("agent_listening", m.notes.count)).font(.caption2).foregroundStyle(.secondary) } }; Spacer() }
                    .padding(10).background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12)) }.buttonStyle(.plain).padding(.horizontal, 16)
            } else {
                Text(m.status).font(.caption).foregroundStyle(.secondary).lineLimit(1).padding(.horizontal, 16)
            }
            if m.activeJob == nil && !m.recording && !m.waiting.isEmpty {
                Button { m.drain() } label: { Label(L("source_process_all", m.waiting.count), systemImage: "play.fill").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity).padding(.vertical, 10) }
                    .buttonStyle(.bordered).padding(.horizontal, 16)
            }
            Button { m.toggleRecord() } label: {
                Label(m.recording ? L("stop") : L("record"), systemImage: m.recording ? "stop.fill" : "mic.fill")
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 16)
                    .background(m.recording ? Color.red : Color.accentColor, in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(.white)
            }.padding(.horizontal, 16).padding(.vertical, 8)
        }
        .background(Color(.systemGroupedBackground))
        .alert(L("rename"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(L("title"), text: $draft)
            Button(L("done")) { if var s = renaming, !draft.trimmingCharacters(in: .whitespaces).isEmpty { s.title = draft.trimmingCharacters(in: .whitespaces); m.update(s) } }
            Button(L("cancel"), role: .cancel) {}
        }
        .confirmationDialog(L("delete_confirm_many", selected.count), isPresented: $deletingMany, titleVisibility: .visible) {
            Button(L("delete"), role: .destructive) { for s in m.sessions where selected.contains(s.id) { m.remove(s) }; selected = [] }
        }
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) { if let sharing { ShareSheet(url: sharing) } }
        .sheet(isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } })) { AskSheet(m: m, initial: asking ?? "") { s, t in asking = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { openAt(s, t) } } }
        .sheet(isPresented: $watching) { WatchLive(m: m) }   // on the root, not on the banner: the banner leaves with the job and would tear the sheet down
        .confirmationDialog(L("delete_confirm", deleting?.title ?? ""), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button(L("delete"), role: .destructive) { if let s = deleting { m.remove(s) } }
        }
    }
}

/// "Add audio" bottom sheet (Android AddSourceSheet, without the YouTube row: not offered on iOS).
struct AddSourceSheet: View {
    let onFile: () -> Void
    let onPodcast: () -> Void
    let onSession: () -> Void
    @Environment(\.dismiss) private var dismiss
    private func row(_ icon: String, _ title: String, _ desc: String, _ action: @escaping () -> Void) -> some View {
        Button { dismiss(); action() } label: {
            HStack(spacing: 16) {
                Image(systemName: icon).font(.title2).foregroundStyle(Color.accentColor).frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body).foregroundStyle(.primary)
                    Text(desc).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
            }.padding(.vertical, 10).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("add_audio")).font(.title3.bold()).padding(.bottom, 8)
            row("folder", L("source_audio_file"), L("source_audio_file_desc"), onFile)
            row("dot.radiowaves.left.and.right", L("source_podcast"), L("source_podcast_desc"), onPodcast)
            row("doc.badge.arrow.up", L("source_session"), L("source_session_desc"), onSession)
            Spacer(minLength: 0)
        }.padding(.horizontal, 20).padding(.top, 24).presentationDetents([.height(380)])
    }
}


struct WatchLive: View {
    @ObservedObject var m: Model
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) { ProgressView().controlSize(.small); Text(m.status).font(.subheadline) }
                    if m.agent.state == nil { Text(L("watch_live_hint")).font(.footnote).foregroundStyle(.secondary) }
                    AgentPanel(agent: m.agent) { _ in }
                        // The live notes name speakers as the booth does (S2 → 語者 2); with no live lines yet, any S-number.
                        .environment(\.speakerRefs, { [known = Set(m.lines.map(\.speaker))] t in
                            SpeakerRefs.resolve(t, label: { L("speaker_n", $0 + 1) }, known: known.isEmpty ? nil : known) })
                }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(L("action_watch_live")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } } }
            .onChange(of: m.activeJob) { _, j in if j == nil { dismiss() } }   // the job is over: the session is in the list, nothing left to watch
        }.presentationDetents([.medium, .large])
    }
}

/// The app icon (docs/brand), drawn from the bundle's icon file with the icon's own corner radius.
struct BrandMark: View {
    let size: CGFloat
    var body: some View {
        Group {
            if let img = UIImage(named: "AppIcon60x60") { Image(uiImage: img).resizable() }
            else { Color(red: 0x20 / 255, green: 0x37 / 255, blue: 0x60 / 255) }
        }
        .frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        .accessibilityHidden(true)
    }
}
