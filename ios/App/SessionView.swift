import SwiftUI
import AVFoundation

/// Plays a session's recording and publishes the position (Android MediaPlayer + seek bar).
@MainActor final class Player: ObservableObject {
    @Published var time = 0.0
    @Published var playing = false
    /// True once the user played or seeked: the line under the cursor is highlighted and followed (also at 0:00 and when paused).
    @Published var engaged = false
    private(set) var duration = 0.0
    private var p: AVAudioPlayer?
    private var timer: Timer?
    var available: Bool { p != nil }

    init(file: String?) { load(file) }
    /// (Re)opens the file: a job's audio can appear after its view opened (a download landing). False when unreadable.
    @discardableResult func load(_ file: String?) -> Bool {
        guard p == nil, let file, let pl = try? AVAudioPlayer(contentsOf: JobQueue.audioDir.appendingPathComponent(file)) else { return p != nil }
        pl.prepareToPlay(); p = pl; duration = pl.duration; objectWillChange.send(); return true
    }
    func toggle() {
        guard let p else { return }
        if p.isPlaying { p.pause(); playing = false; timer?.invalidate(); return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default); try? AVAudioSession.sharedInstance().setActive(true)   // the recorder left .measurement, which plays very quietly
        p.play(); playing = true; engaged = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let p = self.p else { return }
                self.time = p.currentTime
                if !p.isPlaying { self.playing = false; self.timer?.invalidate() }
            }
        }
    }
    /// Android volume popup: mute toggle + level, applied to this player only.
    @Published var volume: Float = 1 { didSet { p?.volume = muted ? 0 : volume } }
    @Published var muted = false { didSet { p?.volume = muted ? 0 : volume } }
    func seek(_ t: Double) { p?.currentTime = max(0, min(t, duration)); time = p?.currentTime ?? 0; engaged = true }
    /// A tap on a line or the end of a slider drag: the cursor moves there and playback starts from it.
    func seekPlay(_ t: Double) { seek(t); if !playing { toggle() } }
    func stop() { previewStop?.cancel(); previewStop = nil; p?.stop(); timer?.invalidate(); playing = false }
    func pause() { guard let p, p.isPlaying else { return }; p.pause(); playing = false; timer?.invalidate() }
    /// Speaker-boundary editor preview: play [from, to] once, then pause (a newer preview cancels the older stop).
    private var previewStop: Task<Void, Never>?
    func preview(from a: Double, to b: Double) {
        guard let p else { return }
        previewStop?.cancel()
        seek(a); if !p.isPlaying { toggle() }
        previewStop = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(0.3, b - a) * 1_000_000_000))
            if Task.isCancelled { return }
            self?.pause(); self?.previewStop = nil
        }
    }
    /// Closing the editor stops a preview that is still playing (it must not run on into the meeting).
    func endPreview() { if previewStop != nil { previewStop?.cancel(); previewStop = nil; pause() } }
}

/// One finished meeting: player, summary (timestamps seek), notes, searchable transcript, rename, export.
struct SessionView: View {
    @State var s: Session
    let save: (Session) -> Void
    let rerun: (Session, Bool) -> Void
    /// Like Android: a run (re-summarize, re-transcribe) works on this meeting: no transcript edits, undo or "re-summarize?" until it ends.
    let busy: Bool
    @StateObject private var player: Player
    @AppStorage("showActions") private var showActions = false
    @State private var query = ""
    /// When the user last scrolled the page by hand: following the playing line holds off for a few seconds after it
    /// (Android: only while playing, 4 s after a manual scroll), so scrolling back to re-read is not snapped away.
    @State private var userScrolledAt = Date.distantPast
    @State private var renamingTitle = false
    @State private var draft = ""
    @State private var renamingSpeaker: Int?
    @State private var editingLine: UUID?
    @State private var moveLine: UUID?
    /// (from, into) awaiting confirmation: a merge rewrites every line of a speaker, one tap away in a menu.
    @State private var merging: (Int, Int)?
    @State private var transcriptDirty = false
    @State private var toast: String?
    @State private var editingSummary = false
    @State private var editingActions = false
    @State private var summaryStale = false
    @State private var undoable: Session?
    /// The last speaker move or merge, undoable for a few seconds (Android OfferSpeakerUndo snackbar).
    @State private var speakerUndo: SpeakerUndo?
    struct SpeakerUndo: Equatable { let id = UUID(); let message: String; let lines: [Utterance]; let names: [String: String]?; var people: [String: String]? = nil; let after: [Int]; let earlierEdit: Bool
        /// A name only (the people picker): the transcript is unchanged, no "re-summarize?" afterwards.
        var nameOnly = false
        static func == (a: Self, b: Self) -> Bool { a.id == b.id } }
    /// Speaker-boundary editor: the seam (index of the line that starts the new speaker) or the line to split.
    @State private var seamAt: Int?
    @State private var splitAt: Int?
    /// Transcript hand-edits (speaker moves and merges, boundary moves, splits, text, names), newest last:
    /// Undo / Redo in the toolbar while the meeting is open (Android SessionState.editUndo / editRedo).
    struct Step { let before: [Utterance]; let beforeNames: [String: String]?; let beforePeople: [String: String]?; let after: [Utterance]; let afterNames: [String: String]?; let afterPeople: [String: String]? }
    @State private var undoSteps: [Step] = []
    @State private var redoSteps: [Step] = []
    @State private var exporting = false
    @State private var exportFile: URL?
    @State private var showExport = Dev.env["VOX_EXPORT"] != nil
    /// "Email participants" (docs/design/speaker-contacts.md §4). DEV: VOX_MAIL=1 (or preview) opens it.
    @State private var mailOpen = Dev.env["VOX_MAIL"] != nil
    @ObservedObject private var peopleStore = PeopleStore.shared

    let openAt: Double?
    init(session: Session, save: @escaping (Session) -> Void, rerun: @escaping (Session, Bool) -> Void = { _, _ in }, openAt: Double? = nil, busy: Bool = false) {
        _s = State(initialValue: session); self.save = save; self.rerun = rerun; self.openAt = openAt; self.busy = busy
        if openAt != nil { _tab = State(initialValue: 1) }
        if let t = Dev.env["VOX_TAB"].flatMap(Int.init) { _tab = State(initialValue: t) }
        if let q = Dev.env["VOX_QUERY"] { _query = State(initialValue: q); _searching = State(initialValue: true) }; if Dev.env["VOX_SEM"] != nil { _semantic = State(initialValue: true) }
        _player = StateObject(wrappedValue: Player(file: session.audio))
        switch Dev.env["VOX_DIALOG"] {   // README / guide screenshots: open one dialog without taps
        case "title": _draft = State(initialValue: session.title); _renamingTitle = State(initialValue: true)
        case "speaker": _draft = State(initialValue: ""); _renamingSpeaker = State(initialValue: Dev.env["VOX_SPEAKER"].flatMap(Int.init) ?? session.lines.first?.speaker)
        case "line": _draft = State(initialValue: session.lines.first?.text ?? ""); _editingLine = State(initialValue: session.lines.first?.id)
        case "summary": _editingSummary = State(initialValue: true)
        default: break
        }
    }
    private var shown: [Utterance] { s.lines }
    private var current: UUID? {
        guard player.engaged else { return nil }
        return s.lines.last(where: { (l: Utterance) -> Bool in l.start <= player.time })?.id
    }

