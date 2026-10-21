import SwiftUI

/// Home screen (Android library): search, filter chips, sessions grouped by day as cards, row menu, queue link, record button.
struct LibraryView: View {
    @ObservedObject var m: Model
    @Binding var path: [Route]
    let onAdd: () -> Void
    let onSettings: () -> Void
    @State private var query = ""
    @State private var filter = Filter.all
    /// The person whose meetings the list is narrowed to (nil: everyone).
    @State private var personFilter: String? = Dev.env["VOX_FILTER"].flatMap { n in PeopleStore.shared.doc.people.first { PersonNames.same($0.name, n, PeopleStore.fold) }?.id }
    @State private var renaming: Session?
    @State private var deleting: Session?
    @State private var draft = ""
    @State private var selected = Set<UUID>()
    @State private var deletingMany = false
    @State private var dlDeleteAsk: UUID?   // download item awaiting its delete confirmation
    @State private var sharing: URL?
    @AppStorage("seenSessions") private var seenRaw = ""
    @ObservedObject private var indexer = LibraryIndexer.shared
    @ObservedObject private var resumer = DownloadResumer.shared
    @State private var hits: [LibrarySearch.Hit] = []
    @State private var searchTask: Task<Void, Never>?
    /// The query whose semantic half is still running: until it lands, no hit means "searching", not "no match".
    @State private var densePending: String?
    @State private var asking: String?
    /// False until the library's first count change: that one is the launch load, not a finished meeting.
    @State private var countArmed = false
    /// Focus of the studio search field (Android parity: never focused on screen open;
    /// a tap outside the field resigns it, which hides the keyboard).
    @FocusState private var searchFocused: Bool
    /// Frame of the search row in global coordinates: the root tap gesture keeps the keyboard up
    /// for taps inside the row and resigns it for taps anywhere else (Android parity).
    @State private var searchRowFrame: CGRect = .zero
    /// Fused search over the library, debounced; lexical at once, semantic once the embedder answers.
    private func runSearch() {
        searchTask?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { hits = []; densePending = nil; return }
        let sessions = m.library.sessions, rec = m.capture.recording
        hits = LibrarySearch.lexical(sessions, query: q)
        densePending = q.count >= 2 && EmbedHost.ready && EmbedHost.mayLoad(recording: rec) ? q : nil
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 250_000_000); if Task.isCancelled { return }
            let r = await Task.detached(priority: .userInitiated) { LibrarySearch.search(sessions, query: q, recording: rec) }.value
            if !Task.isCancelled { hits = r; if densePending == q { densePending = nil } }
        }
    }
    @AppStorage("askOn") private var askOn = true
    /// Ask is on by default; its button shows once its models are downloaded (AskEngine.available).
    private var askReady: Bool { askOn && AskEngine.available }
    /// DEV (README animations): VOX_TYPE types a query; then VOX_TYPE_ASK sends it to Ask, or VOX_TYPE_OPEN=<text>
    /// opens the first passage containing that text once the results are in.
    private func devType(_ q: String) {
        if Dev.env["VOX_TYPE_ASK"] != nil { UserDefaults.standard.set(true, forKey: "askOn") }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            for i in 1...q.count { query = String(q.prefix(i)); try? await Task.sleep(nanoseconds: 220_000_000) }
            if Dev.env["VOX_TYPE_ASK"] != nil { try? await Task.sleep(nanoseconds: 1_000_000_000); asking = query; return }
            guard let pick = Dev.env["VOX_TYPE_OPEN"] else { return }
            @MainActor func found() -> (Session, Double)? {
                for h in hits { if let p = h.passages.first(where: { $0.snippet.contains(pick) }) { return (h.session, p.timeSec) } }
                return nil
            }
            for _ in 0..<80 where densePending != nil || found() == nil { try? await Task.sleep(nanoseconds: 250_000_000) }
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if let (s, t) = found() { openAt(s, t) }
        }
    }
    private func openAt(_ s: Session, _ t: Double) { Self.markSeen(s.id); seenRaw = UserDefaults.standard.string(forKey: "seenSessions") ?? ""; m.openAt = (s.id, t); path.append(.session(s)) }

    enum Filter: String, CaseIterable { case all, new, done }
    private var seen: Set<String> { Set(seenRaw.split(separator: ",").map(String.init)) }
    static func markSeen(_ id: UUID) {
        var s = Set((UserDefaults.standard.string(forKey: "seenSessions") ?? "").split(separator: ",").map(String.init))
        s.insert(id.uuidString); UserDefaults.standard.set(s.joined(separator: ","), forKey: "seenSessions")
    }
    /// Android: NEW = recorded but not yet processed. Archived sessions are all processed (waiting jobs show in the queue line), so they are Done.
    private func isNew(_ s: Session) -> Bool { false }
    /// Done = processed with something to read: a recording without speech is not (Android RowStatus.NoSpeech).
    private func isDone(_ s: Session) -> Bool { !isNew(s) && !s.noSpeech }
    private var shown: [Session] {
        let base = m.library.sessions.filter { s in
            guard personFilter.map({ p in s.speakerPeople?.values.contains(p) == true }) ?? true else { return false }
            switch filter { case .all: return true; case .new: return isNew(s); case .done: return isDone(s) }
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
                        Text((p.speaker.map { $0 + ": " } ?? "") + snippet(p, s)).font(.caption).lineLimit(2).multilineTextAlignment(.leading).foregroundStyle(.primary)
                    }
                }.buttonStyle(.plain)
            }
        }
    }
    /// A note names speakers by the reader's S-numbers: shown as the transcript names them (Android 150fe65d).
    private func snippet(_ p: LibrarySearch.Passage, _ s: Session) -> String {
        if p.kind == .chunk { return p.snippet }
        let known = Set(s.lines.map(\.speaker))
        return SpeakerRefs.resolve(p.snippet, label: { s.name($0) }, known: known.isEmpty ? nil : known)
    }
    private func label(_ d: Date) -> String {
        if Calendar.current.isDateInToday(d) { return L("today") }
        if Calendar.current.isDateInYesterday(d) { return L("yesterday") }
        return d.formatted(date: .abbreviated, time: .omitted)
    }
    private func chip(_ f: Filter) -> some View {
        let n = f == .all ? m.library.sessions.count : m.library.sessions.filter { f == .new ? isNew($0) : isDone($0) }.count
        return Button { filter = f } label: {
            Text(L("filter_" + f.rawValue, n)).font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(filter == f ? Color.accentColor : Color(.secondarySystemGroupedBackground), in: Capsule())
                .foregroundStyle(filter == f ? Color.white : Color.primary)
        }.buttonStyle(.plain)
    }
    private func card(_ s: Session) -> some View {
        HStack(spacing: 12) {
            Image(systemName: isNew(s) ? "waveform" : s.noSpeech ? "mic.slash" : "checkmark")
                .font(.title3.weight(.semibold)).frame(width: 40, height: 40)
                .foregroundStyle(isDone(s) ? Color.green : Color.secondary)
                .background((isDone(s) ? Color.green : Color.secondary).opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel(L(isNew(s) ? "cd_status_new" : s.noSpeech ? "status_no_speech" : "cd_status_done"))
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
                // Long-press selects and nothing advertises it; this is the visible path (Android: same item).
                Button(L("action_select"), systemImage: "checkmark.circle") { toggle(s) }
                if let a = s.audio, FileManager.default.fileExists(atPath: JobQueue.audioDir.appendingPathComponent(a).path) {
                    Button(L("action_share_audio"), systemImage: "square.and.arrow.up") { sharing = JobQueue.audioDir.appendingPathComponent(a) }
                }
                Button(L("action_open"), systemImage: "play.fill") { Self.markSeen(s.id); seenRaw = UserDefaults.standard.string(forKey: "seenSessions") ?? ""; path.append(.session(s)) }
                Button(L("rename"), systemImage: "pencil") { draft = s.title; renaming = s }
                Button(L("delete"), systemImage: "trash", role: .destructive) { deleting = s }
            // the outer "more" label wins — the inner cd_manage was dead code
            } label: { Image(systemName: "ellipsis").padding(12).contentShape(Rectangle()) }
                .accessibilityLabel(L("more")) }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.accentColor, lineWidth: selected.contains(s.id) ? 2 : 0))
        .contentShape(Rectangle())
        .onLongPressGesture { toggle(s) }
        .onTapGesture { activate(s) }
        // Gestures are invisible to VoiceOver: say it is a button, and offer the long-press as an action.
        .accessibilityAddTraits(selected.contains(s.id) ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction { activate(s) }
        .accessibilityAction(named: L("select")) { toggle(s) }
    }
    private func activate(_ s: Session) {
        if !selected.isEmpty { toggle(s); return }
        Self.markSeen(s.id); seenRaw = UserDefaults.standard.string(forKey: "seenSessions") ?? ""; path.append(.session(s))
    }

    /// Row identity: Session equality is by id only, so a card whose search passages changed could keep
    /// its old body (seen for freshly imported meetings: no passages at all); the key changes with them.
    private func rowKey(_ s: Session) -> String {
        let ps = hits.first { $0.session.id == s.id }?.passages ?? []
        return s.id.uuidString + "|" + s.title + "|" + ps.map { "\($0.kind)\($0.uttIndex)\($0.semanticOnly)" }.joined(separator: ",")
    }
    private func jobCard(_ j: Job) -> some View {
        let active = j.id == m.runner.activeJob, failedDl = j.download?.error != nil
        return HStack(spacing: 12) {
            JobGlyph(m: m, j: j)
            VStack(alignment: .leading, spacing: 2) {
                Text(j.title ?? L("meeting")).font(.headline).lineLimit(2)
                Text(m.jobStatus(j)).font(.caption).foregroundStyle(failedDl ? .red : .secondary).lineLimit(2)
                if let f = m.dlFraction(j) { ProgressView(value: f).tint(.blue).padding(.top, 4) }
            }
            Spacer(minLength: 0)
            if j.download != nil {
                Menu { DownloadActions(m: m, j: j, askDelete: { dlDeleteAsk = j.id }) }
                    label: { Image(systemName: "ellipsis").padding(12).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
            } else if active {
                Menu { Button(L("action_stop_processing"), systemImage: "stop.circle", role: .destructive) { m.runner.stopProcessing() } }
                    label: { Image(systemName: "ellipsis").padding(12).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
            } else if m.runner.parked.contains(j.id) {
                Menu { Button(L(m.runner.failed[j.id] != nil ? "retry" : "action_resume"), systemImage: m.runner.failed[j.id] != nil ? "arrow.clockwise" : "play.circle") { m.runner.resume(j) }; Button(L("action_remove_from_queue"), systemImage: "xmark.circle", role: .destructive) { m.runner.unqueue(j) } }
                    label: { Image(systemName: "ellipsis").padding(12).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
            } else if j.id != m.capture.recordingJob {
                Menu { Button(L("action_process_now"), systemImage: "text.line.first.and.arrowtriangle.forward") { m.runner.processNext(j) }; Button(L("action_remove_from_queue"), systemImage: "xmark.circle", role: .destructive) { m.runner.unqueue(j) } }
                    label: { Image(systemName: "ellipsis").padding(12).contentShape(Rectangle()).accessibilityLabel(L("cd_manage")) }
            }
        }
        .padding(12).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
        // Android: a queued or processing row opens the meeting screen, live while it runs (transcript, notes, player).
        .contentShape(Rectangle())
        .onTapGesture { if selected.isEmpty { path.append(.job(j.id)) } }
        .accessibilityAddTraits(.isButton).accessibilityAction { path.append(.job(j.id)) }
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
            Color.clear.frame(height: 0).onAppear { searchFocused = false; if let w = Dev.env["VOX_WATCH"] { DispatchQueue.main.asyncAfter(deadline: .now() + (Double(w) ?? 3)) { if let j = Dev.env["VOX_WATCH_LAST"] != nil ? m.runner.waiting.last : m.runner.waiting.first { path.append(.job(j.id)) } } }; if let q = Dev.env["VOX_LIBQUERY"] { DispatchQueue.main.asyncAfter(deadline: .now() + (Double(Dev.env["VOX_LIBQUERY_AT"] ?? "") ?? 1)) { query = q } }; if let q = Dev.env["VOX_ASK"] { UserDefaults.standard.set(true, forKey: "askOn"); DispatchQueue.main.asyncAfter(deadline: .now() + (Double(Dev.env["VOX_ASK_AT"] ?? "") ?? 2)) { asking = q } }; if let d = Dev.env["VOX_DIALOG"], let s = m.library.sessions.first { DispatchQueue.main.asyncAfter(deadline: .now() + 1) { if d == "rename" { draft = s.title; renaming = s } else if d == "delete" { deleting = s } } }; if Dev.env["VOX_SELECT"] != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { selected = Set(m.library.sessions.prefix(2).map(\.id)) } }; if let q = Dev.env["VOX_TYPE"] { devType(q) }; if let ids = Dev.env["VOX_DELETE"] { DispatchQueue.main.asyncAfter(deadline: .now() + 3) { let want = Set(ids.split(separator: ",").map(String.init)); for s in m.library.sessions where want.contains(s.id.uuidString) { m.library.remove(s); StatusLog.add("DEV deleted \(s.id.uuidString)") } } }; if let fs = Dev.env["VOX_RMDOC"] { for f in fs.split(separator: ",") where !f.contains("/") { try? FileManager.default.removeItem(atPath: NSHomeDirectory() + "/Documents/" + f) } } }
            if !selected.isEmpty {
                HStack(spacing: 16) {
                    Button { selected = [] } label: { Image(systemName: "xmark").font(.title3).tapTarget() }.accessibilityLabel(L("cd_exit_selection"))
                    Text(L("selection_count", selected.count)).font(.title3.bold())
                    Spacer()
                    Button(L("select_all")) { selected = Set(shown.map(\.id)) }
                    Button(role: .destructive) { deletingMany = true } label: { Image(systemName: "trash").font(.title3).tapTarget() }.accessibilityLabel(L("delete"))
                }.padding(.horizontal, 16).padding(.vertical, 8)
            } else {
            HStack {
                BrandMark(size: 34)
                Text(L("app_name")).font(.title2.bold())
                Spacer()
                Button(action: onAdd) { Image(systemName: "plus").font(.title3).tapTarget() }.accessibilityLabel(L("import_audio"))
                Button(action: onSettings) { Image(systemName: "slider.horizontal.3").font(.title3).tapTarget() }.accessibilityLabel(L("settings")).padding(.leading, 12)
            }.padding(.horizontal, 16).padding(.vertical, 8)
            }
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField(L(EmbedHost.ready ? "studio_search_hint" : "search_sessions"), text: $query).submitLabel(askReady ? .send : .search).autocorrectionDisabled()
                    .focused($searchFocused)
                    .onSubmit { searchFocused = false; if askReady, !query.trimmingCharacters(in: .whitespaces).isEmpty { asking = query } }
                    .onChange(of: query) { _, _ in runSearch() }
                if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary).tapTarget() }.accessibilityLabel(L("cd_clear_search")) }
                if askReady {   // labeled, always there: an empty search opens Ask with an empty question
                    Button { searchFocused = false; asking = query.trimmingCharacters(in: .whitespaces) } label: {
                        Label(L("ask_title"), systemImage: "bubble.left.and.text.bubble.right").font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 10).padding(.vertical, 6).background(Color.accentColor.opacity(0.14), in: Capsule())
                            .frame(minHeight: 44).contentShape(Rectangle())   // the capsule keeps its height; the hit area reaches 44 pt
                    }.buttonStyle(.plain).foregroundStyle(Color.accentColor).accessibilityLabel(L("ask_entry_cd"))
                }
            }.padding(12).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 16)
            .background(GeometryReader { g in Color.clear.preference(key: SearchRowFrameKey.self, value: g.frame(in: .global)) })
            .onPreferenceChange(SearchRowFrameKey.self) { searchRowFrame = $0 }
            if !EmbedHost.ready && !m.library.sessions.isEmpty {
                if let p = m.downloads.embedProgress { Text(L("search_semantic_downloading", p)).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16).padding(.top, 6) }
                else { Button(L("search_semantic_download", SizeFormat.bytes(ReaderModel.embed.files.reduce(0) { $0 + $1.size }))) { m.downloads.downloadEmbed() }.font(.caption).padding(.horizontal, 16).padding(.top, 6) }
            }
            if m.downloads.prefetchProgress != nil { ModelDownloadCard(downloads: m.downloads).padding(.horizontal, 16).padding(.top, 6) }
            else if resumer.paused > 0 && !m.imports.dlPaused {   // a run's own model download, waiting to resume
                HStack {
                    Image(systemName: "pause.circle").foregroundStyle(.orange).accessibilityHidden(true)   // the text beside it carries the state
                    Text(L("download_paused")).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button(L("download_resume")) { DownloadResumer.shared.kick() }.font(.caption.weight(.semibold))
                }.padding(.horizontal, 16).padding(.top, 6)
            }
            if !indexer.indexing.isEmpty || indexer.pending > 0 {
                HStack(spacing: 6) { ProgressView().controlSize(.mini); Text(L("studio_indexing") + (indexer.pending > 0 ? " (\(indexer.pending + indexer.indexing.count))" : "")).font(.caption).foregroundStyle(.secondary) }.padding(.horizontal, 16).padding(.top, 6)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack { ForEach(Filter.allCases.filter { $0 == .all || !m.library.sessions.isEmpty && ($0 == .done || m.library.sessions.contains { isNew($0) }) }, id: \.self) { chip($0) } }.padding(.horizontal, 16)
            }.padding(.vertical, 10)
            PeopleFilterChips(sessions: m.library.sessions, selected: $personFilter)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if m.library.sessions.isEmpty && m.runner.waiting.isEmpty {
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
                            if m.downloads.pending.any && m.downloads.prefetchProgress == nil {
                                Text(L("first_run_download_notice", SizeFormat.bytes(m.downloads.pending.total))).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            }
                        }.frame(maxWidth: .infinity).padding(.top, 40)
                    }
                    if !m.library.sessions.isEmpty && days.isEmpty { Text(L(densePending != nil ? "studio_searching" : "studio_no_match")).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.top, 40) }
                    ForEach(m.runner.waiting) { jobCard($0).id("job-" + $0.id.uuidString) }   // the session keeps the job's id: without a distinct identity SwiftUI keeps the job card's body when the row becomes a session
                    ForEach(days, id: \.0) { day, items in
                        if day != .distantPast { Text(label(day)).font(.footnote.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 6) }
                        ForEach(items) { s in card(s).id(rowKey(s)) }
                    }
                }.padding(.horizontal, 16)
            }
            .scrollDismissesKeyboard(.interactively)   // the search keyboard: dragging the list drops it
            if m.runner.activeJob != nil {
                Button { if let a = m.runner.activeJob { path.append(.job(a)) } } label: { HStack(spacing: 8) { ProgressView().controlSize(.small); VStack(alignment: .leading, spacing: 2) { Text(L("studio_processing_banner", m.screen.status)).font(.caption).lineLimit(1); if !m.screen.notes.isEmpty { Text(L("agent_listening", m.screen.notes.count)).font(.caption2).foregroundStyle(.secondary) } }; Spacer() }
                    .padding(10).background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12)) }.buttonStyle(.plain).padding(.horizontal, 16)
            } else {
                Text(m.screen.status).font(.caption).foregroundStyle(.secondary).lineLimit(1).padding(.horizontal, 16)
            }
            if m.runner.activeJob == nil && !m.capture.recording && m.runner.waiting.contains(where: { $0.download == nil }) {
                Button { m.runner.drain() } label: { Label(L("source_process_all", m.runner.waiting.filter { $0.download == nil }.count), systemImage: "play.fill").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity).padding(.vertical, 10) }
                    .buttonStyle(.bordered).padding(.horizontal, 16)
            }
            Button { Haptics.toggle(!m.capture.recording); if m.capture.recording { m.capture.toggleRecord() } else { m.downloads.withModels { m.capture.toggleRecord() } } } label: {
                Label(m.capture.recording ? L("stop") : L("record"), systemImage: m.capture.recording ? "stop.fill" : "mic.fill")
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 16)
                    .background(m.capture.recording ? Color.red : Color.accentColor, in: RoundedRectangle(cornerRadius: 16)).foregroundStyle(.white)
            }.padding(.horizontal, 16).padding(.vertical, 8)
        }
        .background(Color(.systemGroupedBackground))
        // A tap anywhere (cards, chips, background, record) resigns the search field, hiding the
        // keyboard; taps inside the search row keep it. simultaneousGesture so it fires alongside
        // the cards' own tap gestures. Android parity: outside tap clears focus.
        .simultaneousGesture(SpatialTapGesture().onEnded { v in
            if !searchRowFrame.insetBy(dx: -12, dy: -12).contains(v.location) { searchFocused = false }
        })
        // A meeting that finished processing lands in the library: confirm it by touch (a removal stays silent).
        .onChange(of: m.library.sessions.count) { prev, now in
            defer { countArmed = true }
            guard countArmed else { return }   // the first change is the launch load: not a completion
            if now > prev { Haptics.success() }
        }
        .alert(L("rename"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(L("title"), text: $draft)
                .submitLabel(.done).onSubmit { if var s = renaming, !draft.trimmingCharacters(in: .whitespaces).isEmpty { s.title = draft.trimmingCharacters(in: .whitespaces); m.library.update(s) } }
            Button(L("done")) { if var s = renaming, !draft.trimmingCharacters(in: .whitespaces).isEmpty { s.title = draft.trimmingCharacters(in: .whitespaces); m.library.update(s) } }
            Button(L("cancel"), role: .cancel) {}
        }
        .confirmationDialog(L("delete_confirm_many", selected.count), isPresented: $deletingMany, titleVisibility: .visible) {
            Button(L("delete"), role: .destructive) { for s in m.library.sessions where selected.contains(s.id) { m.library.remove(s) }; selected = []; Haptics.destructive() }
        }
        .sheet(isPresented: Binding(get: { sharing != nil }, set: { if !$0 { sharing = nil } })) { if let sharing { ShareSheet(url: sharing) } }
        .sheet(isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } })) { AskSheet(m: m, initial: asking ?? "") { s, t in asking = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { openAt(s, t) } } }
        .confirmationDialog(L("delete_confirm", deleting?.title ?? ""), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button(L("delete"), role: .destructive) { if let s = deleting { m.library.remove(s); Haptics.destructive() } }
        }
        .confirmationDialog(L("download_delete_confirm"), isPresented: Binding(get: { dlDeleteAsk != nil }, set: { if !$0 { dlDeleteAsk = nil } }), titleVisibility: .visible) {
            Button(L("delete"), role: .destructive) { if let id = dlDeleteAsk, let j = m.runner.waiting.first(where: { $0.id == id }) { m.imports.remove(j); Haptics.destructive() }; dlDeleteAsk = nil }
        }
    }
}