    @State private var tab = 0
    @State private var searching = false
    @State private var match = 0
    @FocusState private var searchFocus: Bool
    private static let palette = speakerPalette
    private var names: [(String, Color)] { Array(Set(s.lines.map(\.speaker))).sorted().map { (SpeakerRefs.unbreakable(s.name($0)), tint($0)) } }
    /// Model text as shown: its S1, S2 become the transcript's speaker names (Android refs(screen = true)).
    private func refs(_ t: String, screen: Bool = true) -> String {
        SpeakerRefs.resolve(t, label: { s.name($0) }, known: Set(s.lines.map(\.speaker)), wrap: screen ? SpeakerRefs.unbreakable : { $0 })
    }
    private func tint(_ spk: Int) -> Color { Self.palette[max(0, spk) % Self.palette.count] }
    /// Lexical matches, plus (≈ on) the utterances of the semantic chunks the index ranks for the query, in transcript order.
    private var hits: [Utterance] {
        if query.isEmpty { return [] }
        let lex = s.lines.filter { $0.text.localizedCaseInsensitiveContains(query) }
        guard semantic, !semIds.isEmpty else { return lex }
        let ids = Set(lex.map(\.id)).union(semIds)
        return s.lines.filter { ids.contains($0.id) }
    }
    @State private var semantic = false
    @State private var semIds = Set<UUID>()
    /// In-meeting search (docs/design/in-session-search-ask.md): this meeting's passages found by meaning (notes and
    /// chunks), whether the results list under the bar is unfolded, the line a result tap scrolls to.
    @State private var semPassages: [MeetingSearch.Dense] = []
    @State private var resultsOpen = true
    @State private var jumpTo: UUID?
    /// In-meeting Ask: the question ("" = open on the field); nil = closed. DEV: VOX_ASK_MEETING="…" opens it.
    @State private var askMeeting: String? = Dev.env["VOX_ASK_MEETING"]
    /// Needs the AI notes model only (without the search model it uses keywords).
    private var askMeetingReady: Bool { AskEngine.enabled && Storage.ready(Prefs.reader) && !s.lines.isEmpty }
    /// The search model's cold load was most of an in-meeting Ask's retrieval (Note10+: ~4–6 s of it, the passes
    /// 40 ms): load it while the user types.
    static func preloadEmbedder() { guard EmbedHost.ready else { return }; Task.detached(priority: .utility) { _ = EmbedHost.use(threads: 2) { _ in 0 } } }
    private var meetingResults: [MeetingSearch.Result] {
        guard searching else { return [] }
        return MeetingSearch.results(query, lines: s.lines.map { MeetingSearch.Line(start: $0.start, text: $0.text, speaker: $0.speaker) },
                                     notes: s.notes.map { MeetingSearch.NoteIn(ts: $0.ts, text: $0.text) }, dense: semantic ? semPassages : [])
    }
    /// A result tapped: the list folds, the transcript shows the line, the player is parked there (paused).
    private func pick(_ r: MeetingSearch.Result) {
        resultsOpen = false; searchFocus = false
        guard s.lines.indices.contains(r.line) else { return }
        let l = s.lines[r.line]
        if let i = hits.firstIndex(where: { $0.id == l.id }) { match = i }
        jumpTo = nil; DispatchQueue.main.async { jumpTo = l.id }
        // At the line's start: a note's whole-second time is before its line, which made the line above the playing one.
        if player.available { player.seek(l.start) }
    }
    private var resultsList: some View {
        let rs = meetingResults
        let notes = rs.filter { $0.kind == .note }, lines = rs.filter { $0.kind == .line }
        return ScrollView {
            // A VStack (≤ 50 rows): a LazyVStack nested in the page's own scroll view left rows blank.
            VStack(alignment: .leading, spacing: 0) {
                if !notes.isEmpty { resultsHeader(L("ai_notes"), notes.count); ForEach(Array(notes.enumerated()), id: \.offset) { _, r in resultRow(r) } }
                if !lines.isEmpty { resultsHeader(L("search_results_transcript"), lines.count); ForEach(Array(lines.enumerated()), id: \.offset) { _, r in resultRow(r) } }
                if rs.count >= MeetingSearch.maxResults { Text(L("search_results_more", MeetingSearch.maxResults)).font(.caption2).foregroundStyle(.secondary).padding(.horizontal, 12).padding(.vertical, 4) }
                if askMeetingReady {
                    Button { askMeeting = query.trimmingCharacters(in: .whitespaces) } label: {
                        Label(L("ask_meeting_query", query.trimmingCharacters(in: .whitespaces)), systemImage: "sparkles").font(.subheadline.weight(.semibold)).lineLimit(1)
                    }.buttonStyle(.plain).foregroundStyle(Color.accentColor).padding(.horizontal, 12).padding(.vertical, 10).accessibilityIdentifier("results-ask-meeting")
                }
            }
        }.frame(maxHeight: 280).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityIdentifier("meeting-search-results")
    }
    private func resultsHeader(_ t: String, _ n: Int) -> some View {
        Text("\(t) · \(n)").font(.caption.weight(.bold)).foregroundStyle(.secondary).padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 2)
    }
    private func resultRow(_ r: MeetingSearch.Result) -> some View {
        Button { pick(r) } label: {
            HStack(alignment: .top, spacing: 6) {
                Text(Export.mmss(r.timeSec)).font(.caption.monospacedDigit()).foregroundStyle(Color.accentColor).fixedSize().frame(minWidth: 48, alignment: .leading)   // a fixed 48 pt clipped the time at large text sizes
                if r.semanticOnly { Text("≈").font(.caption.bold()).foregroundStyle(Color.accentColor).accessibilityLabel(L("search_semantic_marker")) }
                (r.speaker.map { Text(s.name($0) + "  ").font(.caption.weight(.semibold)).foregroundColor(.secondary) } ?? Text(""))
                    + Text(Self.marked(MeetingSearch.snippet(r.text, query), query)).font(.caption)
            }.lineLimit(2).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 12).padding(.vertical, 6).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
    @State private var semTask: Task<Void, Never>?
    /// ≈ is on but this meeting has no index yet (Android TranscriptSearch `indexing`): say so instead of "no matches",
    /// ask for the build once, and search again when it lands.
    @State private var semIndexing = false
    @State private var indexAsked = false
    private var hasIndex: Bool { FileManager.default.fileExists(atPath: SessionIndex.url(s.id).path) }
    private func semSearch() {
        semTask?.cancel(); semIds = []; semPassages = []; semIndexing = false
        let q = query.trimmingCharacters(in: .whitespaces); guard semantic, q.count >= 2, EmbedHost.ready else { return }
        if !hasIndex {
            semIndexing = true
            // While a run works on this meeting the index is asked for when it saves the result.
            if !indexAsked, !busy { indexAsked = true; LibraryIndexer.shared.request(s) }
            return
        }
        let s = self.s
        semTask = Task {
            try? await Task.sleep(nanoseconds: 250_000_000); if Task.isCancelled { return }
            let (ids, ps) = await Task.detached(priority: .userInitiated) { () -> (Set<UUID>, [MeetingSearch.Dense]) in
                guard let qv = EmbedHost.use(threads: 2, { $0.embed(EmbedPrompts.query(q)) }) ?? nil else { return ([], []) }
                var out = Set<UUID>(), ps: [MeetingSearch.Dense] = []
                for h in LibrarySearch.dense([s], qv: qv, maxPassages: MeetingSearch.densePassages, snippetChars: 300) {
                    for p in h.passages where p.kind != .meeting {
                        ps.append(MeetingSearch.Dense(note: p.kind == .note, uttIndex: p.uttIndex, uttEnd: p.uttEnd, timeSec: p.timeSec, snippet: p.snippet))
                        // The stepper keeps walking the 5 best chunks' lines, as before.
                        if p.kind == .chunk, out.count < 64, ps.filter({ !$0.note }).count <= 5 { for i in p.uttIndex...p.uttEnd where s.lines.indices.contains(i) { out.insert(s.lines[i].id) } }
                    }
                }
                return (out, ps)
            }.value
            if !Task.isCancelled { semIds = ids; semPassages = ps; match = 0 }
        }
    }
    @State private var showProcess = Dev.env["VOX_PROCESS"] != nil

    private func card<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) { c() }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }
    private var playerBar: some View { PlayerBar(player: player) }
    private var summaryTab: some View {
        VStack(spacing: 12) {
            // Android: the agent card exists only when the reading left notes (agent.restore found a journal).
            if !s.notes.isEmpty { card {
                HStack {
                    Circle().fill(Color.green).frame(width: 9, height: 9)
                    VStack(alignment: .leading) {
                        Text(L("ai_notes")).font(.headline)
                        Text(L("notes_n", s.notes.count)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(L("done_pill")).font(.caption.weight(.semibold)).padding(.horizontal, 10).padding(.vertical, 4)
                        .background(Color.green.opacity(0.15), in: Capsule()).foregroundStyle(.green)
                }
                // Android AgentPanel: "show process" under the header; opened, the notes, the caution, then "hide process".
                if showProcess {
                    ForEach(s.notes) { n in NoteRow(n: n) { sec in if player.available { player.seek(Double(sec)); if !player.playing { player.toggle() } } } }
                    Text(L("agent_notes_caution")).font(.caption2).foregroundStyle(.secondary)
                    // DEV builds: the saved reading's off-protocol counts (model guardrail).
                    if Dev.on, let g = s.readerGuard { Text("DEV guard · " + g.summary).font(.caption2.monospaced()).foregroundStyle(g.exceeds(ModelCard.default.reader.guardrail) ? Color.red : Color.secondary) }
                }
                Button(L(showProcess ? "hide_process" : "show_process")) { withAnimation { showProcess.toggle() } }.font(.subheadline)
            } }
            if !s.summary.isEmpty {
                card {
                    // No heading: the tab already carries the name (Android SummaryCard showHeading = false in portrait).
                    HStack(spacing: 16) { Spacer()
                        Button { UIPasteboard.general.string = refs(s.summary, screen: false); toast = L("summary_copied"); Haptics.success() } label: { Image(systemName: "doc.on.doc").tapTarget() }.accessibilityLabel(L("cd_copy_summary"))
                        Button { draft = s.summary; editingSummary = true } label: { Image(systemName: "pencil").tapTarget() }.accessibilityLabel(L("cd_edit")) }
                    Collapsible(lines: 12) { Text(Markdown.render(refs(s.summary), anchors: player.available ? Color.secondary : nil, speakers: names)) }.environment(\.openURL, OpenURLAction { u in
                        if u.scheme == "vox", let t = Double(u.host ?? "") { player.seek(t); if !player.playing { player.toggle() }; return .handled }
                        return .systemAction })
                    Text(L("ai_disclaimer")).font(.caption2).foregroundStyle(.secondary)
                }
            }
            if showActions, let a = s.actionItems {
                card {
                    HStack(spacing: 16) {
                        Text(L("card_action_items")).font(.headline); Spacer()
                        Button { UIPasteboard.general.string = refs(a, screen: false); toast = L("action_items_copied"); Haptics.success() } label: { Image(systemName: "doc.on.doc").tapTarget() }.accessibilityLabel(L("cd_copy_actions"))
                        Button { draft = a; editingActions = true } label: { Image(systemName: "pencil").tapTarget() }.accessibilityLabel(L("cd_edit"))
                    }
                    Collapsible(lines: 8) { Text(Markdown.render(refs(a), anchors: player.available ? Color.secondary : nil, speakers: names)) }.environment(\.openURL, OpenURLAction { u in
                        if u.scheme == "vox", let t = Double(u.host ?? "") { player.seek(t); if !player.playing { player.toggle() }; return .handled }
                        return .systemAction })
                    Text(L("actions_verify_hint")).font(.caption2).foregroundStyle(.orange)
                }
            }
            // Android NotesSection: what the v2 NOTES format adds to a prose summary, an empty section shows nothing.
            if let n = s.legacyNotes.flatMap(MeetingNotes.parse) {
                notesSection(L("notes_decisions"), n.decisions)
                notesSection(L("notes_open"), n.open)
                notesSection(L("notes_topics"), n.topics)
                ForEach(n.extra) { notesSection($0.key, $0.lines) }
            }
            if !s.lines.isEmpty { SpeakerStats(lines: s.lines, names: s.speakerNames, palette: Self.palette).id("stats") }
            if peopleStore.peopleOf(s).values.contains(where: { !$0.emails.isEmpty }) {
                HStack { Spacer(); Button { mailOpen = true } label: { Label(L("mail_chip"), systemImage: "envelope") }.buttonStyle(.bordered).accessibilityIdentifier("speakers-email") }
            }
            if s.summary.isEmpty { Text(L(s.lines.isEmpty ? "status_no_speech" : "summary_missing_hint")).font(.subheadline).foregroundStyle(.secondary).padding(.top, 12) }
        }.padding(.horizontal, 16)
    }
    @ViewBuilder private func notesSection(_ header: String, _ items: [String]) -> some View {
        if !items.isEmpty {
            card {
                Text(header).font(.subheadline.bold())
                ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                    Text(Markdown.render("- " + refs(it), anchors: player.available ? .blue : nil, speakers: names)).font(.subheadline)
                }
            }.environment(\.openURL, OpenURLAction { u in
                if u.scheme == "vox", let t = Double(u.host ?? "") { player.seek(t); if !player.playing { player.toggle() }; return .handled }
                return .systemAction })
        }
    }
    private var transcriptTab: some View {
        LazyVStack(alignment: .leading, spacing: 8) {
            let speakers = Array(Set(s.lines.map(\.speaker))).sorted()
            if speakers.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack { ForEach(speakers, id: \.self) { k in
                        HStack(spacing: 5) { Circle().fill(tint(k)).frame(width: 8, height: 8); Text(s.name(k)).font(.caption.weight(.semibold)) }
                            .padding(.horizontal, 10).padding(.vertical, 5).background(Color(.secondarySystemGroupedBackground), in: Capsule())
                    } }
                }
            }
            if searching {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                    TextField(L("search_transcript_hint"), text: $query).focused($searchFocus).submitLabel(.search).autocorrectionDisabled()
                        .onChange(of: query) { _, _ in match = 0; resultsOpen = true; semSearch() }
                    if EmbedHost.ready {
                        Button { semantic.toggle(); semSearch() } label: { Text("≈").font(.headline).padding(.horizontal, 6).padding(.vertical, 2).background(semantic ? Color.accentColor.opacity(0.25) : Color.clear, in: RoundedRectangle(cornerRadius: 6)).tapHeight() }
                            .buttonStyle(.plain).accessibilityLabel(L("search_semantic_cd")).accessibilityAddTraits(semantic ? .isSelected : [])
                    }
                    if !query.isEmpty {
                        Text(hits.isEmpty ? L(semantic && semIndexing ? "studio_indexing" : "search_no_matches") : L("search_match_count", match + 1, hits.count)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        Button { stepMatch(-1) } label: { Image(systemName: "chevron.up").tapHeight() }.accessibilityLabel(L("search_prev"))
                        Button { stepMatch(1) } label: { Image(systemName: "chevron.down").tapHeight() }.accessibilityLabel(L("search_next"))
                    }
                    if !query.isEmpty && !meetingResults.isEmpty {
                        Button { resultsOpen.toggle() } label: { Image(systemName: resultsOpen ? "chevron.up.square" : "list.bullet").tapHeight() }
                            .accessibilityLabel(L(resultsOpen ? "search_results_hide" : "search_results_show"))
                    }
                    if askMeetingReady {
                        Button { askMeeting = query.trimmingCharacters(in: .whitespaces) } label: { Image(systemName: "sparkles").tapHeight() }
                            .accessibilityLabel(L("ask_meeting_title")).accessibilityIdentifier("search-ask-meeting")
                    }
                    Button { searching = false; query = "" } label: { Image(systemName: "xmark.circle.fill").tapHeight() }.accessibilityLabel(L("search_close"))
                }.padding(10).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
                if semantic && semIndexing {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small).accessibilityHidden(true)
                        Text(L("search_semantic_indexing")).font(.caption).foregroundStyle(.secondary)
                    }.padding(.horizontal, 12).accessibilityElement(children: .combine).accessibilityIdentifier("search-semantic-indexing")
                }
                if resultsOpen && !meetingResults.isEmpty { resultsList }
            }
            if !s.lines.isEmpty { Text(L("pipeline_transcript_via", "X-ASR", L("pipeline_diar_nemotron"))).font(.caption2).foregroundStyle(.secondary) }
            // A session with no lines had no guidance at all (the summary tab has one): say why the tab is blank.
            if s.lines.isEmpty { Text(L(s.noSpeech ? "status_no_speech" : "transcript_empty")).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity).multilineTextAlignment(.center).padding(.top, 40) }
            ForEach(Array(shown.enumerated()), id: \.element.id) { idx, l in
                // A seam handle between two speakers: adjust where one stops and the next starts.
                if idx > 0, shown[idx - 1].speaker != l.speaker, !busy {
                    SpeakerSeam(colorA: tint(shown[idx - 1].speaker), colorB: tint(l.speaker), labelA: s.name(shown[idx - 1].speaker), labelB: s.name(l.speaker)) { seamAt = idx }
                        .padding(.vertical, -4)
                }
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Circle().fill(tint(l.speaker)).frame(width: 8, height: 8)
                        Button { renamingSpeaker = l.speaker } label: { Text(s.name(l.speaker)).font(.caption.bold()) }.buttonStyle(.borderless)
                        Text(Export.mmss(l.start)).font(.caption2).foregroundStyle(.secondary)
                    }
                    Text(Self.marked(l.text, query))
                }
                .id(l.id).padding(10).frame(maxWidth: .infinity, alignment: .leading)
                .background(l.id == current ? Color.accentColor.opacity(0.18) : (hits.indices.contains(match) && hits[match].id == l.id ? Color.yellow.opacity(0.22) : Color(.secondarySystemGroupedBackground)), in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
                .onTapGesture { if player.available { player.seekPlay(l.start) } }
                .contextMenu { if !busy { lineMenu(l) } }
                .accessibilityAction { if player.available { player.seekPlay(l.start) } }   // VoiceOver's default action: play from this line
                .accessibilityAction(named: L("cd_line_actions")) { if !busy { editingLine = l.id; draft = l.text } }
                // The line menu (move/merge) is long-press only, unreachable to VoiceOver: a named action opens the same move.
                .accessibilityAction(named: L("speaker_move_line")) { if !busy { moveLine = l.id } }
            }
        }.padding(.horizontal, 16)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 12) {
                    if busy { RunStatusRow(id: s.id).padding(.horizontal, 16) }
                    Picker("", selection: $tab) { Text(L("tab_summary")).tag(0); Text(L("tab_transcript")).tag(1) }
                        .pickerStyle(.segmented).padding(.horizontal, 16)
                    if tab == 0 { summaryTab } else { transcriptTab }
                }.padding(.top, 8)
            }
            .scrollDismissesKeyboard(.interactively)   // the transcript search keyboard: dragging the transcript drops it
            .modifier(UserScrollWatch { userScrolledAt = Date() })   // on the scroll view only: a drag on the player bar's slider is not a scroll
            .background(Color(.systemGroupedBackground))
            .onAppear { if searching, semantic, !query.isEmpty { semSearch() }; if let t = Dev.env["VOX_PLAYAT"].flatMap(Double.init) { DispatchQueue.main.asyncAfter(deadline: .now() + 1) { player.seek(t); if !player.playing { player.toggle() } } }; if Dev.env["VOX_SCROLL"] != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { proxy.scrollTo("stats", anchor: .top) } }
                if let t = openAt { DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { let i = MeetingSearch.lineAt(starts: s.lines.map(\.start), t); if s.lines.indices.contains(i) { withAnimation { proxy.scrollTo(s.lines[i].id, anchor: .center) } }; if player.available { player.seek(s.lines.indices.contains(i) ? s.lines[i].start : t) } } } }
            .safeAreaInset(edge: .bottom) { if player.available { playerBar } }
            // Follow the playing line only while it plays, not while a search is open, and not right after a manual scroll.
            .onChange(of: current) { _, id in if player.playing, query.isEmpty, tab == 1, Date().timeIntervalSince(userScrolledAt) > 4, let id { withAnimation { proxy.scrollTo(id, anchor: .center) } } }
            // Search: the current hit scrolls into view as the query changes and on Next/Prev (Android TranscriptSearchBar), not only once the player plays.
            .onChange(of: match) { _, _ in if hits.indices.contains(match) { withAnimation { proxy.scrollTo(hits[match].id, anchor: .center) } } }
            .onChange(of: jumpTo) { _, id in if let id { withAnimation { proxy.scrollTo(id, anchor: .center) } } }
            // Not while the results list is open: the bar and the list sit in this scroll view, and jumping to the
            // first hit as the query was typed (or the ≈ passages landed) scrolled them off screen (14 Pro Max).
            .onChange(of: hits.first?.id) { _, _ in if !(resultsOpen && !meetingResults.isEmpty), hits.indices.contains(match) { withAnimation { proxy.scrollTo(hits[match].id, anchor: .center) } } }
        }
        .environment(\.speakerRefs, { [s] t in SpeakerRefs.resolve(t, label: { s.name($0) }, known: Set(s.lines.map(\.speaker)), wrap: SpeakerRefs.unbreakable) })
        .navigationTitle(refs(s.title, screen: false)).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !undoSteps.isEmpty || !redoSteps.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Button { undoEdit() } label: { Image(systemName: "arrow.uturn.backward") }.disabled(!canUndo || busy).accessibilityLabel(L("undo"))
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { redoEdit() } label: { Image(systemName: "arrow.uturn.forward") }.disabled(!canRedo || busy).accessibilityLabel(L("redo"))
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button { tab = 1; searching = true; searchFocus = true; Self.preloadEmbedder() } label: { Image(systemName: "magnifyingglass") }.accessibilityLabel(L("search_transcript"))
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button(L("rename")) { draft = s.title; renamingTitle = true }
                    if askMeetingReady { Button { askMeeting = "" } label: { Label(L("ask_meeting_title"), systemImage: "sparkles") } }
                    if !s.lines.isEmpty { Button { mailOpen = true } label: { Label(L("mail_menu_entry"), systemImage: "envelope") } }
                    Button(L("export_menu_entry")) { showExport = true }
                    if !s.lines.isEmpty { ShareLink(item: Export.text(s, .txt), subject: Text(s.title)) { Text(L("export_share_transcript")) } }
                    if s.audio != nil {
                        Button(L("re_transcribe")) { rerun(s, true) }
                        if !s.noSpeech { Button(L("re_summarize")) { rerun(s, false) } }   // nothing to summarize (Android canReSummarize)
                    }
                } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(L("cd_more_options")) }
            }
        }
        .sheet(isPresented: $showExport) {
            ExportSheet(s: s) { f in showExport = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { if let u = Export.file(s, f) { Haptics.success(); exportFile = u } else { toast = L("export_share_failed") } } } onSession: { showExport = false; exporting = true; Task { let u = await SessionFile.export(s); exporting = false; try? await Task.sleep(nanoseconds: 400_000_000); if let u { Haptics.success(); exportFile = u } else { toast = L("session_share_failed") } } }
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .sheet(isPresented: Binding(get: { exportFile != nil }, set: { if !$0 { exportFile = nil } })) { if let exportFile { ShareSheet(url: exportFile) } }
        // In-meeting Ask: this meeting's excerpts only; a citation moves the transcript and the player there.
        .sheet(isPresented: Binding(get: { askMeeting != nil }, set: { if !$0 { askMeeting = nil } })) {
            AskSheet(m: Model.shared, initial: askMeeting ?? "", open: { _, t in
                askMeeting = nil; tab = 1
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    let i = MeetingSearch.lineAt(starts: s.lines.map(\.start), t)
                    if s.lines.indices.contains(i) { let l = s.lines[i]; jumpTo = nil; DispatchQueue.main.async { jumpTo = l.id } }
                    if player.available { player.seek(s.lines.indices.contains(i) ? s.lines[i].start : t) }
                }
            }, meeting: s)
        }
        .sheet(isPresented: $mailOpen) {
            EmailParticipantsSheet(session: s, onSend: { r in
                mailOpen = false
                let cur = s
                Task { try? await Task.sleep(nanoseconds: 600_000_000); await MailOpener.send(cur, r) { toast = $0 } }
            }, onShareChat: { r in
                mailOpen = false
                let cur = s
                Task { try? await Task.sleep(nanoseconds: 600_000_000); await MailOpener.shareChat(cur, r) }
            }, onNameSpeaker: { k in
                mailOpen = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { draft = ""; renamingSpeaker = k }
            }, onDismiss: { mailOpen = false })
        }
        .alert(L("rename"), isPresented: $renamingTitle) {
            TextField(L("title"), text: $draft)
                .submitLabel(.done).onSubmit { let t = draft.trimmingCharacters(in: .whitespaces); if !t.isEmpty { s.title = t; save(s) } }
            Button(L("done")) { let t = draft.trimmingCharacters(in: .whitespaces); if !t.isEmpty { s.title = t; save(s) } }
            Button(L("cancel"), role: .cancel) {}
        }
        // Naming a speaker: the people picker (docs/design/speaker-tags.md §2.1), not a free-text alert any more.
        .sheet(isPresented: Binding(get: { renamingSpeaker != nil }, set: { if !$0 { renamingSpeaker = nil } })) {
            if let k = renamingSpeaker {
                VoiceSuggested(session: s, speaker: k) { suggestions, note in
                    SpeakerNameFlow(speaker: pickerSpeaker(k), speakers: pickerSpeakers, sessions: Model.shared.library.sessions,
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
        .modifier(VoiceOutlierPrompt())
        // A sheet, not an alert: an alert's field shows one line of a paragraph-long utterance.
        .sheet(isPresented: Binding(get: { editingLine != nil }, set: { if !$0 { editingLine = nil } })) {
            NavigationStack {
                TextEditor(text: $draft).padding(.horizontal, 12)
                    .scrollDismissesKeyboard(.interactively)   // a long paragraph: scroll to read the tail under the keyboard
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { editingLine = nil } }
                        ToolbarItem(placement: .confirmationAction) { Button(L("done")) {
                            let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                            if let id = editingLine, !t.isEmpty, let i = s.lines.firstIndex(where: { $0.id == id }), s.lines[i].text != t {
                                let before = (s.lines, s.speakerNames, s.speakerPeople); s.lines[i].text = t; record(before); edited() }
                            editingLine = nil }.disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }   // like Android: an emptied line is not a save
                    }.navigationTitle(L("cd_edit")).navigationBarTitleDisplayMode(.inline)
            }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .confirmationDialog(merging.map { L("speaker_merge_confirm", s.name($0.0), s.name($0.1)) } ?? "",
                            isPresented: Binding(get: { merging != nil }, set: { if !$0 { merging = nil } }), titleVisibility: .visible) {
            Button(L("speaker_merge")) { if let (f, t) = merging { merge(f, t) }; merging = nil }
            Button(L("cancel"), role: .cancel) { merging = nil }
        }
        // VoiceOver route for per-line re-attribution: one row per other speaker, same reassign as the menu.
        .sheet(isPresented: Binding(get: { moveLine != nil }, set: { if !$0 { moveLine = nil } })) {
            if let id = moveLine, let i = s.lines.firstIndex(where: { $0.id == id }) {
                NavigationStack {
                    List {
                        ForEach(Array(Set(s.lines.map(\.speaker))).sorted().filter { $0 != s.lines[i].speaker }, id: \.self) { k in
                            Button { moveLine = nil; reassign(id, k) } label: {
                                HStack(spacing: 8) { Circle().fill(tint(k)).frame(width: 10, height: 10); Text(s.name(k)) }
                                    .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    }
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { moveLine = nil } } }
                    .navigationTitle(L("speaker_move_line")).navigationBarTitleDisplayMode(.inline)
                }
                .presentationDetents([.medium]).presentationDragIndicator(.visible)
            }
        }
        .sheet(isPresented: Binding(get: { seamAt != nil }, set: { if !$0 { player.endPreview(); seamAt = nil } })) {
            if let k = seamAt, k >= 1, k < s.lines.count {
                let a = s.lines[k - 1], b = s.lines[k], sm = BoundaryEdits.seam(a, b)
                BoundaryEditorSheet(tokens: sm.tokens, starts: sm.starts, end: sm.end, initialSplit: sm.split,
                                    colorA: tint(a.speaker), labelA: s.name(a.speaker), colorB: tint(b.speaker), labelB: s.name(b.speaker),
                                    estimated: BoundaryEdits.estimated(a) || BoundaryEdits.estimated(b),
                                    canPlay: player.available, demo: Self.devDemo,
                                    onPreview: { player.preview(from: $0, to: $1) },
                                    onApply: { split, _ in player.endPreview(); seamAt = nil; moveBoundary(k, split) },
                                    onCancel: { player.endPreview(); seamAt = nil })
                    .presentationDetents([.medium, .large]).presentationDragIndicator(.visible).presentationBackground(Color(.systemGroupedBackground))
            }
        }
        .sheet(isPresented: Binding(get: { splitAt != nil }, set: { if !$0 { player.endPreview(); splitAt = nil } })) {
            if let li = splitAt, s.lines.indices.contains(li) {
                let u = s.lines[li], toks = BoundaryEdits.tokenize(u.text), order = BoundaryEdits.splitCandidates(s.lines, index: li)
                let known = Set(s.lines.map(\.speaker))
                BoundaryEditorSheet(tokens: toks, starts: BoundaryEdits.tokenStarts(u, toks), end: u.end, initialSplit: toks.count / 2,
                                    colorA: tint(u.speaker), labelA: s.name(u.speaker), colorB: tint(order[0]), labelB: s.name(order[0]),
                                    choices: order.map { SpeakerChoice(id: $0, label: s.name($0), color: tint($0), isNew: !known.contains($0)) },
                                    estimated: BoundaryEdits.estimated(u),
                                    canPlay: player.available, demo: Self.devDemo,
                                    onPreview: { player.preview(from: $0, to: $1) },
                                    onApply: { at, spk in player.endPreview(); splitAt = nil; splitLine(li, at, spk ?? order[0]) },
                                    onCancel: { player.endPreview(); splitAt = nil })
                    .presentationDetents([.medium, .large]).presentationDragIndicator(.visible).presentationBackground(Color(.systemGroupedBackground))
            }
        }
        .task {   // DEV: VOX_BOUNDARY=N opens the editor on the N-th speaker change, VOX_SPLIT=N the split editor on line N
            guard Dev.on, Dev.env["VOX_BOUNDARY"] != nil || Dev.env["VOX_SPLIT"] != nil else { return }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            tab = 1
            if let n = Dev.env["VOX_SPLIT"].flatMap(Int.init) { splitAt = min(max(0, n), s.lines.count - 1) }
            else if let n = Dev.env["VOX_BOUNDARY"].flatMap(Int.init) {
                let seams = s.lines.indices.dropFirst().filter { s.lines[$0].speaker != s.lines[$0 - 1].speaker }
                if seams.indices.contains(n) { seamAt = seams[n] }
            }
        }
        .alert(L("transcript_changed_resummarize"), isPresented: Binding(get: { transcriptDirty && !busy }, set: { transcriptDirty = $0 })) {
            if s.audio != nil && !s.noSpeech { Button(L("re_summarize")) { rerun(s, false) } }
            Button(L("cancel"), role: .cancel) {}
        }
        .overlay(alignment: .bottom) {
            if let toast { Text(toast).font(.subheadline).padding(.horizontal, 16).padding(.vertical, 10).background(.thinMaterial, in: Capsule()).padding(.bottom, 110)
                // A toast is visual only: say it out loud too (VoiceOver gets nothing otherwise).
                .transition(.opacity).task { UIAccessibility.post(notification: .announcement, argument: toast); try? await Task.sleep(nanoseconds: 1_800_000_000); withAnimation { self.toast = nil } } }
        }
        .sheet(isPresented: $editingSummary) {
            NavigationStack {
                TextEditor(text: $draft).padding(.horizontal, 12)
                    .scrollDismissesKeyboard(.interactively)   // a long paragraph: scroll to read the tail under the keyboard
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { editingSummary = false } }
                        ToolbarItem(placement: .confirmationAction) { Button(L("done")) {
                            let t = draft.trimmingCharacters(in: .whitespacesAndNewlines); editingSummary = false
                            if t != s.summary { s.summary = t; save(s) } } }
                    }.navigationTitle(L("tab_summary")).navigationBarTitleDisplayMode(.inline)
            }
        }
        .sheet(isPresented: $editingActions) {
            NavigationStack {
                TextEditor(text: $draft).padding(.horizontal, 12)
                    .scrollDismissesKeyboard(.interactively)   // a long paragraph: scroll to read the tail under the keyboard
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { editingActions = false } }
                        ToolbarItem(placement: .confirmationAction) { Button(L("done")) {
                            let t = draft.trimmingCharacters(in: .whitespacesAndNewlines); editingActions = false
                            if t != s.actionItems { s.actionsEdited = t.isEmpty ? "-" : t; save(s) } } }
                    }.navigationTitle(L("card_action_items")).navigationBarTitleDisplayMode(.inline)
            }
        }
        .alert(L("summary_settings_changed"), isPresented: $summaryStale) {
            Button(L("re_summarize")) { rerun(s, false) }
            Button(L("cancel"), role: .cancel) {}
        }
        .task { if Dev.env["VOX_RESUM"] != nil { rerun(s, false) } }
        .task {   // DEV: VOX_MERGE=confirm asks to merge the first speaker into the second; =apply merges, then undoes after 5 s
            guard let mode = Dev.env["VOX_MERGE"] else { return }
            var ids: [Int] = []; for l in s.lines where !ids.contains(l.speaker) { ids.append(l.speaker) }
            guard ids.count >= 2 else { return }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if mode == "apply" { merge(ids[0], ids[1]); try? await Task.sleep(nanoseconds: 5_000_000_000); if let u = speakerUndo { undoSpeakerEdit(u) } }
            else { merging = (ids[0], ids[1]) }
        }
        .task { if let r = s.reader, r != Prefs.readerId, !s.summary.isEmpty, s.audio != nil { summaryStale = true } }
        .onReceive(NotificationCenter.default.publisher(for: .voxSessionSaved)) { n in
            guard let new = n.object as? Session, new.id == s.id else { return }
            s = new
            if let old = n.userInfo?["undo"] as? Session, !old.summary.isEmpty { withAnimation { undoable = old } }
            if Dev.env["VOX_RESUM"] != nil { try? "undo=\(n.userInfo?["undo"] != nil) names=\(new.speakerNames ?? [:]) reader=\(new.reader ?? "-") summary=\(new.summary.prefix(80))".write(to: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("resum.txt"), atomically: true, encoding: .utf8) }
        }
        .overlay {
            if exporting {
                VStack(spacing: 8) { ProgressView(); Text(L("exporting")).font(.headline); Text(L("exporting_hint")).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center) }
                    .padding(20).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16)).padding(40)
            }
        }
        .overlay(alignment: .bottom) {
            if let old = undoable {
                HStack {
                    Text(L("resummary_done")).font(.subheadline); Spacer()
                    Button(L("undo")) { s.summary = old.summary; s.title = old.title; s.notes = old.notes; s.actionsEdited = old.actionsEdited; s.reader = old.reader; save(s); withAnimation { undoable = nil } }.bold()
                }.padding(14).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 16).padding(.bottom, 110)
                    .task { try? await Task.sleep(nanoseconds: 8_000_000_000); withAnimation { undoable = nil } }
            }
        }
        .overlay(alignment: .bottom) {
            if let u = speakerUndo {
                HStack {
                    Text(u.message).font(.subheadline); Spacer()
                    Button(L("undo")) { undoSpeakerEdit(u) }.bold().disabled(busy)
                }.padding(14).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 16).padding(.bottom, 110)
                    .task(id: u.id) {
                        try? await Task.sleep(nanoseconds: 8_000_000_000)
                        guard speakerUndo == u else { return }
                        withAnimation { speakerUndo = nil }
                        if (!s.summary.isEmpty || s.actionItems != nil) && !u.nameOnly { transcriptDirty = true }
                    }
            }
        }
        .onChange(of: busy) { _, b in if b { transcriptDirty = false } }   // the run re-reads the meeting: the question is moot
        // The index this search was waiting for is written: run the ≈ pass again (Android: indexingIds re-runs it).
        .onReceive(LibraryIndexer.shared.$indexing) { ids in if semIndexing, !ids.contains(s.id), hasIndex { semSearch() } }
        .onDisappear { player.stop() }
    }

    /// Android LineMenu: edit the text, move this line to another speaker, or merge this speaker into another.
    @ViewBuilder private func lineMenu(_ l: Utterance) -> some View {
        Button { editingLine = l.id; draft = l.text } label: { Label(L("cd_edit"), systemImage: "pencil") }
        if BoundaryEdits.tokenize(l.text).count > 1, let i = s.lines.firstIndex(where: { $0.id == l.id }) {
            Button { splitAt = i } label: { Label(L("line_split"), systemImage: "arrow.up.and.down") }
        }
        let others = Array(Set(s.lines.map(\.speaker))).sorted().filter { $0 != l.speaker }
        if !others.isEmpty {
            Section(L("speaker_move_line")) { ForEach(others, id: \.self) { k in Button(s.name(k)) { reassign(l.id, k) } } }
            Section(L("speaker_merge_into")) { ForEach(others, id: \.self) { k in Button(s.name(k)) { merging = (l.speaker, k) } } }
        }
    }
    /// Android SpeakerEdits: plain relabels, ids kept (labels and colours stay put); a speaker left without lines loses its name.
    private func reassign(_ id: UUID, _ to: Int) {
        guard let i = s.lines.firstIndex(where: { $0.id == id }) else { return }
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        let old = s.lines[i].speaker; s.lines[i].speaker = to
        if !s.lines.contains(where: { $0.speaker == old }) { dropName(old) }
        speakerEdited(L("speaker_line_moved"), before)
    }
    private func merge(_ from: Int, _ into: Int) {
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        for i in s.lines.indices where s.lines[i].speaker == from { s.lines[i].speaker = into }
        dropName(from); speakerEdited(L("speaker_merged"), before)
    }
    /// Saved at once with an Undo banner; the "re-summarize?" question waits until the banner is gone.
    private func speakerEdited(_ message: String, _ before: ([Utterance], [String: String]?, [String: String]?), nameOnly: Bool = false) {
        record(before)
        save(s)
        withAnimation { speakerUndo = SpeakerUndo(message: message, lines: before.0, names: before.1, people: before.2, after: s.lines.map(\.speaker), earlierEdit: speakerUndo != nil && !(speakerUndo?.nameOnly ?? false), nameOnly: nameOnly) }
    }
    // MARK: people (docs/design/speaker-tags.md): the picker's choices, undoable like a merge
    private var pickerSpeakers: [PickerSpeaker] {
        let doc = PeopleStore.shared.doc
        return Array(Set(s.lines.map(\.speaker))).sorted().map { k in PickerSpeaker(id: k, label: s.name(k), person: s.speakerPeople?[String(k)].flatMap { doc.byId($0)?.id }) }
    }
    private func pickerSpeaker(_ k: Int) -> PickerSpeaker { pickerSpeakers.first { $0.id == k } ?? PickerSpeaker(id: k, label: s.name(k), person: nil) }
    /// `k` is `p` of the directory.
    private func link(_ k: Int, _ p: Person) {
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        var l = PeopleStore.links(s); l[k] = SpeakerLink(name: p.name, person: p.id); PeopleStore.apply(l, to: &s)
        speakerEdited(L("people_linked"), before, nameOnly: true)
        PeopleStore.shared.touch(p.id)
        VoiceLinks.impl?.confirmed(session: s.id, speaker: k, person: p.id)
    }
    /// Back to "Speaker n": the person stays in the directory.
    private func clearName(_ k: Int) {
        let was = s.speakerPeople?[String(k)]
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        var l = PeopleStore.links(s); l[k] = nil; PeopleStore.apply(l, to: &s)
        speakerEdited(L("people_linked"), before, nameOnly: true)
        if let was { VoiceLinks.impl?.unlinked(session: s.id, speaker: k, person: was) }
    }
    /// A short clip of the speaker: its longest clean line (CleanSpeech), 5 s.
    private func playSample(_ k: Int) {
        guard let t = CleanSpeech.sampleStart(s.lines, speaker: k) else { return }
        player.seek(t); if !player.playing { player.toggle() }
        Task { try? await Task.sleep(nanoseconds: 5_000_000_000); if player.playing, player.time >= t, player.time < t + 6 { player.toggle() } }
    }
    private func undoSpeakerEdit(_ u: SpeakerUndo) {
        withAnimation { speakerUndo = nil }
        guard s.lines.map(\.speaker) == u.after else { return }   // the transcript changed since: nothing safe to restore
        if canUndo, undoSteps.last?.before == u.lines { undoEdit() } else { s.lines = u.lines; s.speakerNames = u.names; s.speakerPeople = u.people; save(s) }
        if u.earlierEdit && (!s.summary.isEmpty || s.actionItems != nil) { transcriptDirty = true }
    }
    private func dropName(_ k: Int) {
        s.speakerNames?[String(k)] = nil; if s.speakerNames?.isEmpty == true { s.speakerNames = nil }
        s.speakerPeople?[String(k)] = nil; if s.speakerPeople?.isEmpty == true { s.speakerPeople = nil }
    }    /// Push the edit that just turned `before` into the current transcript; a new edit drops the redo branch.
    private func record(_ before: ([Utterance], [String: String]?, [String: String]?)) {
        // An older step that no longer matches (a re-summarize replaced the session) is dropped.
        if let last = undoSteps.last, last.after != before.0 || last.afterNames != before.1 || last.afterPeople != before.2 { undoSteps = [] }
        undoSteps = Array((undoSteps + [Step(before: before.0, beforeNames: before.1, beforePeople: before.2, after: s.lines, afterNames: s.speakerNames, afterPeople: s.speakerPeople)]).suffix(50))
        redoSteps = []
    }
    private var canUndo: Bool { undoSteps.last.map { $0.after == s.lines && $0.afterNames == s.speakerNames && $0.afterPeople == s.speakerPeople } ?? false }
    private var canRedo: Bool { redoSteps.last.map { $0.before == s.lines && $0.beforeNames == s.speakerNames && $0.beforePeople == s.speakerPeople } ?? false }
    private func undoEdit() {
        guard canUndo, !busy, let st = undoSteps.popLast() else { return }
        s.lines = st.before; s.speakerNames = st.beforeNames; s.speakerPeople = st.beforePeople; redoSteps.append(st); save(s)
        withAnimation { speakerUndo = nil }
    }
    private func redoEdit() {
        guard canRedo, !busy, let st = redoSteps.popLast() else { return }
        s.lines = st.after; s.speakerNames = st.afterNames; s.speakerPeople = st.afterPeople; undoSteps.append(st); save(s)
        withAnimation { speakerUndo = nil }
    }
    /// Boundary moves and splits: saved at once, undoable from the toolbar; no "re-summarize?" question (like a relabel,
    /// they change who said a few words, not what was said).
    private func moveBoundary(_ seam: Int, _ split: Int) {
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        let out = BoundaryEdits.move(s.lines, seam: seam, split: split)
        guard out != s.lines else { return }
        s.lines = out; record(before); save(s); toast = L("boundary_moved")
    }
    private func splitLine(_ index: Int, _ at: Int, _ speaker: Int) {
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        let out = BoundaryEdits.split(s.lines, index: index, at: at, speaker: speaker)
        guard out != s.lines else { return }
        s.lines = out; record(before); save(s); toast = L("line_split_done")
    }
    /// DEV (README animation): VOX_BOUNDARY_STEPS="-1,-1" moves the open editor's marker by these steps, then applies.
    static var devDemo: [Int]? { Dev.on ? Dev.env["VOX_BOUNDARY_STEPS"].map { $0.split(separator: ",").compactMap { Int($0) } } : nil }
    private func edited() { save(s); if !s.summary.isEmpty || s.actionItems != nil { transcriptDirty = true } }   // Android: summary or action items

    private func stepMatch(_ d: Int) {
        guard !hits.isEmpty else { return }
        match = (match + d + hits.count) % hits.count
        if player.available { player.seek(hits[match].start) }
    }
    static func marked(_ text: String, _ q: String) -> AttributedString {
        var a = AttributedString(text)
        guard !q.isEmpty else { return a }
        var from = a.startIndex
        while let r = a[from...].range(of: q, options: .caseInsensitive) { a[r][Attr.Background.self] = .yellow.opacity(0.5); from = r.upperBound }
        return a
    }
    private func highlight(_ id: UUID) -> Color? { id == current ? Color.accentColor.opacity(0.18) : nil }

}

/// A run (re-summarize, re-transcribe) on the open meeting: its status line and progress, as the live view's header
/// shows them (LiveJobView); until now nothing on this screen said anything was happening until the summary changed.
/// Its own view, so the model's changes redraw this row only.
private struct RunStatusRow: View {
    @ObservedObject private var m = Model.shared
    let id: UUID
    var body: some View {
        let active = m.runner.activeJob == id
        let job = m.runner.waiting.first(where: { $0.id == id })
        if active || job != nil {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    // Decoration for the status text next to it (an SF Symbol would read its own name).
                    if active { ProgressView().controlSize(.small).accessibilityHidden(true) } else { Image(systemName: "clock").foregroundStyle(.orange).accessibilityHidden(true) }
                    Text(job.map(m.jobStatus) ?? m.screen.status).font(.subheadline).lineLimit(3)
                    Spacer(minLength: 0)
                    if active { HwStatusLine() }
                }
                if active { ProgressView(value: min(max(m.runner.jobProgress, 0), 1)) }
            }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
            .accessibilityIdentifier("session-run-status")
        }
    }
}

/// Calls `changed` while the user scrolls a scroll view by hand (a programmatic scrollTo does not count). iOS 18 reads
/// the scroll phase; iOS 17 has no phase, so a drag recognized alongside the scroll stands in (on iOS 18 such a gesture
/// can hold up the scroll itself, hence not used there).
private struct UserScrollWatch: ViewModifier {
    let changed: () -> Void
    @ViewBuilder func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { old, new in
                func byHand(_ p: ScrollPhase) -> Bool { p == .interacting || p == .decelerating }
                if byHand(old) || byHand(new) { changed() }   // on the way out too: the hold-off counts from the end of a fling
            }
        } else {
            content.simultaneousGesture(DragGesture(minimumDistance: 10).onChanged { _ in changed() })
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ c: UIActivityViewController, context: Context) {}
}