/// Global frame of the studio search row, for the outside-tap keyboard dismissal.
private struct SearchRowFrameKey: PreferenceKey {
    static var defaultValue: CGRect = .zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) { value = nextValue() }
}

/// "Add audio" bottom sheet (Android AddSourceSheet, without the YouTube row: not offered on iOS).
struct AddSourceSheet: View {
    let onFile: () -> Void
    let onPodcast: () -> Void
    let onPeerTube: () -> Void
    let onSession: () -> Void
    @Environment(\.dismiss) private var dismiss
    private func row(_ icon: String, _ title: String, _ desc: String, _ action: @escaping () -> Void) -> some View {
        Button { dismiss(); action() } label: {
            HStack(spacing: 16) {
                Image(systemName: icon).font(.title2).foregroundStyle(Color.accentColor).frame(width: 32).accessibilityHidden(true)   // the row's own text is the label
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
            row("play.rectangle", L("source_peertube"), L("source_peertube_desc"), onPeerTube)
            row("doc.badge.arrow.up", L("source_session"), L("source_session_desc"), onSession)
            Spacer(minLength: 0)
        }
        // medium/large, not a fixed 450 pt: a fixed sheet clips its rows at large text sizes.
        .padding(.horizontal, 20).padding(.top, 24).presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
    }
}


extension Model {
    /// A queue row's second line (and the live view's status): what the job is doing or waiting for.
    func jobStatus(_ j: Job) -> String {
        if let d = j.download {
            if let e = d.error { return L("dl_failed_reason", e) }
            guard imports.dlId == j.id else { return L("dl_waiting") }
            if imports.dlPaused { return pausedText(imports.dlBytes) }
            guard let (got, total) = imports.dlBytes, got > 0 else { return L("dl_downloading") }
            return total > 0 ? L("dl_progress", Int(Double(got) * 100 / Double(total)), SizeFormat.bytes(got), SizeFormat.bytes(total))
                : L("dl_progress_bytes", SizeFormat.bytes(got))
        }
        if j.id == runner.activeJob { return screen.status }
        return runner.failed[j.id] ?? L(runner.parked.contains(j.id) ? "stop_resumable" : "cd_status_queued")
    }
}

extension Model {
    /// The fraction downloaded of a podcast item downloading now (nil when not, or the size is unknown).
    func dlFraction(_ j: Job) -> Double? {
        guard j.download != nil, imports.dlId == j.id, let (got, total) = imports.dlBytes, total > 0 else { return nil }
        return min(1, Double(got) / Double(total))
    }
}

/// A queue row's leading glyph: downloading (blue), download failed (red), processing (spinner), queued (clock).
struct JobGlyph: View {
    @ObservedObject var m: Model
    let j: Job
    var body: some View {
        let failed = j.download?.error != nil, dl = j.download != nil, active = j.id == m.runner.activeJob
        let tint: Color = failed ? .red : dl ? .blue : .orange
        Group {
            if failed { Image(systemName: "exclamationmark.circle").font(.title3.weight(.semibold)).foregroundStyle(.red) }
            else if dl { Image(systemName: m.imports.dlPaused && m.imports.dlId == j.id ? "pause.circle" : "arrow.down.circle").font(.title3.weight(.semibold)).foregroundStyle(.blue) }
            else if active { ProgressView() }
            else { Image(systemName: "clock").font(.title3.weight(.semibold)).foregroundStyle(.orange) }
        }
        .frame(width: 40, height: 40).background(tint.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityLabel(L(failed ? "cd_status_download_failed" : dl ? "cd_status_downloading" : active ? "cd_status_processing" : "cd_status_queued"))
    }
}

/// A podcast item's actions while not downloaded: Resume now (paused) or Retry (failed), and Delete — which stops the
/// download and removes its partial file, so it never runs on the menu tap: `askDelete` opens the caller's confirm,
/// and `onDelete` (run by the caller after the confirm) also leaves the item's screen.
struct DownloadActions: View {
    @ObservedObject var m: Model
    let j: Job
    var askDelete: () -> Void = {}
    var onDelete: () -> Void = {}
    var body: some View {
        if j.download?.error != nil { Button(L("retry"), systemImage: "arrow.clockwise") { m.imports.retry(j) } }
        else if m.imports.dlId == j.id && m.imports.dlPaused { Button(L("download_resume"), systemImage: "play.circle") { m.imports.resumeDownload() } }
        Button(L("delete"), systemImage: "trash", role: .destructive) { askDelete() }
    }
}

/// A queued or processing job's screen (Android: the meeting screen watching the run). While it runs: the status and
/// progress, the reader's notes and the transcript as they come, and the audio playable meanwhile (the source file is
/// on disk before the run starts). Queued or stopped: the same screen with its actions. Once saved, it becomes the
/// meeting itself.
struct LiveJobView: View {
    @ObservedObject var m: Model
    let id: UUID
    @Binding var path: [Route]
    @StateObject private var player = Player(file: nil)
    @State private var tab = 0
    @State private var dlDeleteAsk = false   // download item awaiting its delete confirmation
    private var job: Job? { m.runner.waiting.first { $0.id == id } }
    private var active: Bool { m.runner.activeJob == id }
    /// A recording started meanwhile paints its own lines on the shared screen: the job's are not shown then.
    private var showsRun: Bool { active && !m.capture.recording }
    private static let palette = speakerPalette
    /// The speaker being named from the run's rows (Android: the transcript row's speaker chip, live).
    @State private var renamingSpeaker: Int?
    @State private var toast: String?
    /// The line being played (SessionView.current): highlighted, and followed while playing, as in a finished meeting.
    private var current: UUID? {
        guard player.engaged else { return nil }
        return m.screen.lines.last(where: { $0.start <= player.time })?.id
    }
    /// The run's speakers, in id order, as the people picker takes them.
    private var pickerSpeakers: [PickerSpeaker] {
        Array(Set(m.screen.lines.map(\.speaker))).sorted().map { PickerSpeaker(id: $0, label: m.screen.name($0), person: m.screen.names[$0]?.person) }
    }
    private func pickerSpeaker(_ k: Int) -> PickerSpeaker { pickerSpeakers.first { $0.id == k } ?? PickerSpeaker(id: k, label: m.screen.name(k), person: nil) }
    /// The run as a Session, for the naming sheet (voice suggestions, people usage): the id its meeting will have.
    private var liveSession: Session {
        var s = Session(title: job?.title ?? L("meeting"), summary: "", seconds: m.screen.lines.last?.end ?? 0, lines: m.screen.lines, notes: [])
        s.id = id; s.date = job?.date ?? Date(); s.audio = job?.audio; s.captureStart = job?.captureStart
        PeopleStore.apply(LiveNames.stored(m.screen.names), to: &s)
        return s
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                // SF Symbols carry built-in VoiceOver labels ("exclamation mark", "clock"…): these are
                // decoration for the status line next to them, so they must not be announced.
                if let j = job, j.download != nil {
                    Image(systemName: j.download?.error != nil ? "exclamationmark.circle" : "arrow.down.circle").foregroundStyle(j.download?.error != nil ? .red : .blue).accessibilityHidden(true)
                } else if active { ProgressView().controlSize(.small).accessibilityHidden(true) } else { Image(systemName: "clock").foregroundStyle(.orange).accessibilityHidden(true) }
                Text(job.map(m.jobStatus) ?? m.screen.status).font(.subheadline).lineLimit(3)
                Spacer(minLength: 0)
                if let j = job, j.download?.error != nil { Button(L("retry")) { m.imports.retry(j) }.font(.subheadline.weight(.semibold)) }
                else if let j = job, j.download != nil, m.imports.dlId == j.id, m.imports.dlPaused { Button(L("download_resume")) { m.imports.resumeDownload() }.font(.subheadline.weight(.semibold)) }
                // The engines' load while this job runs (Android: the same place, the status row of the meeting screen).
                if active { HwStatusLine() }
            }
            if active { ProgressView(value: m.runner.jobProgress) }
            else if let j = job, let f = m.dlFraction(j) { ProgressView(value: f).tint(.blue) }
            else if let j = job, j.download != nil, j.download?.error == nil, m.imports.dlId == j.id, !m.imports.dlPaused { ProgressView().progressViewStyle(.linear).tint(.blue) }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
    private var transcript: some View {
        LazyVStack(alignment: .leading, spacing: 8) {
            if m.screen.lines.isEmpty { Text(L(showsRun ? "capture_live_waiting" : "watch_live_queued")).font(.footnote).foregroundStyle(.secondary).padding(.top, 8) }
            ForEach(m.screen.lines) { l in
                let c = Self.palette[max(0, l.speaker) % Self.palette.count]
                HStack(alignment: .top, spacing: 10) {
                    RoundedRectangle(cornerRadius: 2).fill(c).frame(width: 3)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            // Naming a speaker of the run: the people picker, as in the finished meeting. The label is
                            // read from the screen's names, so confirming a name relabels every line of this speaker.
                            Button { renamingSpeaker = l.speaker } label: {
                                Text(m.screen.name(max(0, l.speaker))).font(.caption.bold()).foregroundStyle(c)
                            }.buttonStyle(.borderless)
                            Text(Export.mmss(l.start)).font(.caption2).foregroundStyle(.secondary)
                        }
                        Text(l.text)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 3).padding(.horizontal, 6)
                .background(l.id == current ? Color.accentColor.opacity(0.18) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                .id(l.id).contentShape(Rectangle())
                .onTapGesture { if player.available { player.seekPlay(l.start) } }
            }
        }
    }
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    header
                    if showsRun {
                        Picker("", selection: $tab) { Text(L("tab_summary")).tag(0); Text(L("tab_transcript")).tag(1) }.pickerStyle(.segmented)
                        if tab == 0 {
                            if m.screen.agent.state == nil { Text(L("watch_live_hint")).font(.footnote).foregroundStyle(.secondary) }
                            AgentPanel(agent: m.screen.agent) { sec in if player.available { player.seek(Double(sec)); if !player.playing { player.toggle() } } }
                        } else { transcript }
                    } else if let j = job {
                        Text(L(j.download != nil ? "dl_item_hint" : "watch_live_queued")).font(.footnote).foregroundStyle(.secondary)
                    }
                }.padding(16)
            }
            .onChange(of: m.screen.lines.count) { _, _ in if showsRun, tab == 1, !player.engaged, let l = m.screen.lines.last { withAnimation { proxy.scrollTo(l.id, anchor: .bottom) } } }
            // While playing, the played line is followed instead of the newest one.
            .onChange(of: current) { _, id in if showsRun, tab == 1, player.engaged, let id { withAnimation { proxy.scrollTo(id, anchor: .center) } } }
        }
        // Names arrive mid-run, so the reader's S-numbers resolve to them on screen: wrapped unbreakable, as the
        // watched run renders them on Android (MainActivity summary cards: refs(screen = true) / unbreakable).
        .environment(\.speakerRefs, { [known = Set(m.screen.lines.map(\.speaker))] t in
            SpeakerRefs.resolve(t, label: { m.screen.name($0) }, known: known.isEmpty ? nil : known, wrap: SpeakerRefs.unbreakable) })
        .background(Color(.systemGroupedBackground))
        .safeAreaInset(edge: .bottom) { if player.available { PlayerBar(player: player) } }
        .navigationTitle(job?.title ?? L("meeting")).navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(L("download_delete_confirm"), isPresented: $dlDeleteAsk, titleVisibility: .visible) {
            Button(L("delete"), role: .destructive) { if let j = job { m.imports.remove(j); Haptics.destructive() }; path.removeAll { $0 == .job(id) } }
        }
        // Naming a speaker mid-run: the same flow as the finished meeting (docs/design/speaker-tags.md §2.1).
        .sheet(isPresented: Binding(get: { renamingSpeaker != nil }, set: { if !$0 { renamingSpeaker = nil } })) {
            if let k = renamingSpeaker {
                VoiceSuggested(session: liveSession, speaker: k) { suggestions, note in
                    SpeakerNameFlow(speaker: pickerSpeaker(k), speakers: pickerSpeakers, sessions: m.library.sessions,
                                    suggestions: suggestions, voiceNote: note,
                                    onPlaySample: player.available ? { playSample(k) } : nil,
                                    onLink: { p in renamingSpeaker = nil; link(k, p) },
                                    onNew: { n in renamingSpeaker = nil; link(k, PeopleStore.shared.create(n)) },
                                    onClear: { renamingSpeaker = nil; clearName(k) },
                                    onMerge: { into in renamingSpeaker = nil; merge(k, into) },
                                    onDismiss: { renamingSpeaker = nil })
                }
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
            }
        }
        .overlay(alignment: .bottom) {
            if let toast { Text(toast).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10)
                .background(.thinMaterial, in: Capsule()).padding(.bottom, 90)
                // A toast is visual only: say it out loud too, as in the finished meeting (SessionView).
                .transition(.opacity).task { UIAccessibility.post(notification: .announcement, argument: toast); try? await Task.sleep(nanoseconds: 1_800_000_000); withAnimation { self.toast = nil } } }
        }
        .toolbar {
            if let j = job {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        if j.download != nil { DownloadActions(m: m, j: j, askDelete: { dlDeleteAsk = true }) { path.removeAll { $0 == .job(id) } } }
                        else if active { Button(L("action_stop_processing"), systemImage: "stop.circle", role: .destructive) { m.runner.stopProcessing() } }
                        else if m.runner.parked.contains(j.id) {
                            Button(L(m.runner.failed[j.id] != nil ? "retry" : "action_resume"), systemImage: m.runner.failed[j.id] != nil ? "arrow.clockwise" : "play.circle") { m.runner.resume(j) }
                            Button(L("action_remove_from_queue"), systemImage: "xmark.circle", role: .destructive) { m.runner.unqueue(j); path.removeAll { $0 == .job(id) } }
                        } else if j.id != m.capture.recordingJob {
                            Button(L("action_process_now"), systemImage: "text.line.first.and.arrowtriangle.forward") { m.runner.processNext(j); if m.runner.activeJob == nil { m.runner.drain() } }
                            Button(L("action_remove_from_queue"), systemImage: "xmark.circle", role: .destructive) { m.runner.unqueue(j); path.removeAll { $0 == .job(id) } }
                        }
                    } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(L("cd_more_options")) }
                }
            }
        }
        .onAppear {
            player.load(job?.audio)
            StatusLog.add("live view \(id): active=\(active) player=\(player.available) lines=\(m.screen.lines.count)")
            if Dev.env["VOX_PLAY"] != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 2) { if player.available && !player.playing { player.toggle() }; DispatchQueue.main.asyncAfter(deadline: .now() + 8) { StatusLog.add("live view player t=\(Int(player.time)) playing=\(player.playing) lines=\(m.screen.lines.count) status=\(m.screen.status)") } } }
        }
        .onChange(of: job?.audio) { _, a in player.load(a) }
        .onChange(of: job?.download == nil) { _, done in if done { player.load(job?.audio) } }   // downloaded: playable while it is processed
        .onDisappear { player.stop() }
        // Saved: the live view becomes the meeting (Android: the watched item's screen shows the result).
        .onReceive(NotificationCenter.default.publisher(for: .voxSessionSaved)) { n in
            guard let s = n.object as? Session, s.id == id, let i = path.lastIndex(of: .job(id)) else { return }
            player.stop(); path[i] = .session(s)
        }
    }

    // MARK: live speaker names (Android SessionState.speakerNames during a run)
    /// `k` is `p` of the directory (Android linkSpeaker): every live row of `k` reads the name from the screen model,
    /// so they all change in this frame.
    private func link(_ k: Int, _ p: Person) {
        m.screen.names = LiveNames.setting(m.screen.names, k, SpeakerLink(name: p.name, person: p.id))
        PeopleStore.shared.touch(p.id)
        VoiceLinks.impl?.confirmed(session: id, speaker: k, person: p.id)
        persistNames()
        withAnimation { toast = L("people_linked") }
    }
    /// Back to "Speaker n" (Android clearSpeakerName): the name goes at once, the person stays in the directory.
    private func clearName(_ k: Int) {
        let was = m.screen.names[k]?.person
        m.screen.names = LiveNames.cleared(m.screen.names, k)
        if let was { VoiceLinks.impl?.unlinked(session: id, speaker: k, person: was) }
        persistNames()
        withAnimation { toast = L("people_linked") }
    }
    /// Merge one speaker of the run into another (Android SpeakerEdits.merge): its lines move to the target, the
    /// merged id is gone and its name with it; the target keeps its own label.
    private func merge(_ from: Int, _ into: Int) {
        guard from != into else { return }
        for i in m.screen.lines.indices where m.screen.lines[i].speaker == from { m.screen.lines[i].speaker = into }
        m.screen.names = LiveNames.merging(m.screen.names, from: from, into: into)
        persistNames()
        withAnimation { toast = L("speaker_merged") }
    }
    /// Names land in the meeting as soon as it is in the library (a re-run, a resumed job): the live screen answers
    /// over what it stored, so a speaker the run never mentions loses its name (Android dropName) and the names of
    /// speakers the screen said nothing about stay as they were. Android defers this to the run's end (autoPersist
    /// skips while running); writing it at the tap is what keeps a mid-run kill from losing the answer.
    /// A recording that is not archived yet has no meeting to write to: the run's archive folds its names in.
    private func persistNames() {
        let live = m.screen.names, lines = m.screen.lines
        Task { @MainActor in
            guard var s = await m.library.all().first(where: { $0.id == id }) else { return }
            PeopleStore.apply(LiveNames.overlaid(PeopleStore.links(s), LiveNames.kept(live, lines)), to: &s)
            m.library.update(s)
        }
    }
    /// A short clip of the speaker (VoiceSuggested's sample): its longest clean line, 5 s (SessionView.playSample).
    private func playSample(_ k: Int) {
        guard let t = CleanSpeech.sampleStart(m.screen.lines, speaker: k) else { return }
        player.seek(t); if !player.playing { player.toggle() }
        Task { try? await Task.sleep(nanoseconds: 5_000_000_000); if player.playing, player.time >= t, player.time < t + 6 { player.toggle() } }
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

/// The first-use model download while it runs: bytes so far / total, percent and Cancel. On the library (empty or
/// not) and in Settings (Android ModelDownloadCard).
struct ModelDownloadCard: View {
    @ObservedObject var downloads: ModelDownloads
    var body: some View {
        if let p = downloads.prefetchProgress {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("\(SizeFormat.bytes(Int64(Double(downloads.prefetchTotal) * p))) / \(SizeFormat.bytes(downloads.prefetchTotal))").font(.subheadline.weight(.semibold))
                    Spacer()
                    if downloads.prefetchPaused { Button(L("download_resume")) { DownloadResumer.shared.kick() }.font(.subheadline.weight(.semibold)).padding(.trailing, 8) }
                    Button(L("cancel")) { downloads.cancelPrefetch() }.font(.subheadline)
                }
                ProgressView(value: p)
                Text(downloads.prefetchPaused ? L("download_paused") : L("dl_prefetching") + " \(Int(p * 100)) %").font(.caption).foregroundStyle(.secondary)
            }.padding(12).background(Color.accentColor.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

/// Settings: the download card in its own section while the prefetch runs (observes the downloads itself).
struct ModelDownloadSection: View {
    @ObservedObject var downloads: ModelDownloads
    var body: some View {
        if downloads.prefetchProgress != nil { Section { ModelDownloadCard(downloads: downloads) } }
    }
}

/// "Paused · 12 MB / 48 MB" (bytes unknown: just "Paused"), for an interrupted download that resumes by itself.
func pausedText(_ b: (Int64, Int64)?) -> String {
    guard let (got, total) = b, total > 0 else { return L("download_paused") }
    return L("download_paused_bytes", SizeFormat.bytes(got), SizeFormat.bytes(total))
}

/// DEV (device tests on a phone holding a real library): VOX_IDS writes Documents/ids.txt (queued jobs, then the
/// newest meetings: id, title); VOX_DEL=<uuid>,… deletes exactly those jobs / meetings, their audio and checkpoints.
@MainActor enum DevJobs {
    static func run(_ m: Model) {
        guard Dev.on else { return }
        if let ids = Dev.env["VOX_DEL"] {
            let del = Set(ids.split(separator: ",").compactMap { UUID(uuidString: String($0)) })
            Task {
                for j in await m.queue.all where del.contains(j.id) {
                    if j.download != nil { m.imports.remove(j); StatusLog.add("DEV deleted download item \(j.id)"); continue }
                    await m.queue.remove(j.id); Checkpoint.remove(j.id)
                    try? FileManager.default.removeItem(at: JobQueue.url(j))
                    StatusLog.add("DEV deleted job \(j.id)")
                }
                for s in await m.library.all() where del.contains(s.id) { m.library.remove(s); StatusLog.add("DEV deleted meeting \(s.id)") }
                await m.runner.syncQueue()
            }
        }
        // VOX_SNAP_ACTIVE=<spec>: VOX_SNAP's shots again each time the app comes back to the front (a paused download).
        if let sa = Dev.env["VOX_SNAP_ACTIVE"] {
            NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
                Task { @MainActor in StatusLog.add("DEV active: download \(m.imports.dlId?.uuidString ?? "-") paused=\(m.imports.dlPaused) bytes=\(m.imports.dlBytes.map { "\($0.0)" } ?? "-")"); Snap.schedule(sa) }
            }
        }
        if Dev.env["VOX_IDS"] != nil {
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                var o = (await m.queue.all).map { j in
                    let part = j.download.map { (try? FileManager.default.attributesOfItem(atPath: Podcast.partURL($0.url).path)[.size] as? Int64) ?? -1 }
                    return "job \(j.id) \(j.title ?? "")" + (j.download.map { " download error=\($0.error ?? "-") part=\(part ?? -1)" } ?? "")
                        + " audio=\(FileManager.default.fileExists(atPath: JobQueue.url(j).path)) status=\(m.jobStatus(j))"
                }
                o += (try? FileManager.default.contentsOfDirectory(atPath: JobQueue.audioDir.path).filter { $0.hasSuffix(".part") }.map { "part \($0)" }) ?? []
                o += (await m.library.all()).prefix(8).map { "meeting \($0.id) \($0.date.timeIntervalSince1970) \($0.title)" }
                try? o.joined(separator: "\n").write(to: URL(fileURLWithPath: NSHomeDirectory() + "/Documents/ids.txt"), atomically: true, encoding: .utf8)
            }
        }
    }
}