/// Android ExportSheet: grouped formats (document / subtitles) plus copy transcript; sharing goes through the system sheet (which also offers Save to Files).
struct ExportSheet: View {
    let s: Session
    let onPick: (Export.Format) -> Void
    var onSession: () -> Void = {}
    @State private var copied = false

    private func group(_ title: String, _ desc: String, _ fs: [Export.Format]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            Text(desc).font(.footnote).foregroundStyle(.secondary)
            HStack { ForEach(fs) { f in Button(f.rawValue.uppercased()) { onPick(f) }.buttonStyle(.bordered) } }
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(L("export_sheet_title")).font(.title2.bold())
            group(L("export_group_document"), L("export_group_document_desc"), [.pdf, .md, .txt])
            group(L("export_group_subtitles"), L("export_group_subtitles_desc"), [.srt, .vtt, .lrc])
            if s.audio != nil {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("export_group_session")).font(.headline)
                    Text(L("export_group_session_desc")).font(.footnote).foregroundStyle(.secondary)
                    Button(L("export_action_save")) { onSession() }.buttonStyle(.bordered)
                }
            }
            Button { UIPasteboard.general.string = Export.text(s, .txt); copied = true; Haptics.success() } label: { Label(L("export_copy_transcript"), systemImage: copied ? "checkmark" : "doc.on.doc") }
            Spacer()
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
    }
}


/// Android AgentPanel NoteCard: timestamp pill (seeks), coloured tag chip, text, and a "verify" link on the error-prone tags.
struct NoteRow: View {
    let n: Note
    @Environment(\.speakerRefs) private var refs
    let seek: (Int) -> Void
    private var full: String {
        let t = (n.tag ?? "").uppercased()
        return ["DECISION", "ACTION", "OPEN-ISSUE", "NUMBER", "PROPOSAL"].first { $0.hasPrefix(t) && !t.isEmpty } ?? t
    }
    private var color: Color {
        switch full { case "DECISION": return .green; case "ACTION": return .blue; case "OPEN-ISSUE": return .orange
        // System hues: they adapt to dark mode (raw RGB literals did not).
        case "NUMBER": return .purple; case "PROPOSAL": return .teal; default: return .gray }
    }
    private var label: String {
        switch full { case "DECISION": return L("agent_tag_decision"); case "ACTION": return L("agent_tag_action"); case "OPEN-ISSUE": return L("agent_tag_open")
        case "NUMBER": return L("agent_tag_number"); case "PROPOSAL": return L("agent_tag_proposal"); default: return n.tag ?? "" }
    }
    var body: some View {
        let sec = ReaderProtocol.parseTs(n.ts)
        HStack(alignment: .top, spacing: 6) {
            Button { if let sec { seek(sec) } } label: {
                Text(n.ts).font(.caption2.monospaced().weight(.semibold)).foregroundStyle(.blue)
                    .padding(.horizontal, 5).padding(.vertical, 1).background(Color.blue.opacity(0.12), in: RoundedRectangle(cornerRadius: 6)).tapHeight()
            }.buttonStyle(.plain)
            if let tag = n.tag, tag != "-" {
                Text(label).font(.caption2.weight(.semibold)).foregroundStyle(color)
                    .padding(.horizontal, 8).padding(.vertical, 2).background(color.opacity(0.14), in: Capsule())
            }
            Text(refs(n.text)).font(.caption).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
            if ["DECISION", "ACTION", "NUMBER"].contains(full), let sec {
                Button { seek(sec) } label: {
                    Text(L("agent_verify")).font(.caption2.weight(.semibold)).foregroundStyle(.orange)
                        .padding(.horizontal, 6).padding(.vertical, 1).overlay(Capsule().stroke(Color.orange.opacity(0.5))).tapHeight()
                }.buttonStyle(.plain)
            }
        }.padding(.vertical, 3)
    }
}


/// Android SpeakerStatsPanel: speaker count beside one stacked talk-time bar in the speakers' colours; tap for the per-speaker legend.
/// Takes the lines and names, not the Session: Session's == compares ids only, so SwiftUI kept the old bar after a merge.
struct SpeakerStats: View {
    let lines: [Utterance]
    let names: [String: String]?
    let palette: [Color]
    @State private var legend = false
    private var shares: [(spk: Int, pct: Double)] {
        var d = [Int: Double]()
        for l in lines { d[max(0, l.speaker), default: 0] += max(0, l.end - l.start) }
        let total = d.values.reduce(0, +)
        return total > 0 ? d.map { ($0.key, $0.value / total * 100) }.sorted { $0.1 > $1.1 } : []
    }
    var body: some View {
        let sh = shares
        if !sh.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    Text(L("speaker_count", sh.count)).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    GeometryReader { g in
                        HStack(spacing: 0) { ForEach(sh, id: \.spk) { x in palette[x.spk % palette.count].frame(width: g.size.width * x.pct / 100) } }
                            .clipShape(Capsule())
                    }.frame(height: 8)
                    // SF Symbols ship their own VoiceOver names; inside this tap target the chevron is
                    // decoration and only added "Chevron Up" noise after the speaker count.
                    Image(systemName: legend ? "chevron.up" : "chevron.down").font(.caption).foregroundStyle(.secondary).accessibilityHidden(true)
                }
                if legend {
                    HStack(spacing: 14) {
                        ForEach(sh, id: \.spk) { x in
                            HStack(spacing: 6) {
                                RoundedRectangle(cornerRadius: 2).fill(palette[x.spk % palette.count]).frame(width: 8, height: 8)
                                Text("\(Session.name(x.spk, names)) · \(Int(x.pct.rounded()))%").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 4).contentShape(Rectangle()).onTapGesture { withAnimation { legend.toggle() } }
            // The tap is invisible to VoiceOver: say it is a button, name what the tap does (the chevron's
            // job for sighted users), and offer the same action.
            .accessibilityValue(L(legend ? "show_less" : "show_more"))
            .accessibilityAddTraits(.isButton).accessibilityAction { withAnimation { legend.toggle() } }
        }
    }
}

/// Android CollapsibleMarkdown: folded past `lines` behind Show more / Show less; a new text starts collapsed.
struct Collapsible<C: View>: View {
    let lines: Int
    @ViewBuilder let content: () -> C
    @State private var expanded = false
    @State private var full: CGFloat = 0
    @State private var folded: CGFloat = 0
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            content().lineLimit(expanded ? nil : lines)
                .background(GeometryReader { g in Color.clear.onAppear { folded = g.size.height }.onChange(of: g.size.height) { _, h in if !expanded { folded = h } } })
                .background(content().fixedSize(horizontal: false, vertical: true).hidden()
                    .background(GeometryReader { g in Color.clear.onAppear { full = g.size.height }.onChange(of: g.size.height) { _, h in full = h } }))
            if expanded || full > folded + 1 {
                Button(L(expanded ? "show_less" : "show_more")) { withAnimation { expanded.toggle() } }.font(.caption.weight(.medium)).foregroundStyle(.secondary)
            }
        }
    }
}

/// The player bar under a meeting (seek slider, ±5 s, play, volume, length): the saved meeting and the live view of a
/// job being processed share it.
struct PlayerBar: View {
    @ObservedObject var player: Player
    var body: some View {
        VStack(spacing: 6) {
            Slider(value: Binding(get: { player.time }, set: { player.seek($0) }), in: 0...max(1, player.duration)) { editing in if !editing, !player.playing { player.toggle() } }
            HStack {
                // minWidth, not width: a fixed 96 pt clipped "7:40" to "7:4…" at large text sizes.
                Text(Export.mmss(player.time)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    .lineLimit(1).fixedSize().frame(minWidth: 96, alignment: .leading)
                Spacer()
                Button { player.seek(player.time - 5) } label: { Image(systemName: "gobackward.5").font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }.accessibilityLabel(L("cd_back5"))
                Button { player.toggle() } label: {
                    Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title2).foregroundStyle(.white)
                        .frame(width: 56, height: 56).background(Color.accentColor, in: Circle())
                }.accessibilityLabel(L(player.playing ? "pause" : "play"))
                Button { player.seek(player.time + 5) } label: { Image(systemName: "goforward.5").font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }.accessibilityLabel(L("cd_forward5"))
                Spacer()
                HStack(spacing: 12) {
                Menu {
                    Button { player.muted.toggle() } label: { Label(L(player.muted ? "cd_unmute" : "cd_mute"), systemImage: player.muted ? "speaker.wave.2" : "speaker.slash") }
                    ForEach([0.25, 0.5, 0.75, 1.0], id: \.self) { v in Button("\(Int(v * 100)) %") { player.muted = false; player.volume = Float(v) } }
                } label: { Image(systemName: player.muted || player.volume == 0 ? "speaker.slash" : "speaker.wave.2").font(.body).foregroundStyle(.secondary).tapTarget() }
                    .accessibilityLabel(L("cd_volume")).accessibilityValue(player.muted || player.volume == 0 ? L("cd_muted") : "\(Int(player.volume * 100)) %")
                Text(Export.mmss(player.duration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    .lineLimit(1).fixedSize()
                }.frame(minWidth: 96, alignment: .trailing)
            }
        }.padding(.horizontal, 20).padding(.vertical, 8).background(.bar)
    }
}
