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
    /// An error or a mail-send result: shown as a system alert (copies and edits confirm with a haptic instead).
    @State private var alertText: String?
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
    @State private var mailCopied = false
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
    private var resultsList: some View { sessionResultsList }
    private func resultsHeader(_ t: String, _ n: Int) -> some View {
        sessionResultsHeader(t, n)
    }
    private func resultRow(_ r: MeetingSearch.Result) -> some View {
        sessionResultRow(r)
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
        SessionCard(c)
    }
    private var playerBar: some View { PlayerBar(player: player) }
    private var summaryTab: some View {
        SessionSummaryTab(
            session: s,
            busy: busy,
            showActions: showActions,
            showProcess: showProcess,
            hasMailRecipients: peopleStore.peopleOf(s).values.contains(where: { !$0.emails.isEmpty }),
            palette: Self.palette,
            names: names,
            refs: { refs($0) },
            playerAvailable: player.available,
            onSeekPlay: { player.seekPlay($0) },
            onCopySummary: { UIPasteboard.general.string = refs(s.summary, screen: false); Haptics.success(); announce(L("summary_copied")) },
            onEditSummary: { draft = s.summary; editingSummary = true },
            onCopyActions: { if let a = s.actionItems { UIPasteboard.general.string = refs(a, screen: false); Haptics.success(); announce(L("action_items_copied")) } },
            onEditActions: { if let a = s.actionItems { draft = a; editingActions = true } },
            onToggleProcess: { withAnimation { showProcess.toggle() } },
            onOpenMail: { mailOpen = true },
            mailCopied: mailCopied,
            notesSection: { AnyView(notesSection($0, $1)) }
        )
    }
    @ViewBuilder private func notesSection(_ header: String, _ items: [String]) -> some View {
        SessionNotesSection(
            header: header,
            items: items,
            names: names,
            refs: { refs($0) },
            playerAvailable: player.available,
            onSeekPlay: { player.seekPlay($0) }
        )
    }
    private var transcriptTab: some View {
        LazyVStack(alignment: .leading, spacing: 8) {
            if searching {
                SessionSearchBar(
                    query: $query,
                    match: $match,
                    resultsOpen: $resultsOpen,
                    semantic: $semantic,
                    searchFocus: $searchFocus,
                    semIndexing: semIndexing,
                    hasResults: !meetingResults.isEmpty,
                    askMeetingReady: askMeetingReady,
                    onQueryChange: { match = 0; resultsOpen = true; semSearch() },
                    onToggleSemantic: { semantic.toggle(); semSearch() },
                    onStep: { stepMatch($0) },
                    onToggleResults: { resultsOpen.toggle() },
                    onAskMeeting: { askMeeting = query.trimmingCharacters(in: .whitespaces) },
                    onClose: { searching = false; query = "" },
                    matchText: hits.isEmpty ? L(semantic && semIndexing ? "studio_indexing" : "search_no_matches") : L("search_match_count", match + 1, hits.count),
                    resultsView: AnyView(resultsList)
                )
            }
            SessionTranscriptLines(
                lines: shown,
                noSpeech: s.noSpeech,
                busy: busy,
                currentId: current,
                query: query,
                matchId: hits.indices.contains(match) ? hits[match].id : nil,
                tint: { tint($0) },
                name: { s.name($0) },
                marked: { Self.marked($0, $1) },
                onSeekPlay: { if player.available { player.seekPlay($0) } },
                onRenameSpeaker: { renamingSpeaker = $0 },
                onEditLine: { editingLine = $0; draft = $1 },
                onMoveLine: { moveLine = $0 },
                onSeam: { seamAt = $0 },
                lineMenu: { AnyView(lineMenu($0)) }
            )
        }.padding(.horizontal, Vox.gutter)
    }

    /// onAppear work, out of the body chain so the solver sees one call, not three closures.
    private func appear(_ proxy: ScrollViewProxy) {
        if searching, semantic, !query.isEmpty { semSearch() }
        if let t = Dev.env["VOX_PLAYAT"].flatMap(Double.init) { DispatchQueue.main.asyncAfter(deadline: .now() + 1) { player.seek(t); if !player.playing { player.toggle() } } }
        if Dev.env["VOX_SCROLL"] != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { proxy.scrollTo("stats", anchor: .top) } }
        if let t = openAt { openAtSeek(t, proxy) }
    }
    /// Open the transcript search (magnifier): transcript tab, field focused, embedder preloading.
    private func openSearch() { tab = 1; searching = true; searchFocus = true; Self.preloadEmbedder() }
    /// An Ask citation tapped: back to the transcript, scroll to the cited line, park the player there.
    private func askCitation(_ t: Double) {
        askMeeting = nil; tab = 1
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            let i = MeetingSearch.lineAt(starts: s.lines.map(\.start), t)
            if s.lines.indices.contains(i) { let l = s.lines[i]; jumpTo = nil; DispatchQueue.main.async { jumpTo = l.id } }
            if player.available { player.seek(s.lines.indices.contains(i) ? s.lines[i].start : t) }
        }
    }
    /// Export-format picked: write the file, then hand it to the share sheet (or alert on failure).
    private func pickExport(_ f: Export.Format) {
        showExport = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            if let u = Export.file(s, f) { Haptics.success(); exportFile = u } else { alertText = L("export_share_failed") }
        }
    }
    /// "Save session" picked: export the .primeminutes bundle, then share it (or alert on failure).
    private func saveSessionExport() {
        showExport = false; exporting = true
        Task {
            let u = await SessionFile.export(s); exporting = false
            try? await Task.sleep(nanoseconds: 400_000_000)
            if let u { Haptics.success(); exportFile = u } else { alertText = L("session_share_failed") }
        }
    }
    /// The addresses went to the clipboard: the mail glyphs turn to a checkmark for 2 s and VoiceOver hears it
    /// (as the other copies; an alert is kept for real errors).
    private func mailCopiedFlash() {
        withAnimation { mailCopied = true }; announce(L("mail_addresses_copied"))
        Task { try? await Task.sleep(nanoseconds: 2_000_000_000); withAnimation { mailCopied = false } }
    }
    /// Mail "send" picked: close the sheet, then send after the dismiss animation.
    private func sendMail(_ r: MailRequest) {
        mailOpen = false
        let cur = s
        Task { try? await Task.sleep(nanoseconds: 600_000_000); await MailOpener.send(cur, r, copied: { mailCopiedFlash() }) { alertText = $0 } }
    }
    /// Mail "share chat" picked: close the sheet, then share after the dismiss animation.
    private func shareMailChat(_ r: MailRequest) {
        mailOpen = false
        let cur = s
        Task { try? await Task.sleep(nanoseconds: 600_000_000); await MailOpener.shareChat(cur, r) }
    }
    /// Mail "name speaker" picked: close the sheet, then open the rename sheet for k.
    private func nameMailSpeaker(_ k: Int) {
        mailOpen = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { draft = ""; renamingSpeaker = k }
    }
    /// Undo the resummary: restore the pre-run summary/title/notes/actions/reader.
    private func undoResummary() {
        guard let old = undoable else { return }
        s.summary = old.summary; s.title = old.title; s.titleUserSet = old.titleUserSet; s.notes = old.notes; s.actionsEdited = old.actionsEdited; s.reader = old.reader; save(s)
        withAnimation { undoable = nil }
    }
    /// The speaker-undo banner timed out: clear it, and ask "re-summarize?" if the edit changed what was said.
    private func expireSpeakerUndo(_ u: SpeakerUndo) {
        guard speakerUndo == u else { return }
        withAnimation { speakerUndo = nil }
        if (!s.summary.isEmpty || s.actionItems != nil) && !u.nameOnly { transcriptDirty = true }
    }
    /// Wait out the speaker-undo banner, then expire it (cancelled by a newer banner's task id).
    private func expireSpeakerUndoAfter(_ u: SpeakerUndo) async {
        try? await Task.sleep(nanoseconds: 8_000_000_000)
        expireSpeakerUndo(u)
    }
    /// VoiceOver hears what sighted users see change (a copy, a moved boundary): no toast on screen.
    private func announce(_ t: String) { UIAccessibility.post(notification: .announcement, argument: t) }
    // A run started on this meeting: the "re-summarize?" question is moot (the run re-reads the meeting).
    private func busyChanged(_ b: Bool) { if b { transcriptDirty = false } }
    // The index this search was waiting for is written: run the ≈ pass again (Android: indexingIds re-runs it).
    private func indexingLanded(_ ids: Set<UUID>) {
        if semIndexing, !ids.contains(s.id), hasIndex { semSearch() }
    }
    /// Whether the summary-edit Done button is enabled (non-blank draft).
    private var summaryEditDoneEnabled: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    /// Save the summary edit.
    private func saveSummaryEdit() {
        let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        editingSummary = false
        if t != s.summary { s.summary = t; save(s) }
    }
    /// Whether the actions-edit Done button is enabled (non-blank draft).
    private var actionsEditDoneEnabled: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    /// Save the action-items edit (empty means cleared: stored as "-").
    private func saveActionsEdit() {
        let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        editingActions = false
        if t != s.actionItems { s.actionsEdited = t.isEmpty ? "-" : t; save(s) }
    }
    // DEV: VOX_RESUM re-runs the summary on appear (README-animation harness).
    private func devResumTask() { if Dev.env["VOX_RESUM"] != nil { rerun(s, false) } }
    // DEV: VOX_BOUNDARY=N opens the editor on the N-th speaker change, VOX_SPLIT=N the split editor on line N.
    private func devBoundaryTask() async {
        guard Dev.on, Dev.env["VOX_BOUNDARY"] != nil || Dev.env["VOX_SPLIT"] != nil else { return }
        try? await Task.sleep(nanoseconds: 1_500_000_000)
        tab = 1
        if let n = Dev.env["VOX_SPLIT"].flatMap(Int.init) { splitAt = min(max(0, n), s.lines.count - 1) }
        else if let n = Dev.env["VOX_BOUNDARY"].flatMap(Int.init) { openDevSeam(n) }
    }
    /// DEV: open the N-th speaker-change seam in the boundary editor.
    private func openDevSeam(_ n: Int) {
        let seams = s.lines.indices.dropFirst().filter { s.lines[$0].speaker != s.lines[$0 - 1].speaker }
        if seams.indices.contains(n) { seamAt = seams[n] }
    }
    // DEV: VOX_MERGE=confirm asks to merge the first speaker into the second; =apply merges, then undoes after 5 s.
    private func devMergeTask() async {
        guard let mode = Dev.env["VOX_MERGE"] else { return }
        var ids: [Int] = []; for l in s.lines where !ids.contains(l.speaker) { ids.append(l.speaker) }
        guard ids.count >= 2 else { return }
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        if mode == "apply" { merge(ids[0], ids[1]); try? await Task.sleep(nanoseconds: 5_000_000_000); if let u = speakerUndo { undoSpeakerEdit(u) } }
        else { merging = (ids[0], ids[1]) }
    }
    /// The reading model changed under this summary (settings): offer a re-summarize.
    private func checkSummaryStale() {
        if let r = s.reader, r != Prefs.readerId, !s.summary.isEmpty, s.audio != nil { summaryStale = true }
    }
    /// The rename alert's Done: the user's title (a blank one restores the automatic title).
    private func commitTitle() { let before = (s.title, s.titleUserSet); s.rename(draft); if (s.title, s.titleUserSet) != before { save(s) } }
    /// The session toolbar, out of the body chain so the solver sees one call.
    @ToolbarContentBuilder private func toolbar() -> some ToolbarContent {
        SessionToolbar(
            showUndo: !undoSteps.isEmpty || !redoSteps.isEmpty,
            canUndo: canUndo, canRedo: canRedo, busy: busy,
            hasLines: !s.lines.isEmpty, hasAudio: s.audio != nil, noSpeech: s.noSpeech,
            askMeetingReady: askMeetingReady, title: s.title, displayTitle: refs(s.title, screen: false),
            shareText: Export.text(s, .txt),
            onUndo: { undoEdit() }, onRedo: { redoEdit() }, onSearch: { openSearch() },
            onRename: { draft = s.title; renamingTitle = true },
            onAsk: { askMeeting = "" }, onMail: { mailOpen = true }, mailCopied: mailCopied,
            onExport: { showExport = true },
            onRetranscribe: { rerun(s, true) }, onResummarize: { rerun(s, false) })
    }
    /// The scroll page, out of the body chain so the solver sees one call.
    private func scrollPage(_ proxy: ScrollViewProxy) -> some View {
        SessionScrollPage(proxy: proxy, tab: $tab, summary: AnyView(summaryTab), transcript: AnyView(transcriptTab),
            appear: { appear(proxy) }, scrolled: { userScrolledAt = Date() },
            follow: followScroll(proxy), sessionId: s.id, busy: busy,
            playerAvailable: player.available, playerBar: AnyView(playerBar), header: AnyView(stickyHeader))
    }
    /// The pinned tabs + speaker strip (SessionInsets' top inset).
    private var stickyHeader: some View {
        SessionStickyHeader(tab: $tab, speakers: Array(Set(s.lines.map(\.speaker))).sorted(), tint: { tint($0) }, name: { s.name($0) })
    }
    /// Whether the line-edit Done button is enabled (non-blank draft).
    private var lineEditDoneEnabled: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    /// Save the line-text edit (like Android: an emptied line is not a save).
    private func saveLineEdit() {
        let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id = editingLine, !t.isEmpty, let i = s.lines.firstIndex(where: { $0.id == id }), s.lines[i].text != t {
            let before = (s.lines, s.speakerNames, s.speakerPeople); s.lines[i].text = t; record(before); edited() }
        editingLine = nil
    }
    /// A run saved this meeting (JobRunner posts voxSessionSaved): re-read it, drop a
    /// mid-run rename sheet, surface the resummary undo, DEV-dump the resum trace.
    private func sessionSaved(_ n: NotificationCenter.Publisher.Output) {
        guard let new = n.object as? Session, new.id == s.id else { return }
        if busy { renamingSpeaker = nil }   // a run re-reads the meeting: a mid-run name is moot, drop the sheet
        s = new
        if let old = n.userInfo?["undo"] as? Session, !old.summary.isEmpty { withAnimation { undoable = old } }
        if Dev.env["VOX_RESUM"] != nil { sessionSavedDump(n, new) }
    }
    /// DEV VOX_RESUM trace: what the run saved, for the README-animation harness.
    private func sessionSavedDump(_ n: NotificationCenter.Publisher.Output, _ new: Session) {
        if Dev.env["VOX_RESUM"] != nil { try? "undo=\(n.userInfo?["undo"] != nil) names=\(new.speakerNames ?? [:]) reader=\(new.reader ?? "-") summary=\(new.summary.prefix(80))".write(to: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("resum.txt"), atomically: true, encoding: .utf8) }
    }
    /// Snapshot of the follow-scroll state for the SessionFollow modifier (one call in the chain, not four closures).
    private func followScroll(_ proxy: ScrollViewProxy) -> SessionFollowScroll {
        SessionFollowScroll(proxy: proxy, playing: player.playing, queryEmpty: query.isEmpty, tab: tab, userScrolledAt: userScrolledAt,
            current: current, match: match, matchId: hits.indices.contains(match) ? hits[match].id : nil, jumpTo: jumpTo,
            firstHitId: hits.first?.id, resultsOpen: resultsOpen, hasResults: !meetingResults.isEmpty,
            onFollow: { proxy.scrollTo($0, anchor: .center) })
    }
    /// openAt deep-link: scroll to the line at t and park the player there.
    private func openAtSeek(_ t: Double, _ proxy: ScrollViewProxy) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            let i = MeetingSearch.lineAt(starts: s.lines.map(\.start), t)
            if s.lines.indices.contains(i) { withAnimation { proxy.scrollTo(s.lines[i].id, anchor: .center) } }
            if player.available { player.seek(s.lines.indices.contains(i) ? s.lines[i].start : t) }
        }
    }

    var body: some View {
        ScrollViewReader { proxy in
            scrollPage(proxy)
        }
        .environment(\.speakerRefs, { [s] t in SpeakerRefs.resolve(t, label: { s.name($0) }, known: Set(s.lines.map(\.speaker)), wrap: SpeakerRefs.unbreakable) })
        .navigationTitle(refs(s.title, screen: false)).navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar() }
        .sheet(isPresented: $showExport) {
            ExportSheet(s: s, onPick: { pickExport($0) }, onSession: { saveSessionExport() })
                .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
        }
        .sheet(isPresented: Binding(get: { exportFile != nil }, set: { if !$0 { exportFile = nil } })) { if let exportFile { ShareSheet(url: exportFile) } }
        // In-meeting Ask: this meeting's excerpts only; a citation moves the transcript and the player there.
        .sheet(isPresented: Binding(get: { askMeeting != nil }, set: { if !$0 { askMeeting = nil } })) {
            AskSheet(m: Model.shared, initial: askMeeting ?? "", open: { _, t in askCitation(t) }, meeting: s)
        }
        .sheet(isPresented: $mailOpen) {
            SessionMailSheet(session: s, busy: busy,
                onSend: { sendMail($0) }, onShareChat: { shareMailChat($0) },
                onNameSpeaker: { nameMailSpeaker($0) }, onDismiss: { mailOpen = false })
        }
        .alert(L("rename"), isPresented: $renamingTitle) {
            TextField(L("title"), text: $draft)
                .submitLabel(.done).onSubmit { commitTitle() }
            Button(L("done")) { commitTitle() }
            Button(L("cancel"), role: .cancel) {}
        }
        // Naming a speaker: the people picker (docs/design/speaker-tags.md §2.1), not a free-text alert any more.
        .sheet(isPresented: Binding(get: { renamingSpeaker != nil && !busy }, set: { if !$0 { renamingSpeaker = nil } })) {
            if let k = renamingSpeaker {
                SessionRenameSheet(session: s, speakerKey: k, sessions: Model.shared.library.sessions,
                    canPlaySample: player.available,
                    speaker: pickerSpeaker(k), speakers: pickerSpeakers,
                    onPlaySample: { playSample(k) },
                    onLink: { renamingSpeaker = nil; link(k, $0) },
                    onNew: { n in renamingSpeaker = nil; if let p = PeopleStore.shared.newPerson(n) { link(k, p) } else { link(k, n) } },
                    onClear: { renamingSpeaker = nil; clearName(k) },
                    onMerge: { renamingSpeaker = nil; merge(k, $0) },
                    onDismiss: { renamingSpeaker = nil })
            }
        }
        .modifier(VoiceOutlierPrompt())
        .modifier(NewPersonEmailPrompt())
        // A sheet, not an alert: an alert's field shows one line of a paragraph-long utterance.
        .sheet(isPresented: Binding(get: { editingLine != nil }, set: { if !$0 { editingLine = nil } })) {
            if editingLine != nil {
                SessionLineEditSheet(draft: $draft, doneEnabled: lineEditDoneEnabled, onCancel: { editingLine = nil }, onDone: { saveLineEdit() })
            }
        }
        .confirmationDialog(merging.map { L("speaker_merge_confirm", s.name($0.0), s.name($0.1)) } ?? "",
                            isPresented: Binding(get: { merging != nil }, set: { if !$0 { merging = nil } }), titleVisibility: .visible) {
            Button(L("speaker_merge")) { if let (f, t) = merging { merge(f, t) }; merging = nil }
            Button(L("cancel"), role: .cancel) { merging = nil }
        }
        // VoiceOver route for per-line re-attribution: one row per other speaker, same reassign as the menu.
        .sheet(isPresented: Binding(get: { moveLine != nil }, set: { if !$0 { moveLine = nil } })) {
            if let id = moveLine, let i = s.lines.firstIndex(where: { $0.id == id }) {
                SessionMoveLineSheet(lineId: id, lineSpeaker: s.lines[i].speaker,
                    speakers: Array(Set(s.lines.map(\.speaker))).sorted(),
                    tint: { tint($0) }, name: { s.name($0) },
                    onPick: { moveLine = nil; reassign(id, $0) },
                    onCancel: { moveLine = nil })
            }
        }
        .sheet(isPresented: Binding(get: { seamAt != nil }, set: { if !$0 { player.endPreview(); seamAt = nil } })) {
            if let k = seamAt, k >= 1, k < s.lines.count {
                SessionSeamSheet(seam: k, lines: s.lines,
                    tint: { tint($0) }, name: { s.name($0) },
                    canPlay: player.available,
                    onPreview: { player.preview(from: $0, to: $1) },
                    onApply: { player.endPreview(); seamAt = nil; moveBoundary(k, $0) },
                    onCancel: { player.endPreview(); seamAt = nil })
            }
        }
        .sheet(isPresented: Binding(get: { splitAt != nil }, set: { if !$0 { player.endPreview(); splitAt = nil } })) {
            if let li = splitAt, s.lines.indices.contains(li) {
                SessionSplitSheet(index: li, lines: s.lines,
                    tint: { tint($0) }, name: { s.name($0) },
                    canPlay: player.available,
                    onPreview: { player.preview(from: $0, to: $1) },
                    onApply: { player.endPreview(); splitAt = nil; splitLine(li, $0, $1) },
                    onCancel: { player.endPreview(); splitAt = nil })
            }
        }
        .task { await devBoundaryTask() }
        .alert(L("transcript_changed_resummarize"), isPresented: Binding(get: { transcriptDirty && !busy }, set: { transcriptDirty = $0 })) {
            if s.audio != nil && !s.noSpeech { Button(L("re_summarize")) { rerun(s, false) } }
            Button(L("cancel"), role: .cancel) {}
        }
        .alert(alertText ?? "", isPresented: Binding(get: { alertText != nil }, set: { if !$0 { alertText = nil } })) {
            Button(L("ok"), role: .cancel) {}
        }
        .sheet(isPresented: $editingSummary) {
            SessionTextEditSheet(draft: $draft, title: L("tab_summary"), doneEnabled: summaryEditDoneEnabled,
                onCancel: { editingSummary = false }, onDone: { saveSummaryEdit() })
        }
        .sheet(isPresented: $editingActions) {
            SessionTextEditSheet(draft: $draft, title: L("card_action_items"), doneEnabled: actionsEditDoneEnabled,
                onCancel: { editingActions = false }, onDone: { saveActionsEdit() })
        }
        .alert(L("summary_settings_changed"), isPresented: $summaryStale) {
            Button(L("re_summarize")) { rerun(s, false) }
            Button(L("cancel"), role: .cancel) {}
        }
        .task { devResumTask() }
        .task { await devMergeTask() }
        .task { checkSummaryStale() }
        .onReceive(NotificationCenter.default.publisher(for: .voxSessionSaved)) { sessionSaved($0) }
        .overlay {
            if exporting { SessionExporting() }
        }
        .overlay(alignment: .bottom) {
            if undoable != nil {
                SessionResummaryUndo(session: $s, onUndo: { undoResummary() }, onExpire: { undoable = nil })
            }
        }
        .overlay(alignment: .bottom) {
            if let u = speakerUndo {
                SessionSpeakerUndoBanner(message: u.message, busy: busy, onUndo: { undoSpeakerEdit(u) },
                    onExpire: { expireSpeakerUndo(u) })
                    .task(id: u.id) { await expireSpeakerUndoAfter(u) }
            }
        }
        .onChange(of: busy) { _, b in busyChanged(b) }
        .onReceive(LibraryIndexer.shared.$indexing) { indexingLanded($0) }
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
    /// A plain name, no person (Contacts access denied: no person, e-mail or voice recognition).
    private func link(_ k: Int, _ name: String) {
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        var l = PeopleStore.links(s); l[k] = SpeakerLink(name: name); PeopleStore.apply(l, to: &s)
        speakerEdited(L("people_linked"), before, nameOnly: true)
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
        s.lines = out; record(before); save(s); announce(L("boundary_moved"))
    }
    private func splitLine(_ index: Int, _ at: Int, _ speaker: Int) {
        let before = (s.lines, s.speakerNames, s.speakerPeople)
        let out = BoundaryEdits.split(s.lines, index: index, at: at, speaker: speaker)
        guard out != s.lines else { return }
        s.lines = out; record(before); save(s); announce(L("line_split_done"))
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

    /// Speaker name for the extracted SpeakerStats legend (Session.name's static body, verbatim:
    /// Session.name resolves to the instance method from here, so the static is not callable by name).
    static func nameOf(_ spk: Int, _ names: [String: String]?) -> String { names?[String(spk)].flatMap { $0.isEmpty ? nil : $0 } ?? L("speaker_n", spk + 1) }

}

extension SessionView {
    /// The unfolded search-results list under the transcript search bar.
    var sessionResultsList: some View {
        let q = query
        return SessionResultsList(
            results: meetingResults,
            query: q,
            askMeetingReady: askMeetingReady,
            name: { [s] in s.name($0) },
            pick: { [self] in pick($0) },
            askMeeting: { [self] in askMeeting = query.trimmingCharacters(in: .whitespaces) }
        )
    }

    /// One header line over the notes / transcript result groups.
    func sessionResultsHeader(_ t: String, _ n: Int) -> some View {
        SessionResultsHeader(title: t, count: n)
    }

    /// One tappable result row (time pill, ≈ marker, speaker, marked snippet).
    func sessionResultRow(_ r: MeetingSearch.Result) -> some View {
        SessionResultRow(result: r, query: query, name: { [s] in s.name($0) }, pick: { [self] in pick($0) })
    }
}

/// The run-status bar's pinned slot above the scrollable content (safeAreaInset top):
/// the inset's own tiny view, so the long SessionView body stays out of the solver's
/// way. Renders nothing while idle; the sticky-bar patch claims this slot.
struct SessionRunStatusTop: View {
    let id: UUID
    let busy: Bool
    var body: some View {
        if busy { RunStatusRow(id: id).padding(.horizontal, Vox.gutter) }
    }
}

/// A run (re-summarize, re-transcribe) on the open meeting: its status line and progress, as the live view's header
/// shows them (LiveJobView); until now nothing on this screen said anything was happening until the summary changed.
/// Its own view, so the model's changes redraw this row only.
private struct RunStatusRow: View {
    @ObservedObject private var m = Model.shared
    let id: UUID
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        let active = m.runner.activeJob == id
        let job = m.runner.waiting.first(where: { $0.id == id })
        if active || job != nil {
            VStack(alignment: .leading, spacing: 8) {
                // At AX sizes the engine load goes under the status instead of squeezing it.
                (typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 8))) {
                    // Decoration for the status text next to it (an SF Symbol would read its own name).
                    if active { ProgressView().controlSize(.small).accessibilityHidden(true) } else { Image(systemName: "clock").voxFore(Vox.warning).accessibilityHidden(true) }
                    Vox.hint(job.map(m.jobStatus) ?? m.screen.status).lineLimit(3)
                    Spacer(minLength: 0)
                    if active { HwStatusLine() }
                }
                if active { ProgressView(value: min(max(m.runner.jobProgress, 0), 1)) }
            }
            .padding(Vox.inset).frame(maxWidth: .infinity, alignment: .leading)
            .voxRow()
            .accessibilityIdentifier("session-run-status")
        }
    }
}

/// Calls `changed` while the user scrolls a scroll view by hand (a programmatic scrollTo does not count). iOS 18 reads
/// the scroll phase; iOS 17 has no phase, so a drag recognized alongside the scroll stands in (on iOS 18 such a gesture
/// can hold up the scroll itself, hence not used there).
struct UserScrollWatch: ViewModifier {
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
            Vox.head(title)
            Vox.fine(desc)
            HStack { ForEach(fs) { f in Button(f.rawValue.uppercased()) { onPick(f) }.buttonStyle(.bordered) } }
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Vox.title(L("export_sheet_title"))
            group(L("export_group_document"), L("export_group_document_desc"), [.pdf, .md, .txt])
            group(L("export_group_subtitles"), L("export_group_subtitles_desc"), [.srt, .vtt, .lrc])
            if s.audio != nil {
                VStack(alignment: .leading, spacing: 8) {
                    Vox.head(L("export_group_session"))
                    Vox.fine(L("export_group_session_desc"))
                    Button(L("export_action_save")) { onSession() }.buttonStyle(.bordered)
                }
            }
            Button { UIPasteboard.general.string = Export.text(s, .txt); copied = true; Haptics.success(); UIAccessibility.post(notification: .announcement, argument: L("transcript_copied")) } label: { Label(L("export_copy_transcript"), systemImage: copied ? "checkmark" : "doc.on.doc") }
            Spacer()
        }.padding(Vox.gutterWide).frame(maxWidth: .infinity, alignment: .leading)
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
        switch full { case "DECISION": return Vox.success.color; case "ACTION": return Vox.info.color; case "OPEN-ISSUE": return Vox.warning.color
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
                Text(n.ts).font(.caption2.monospaced().weight(.semibold)).voxFore(Vox.info)
                    .padding(.horizontal, 5).padding(.vertical, 1).background(Vox.info.color.opacity(0.12), in: RoundedRectangle(cornerRadius: Vox.radiusSM)).tapHeight()
            }.buttonStyle(.plain)
            if let tag = n.tag, tag != "-" {
                Text(label).font(.caption2.weight(.semibold)).foregroundStyle(color)
                    .padding(.horizontal, 8).padding(.vertical, 2).background(color.opacity(0.14), in: Capsule())
            }
            Text(refs(n.text)).font(.caption).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
            if ["DECISION", "ACTION", "NUMBER"].contains(full), let sec {
                Button { seek(sec) } label: {
                    Text(L("agent_verify")).font(.caption2.weight(.semibold)).voxFore(Vox.warning)
                        .padding(.horizontal, 6).padding(.vertical, 1).overlay(Capsule().stroke(Vox.warning.color.opacity(0.5))).tapHeight()
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
        SpeakerStatsModel.shares(lines)
    }
    var body: some View {
        let sh = shares
        if !sh.isEmpty {
            Button { withAnimation { legend.toggle() } } label: { VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    Text(L("speaker_count", sh.count)).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    SpeakerStatsBar(shares: sh, palette: palette)
                    // SF Symbols ship their own VoiceOver names; inside this tap target the chevron is
                    // decoration and only added "Chevron Up" noise after the speaker count.
                    Image(systemName: legend ? "chevron.up" : "chevron.down").font(.caption).foregroundStyle(.secondary).accessibilityHidden(true)
                }
                if legend {
                    SpeakerStatsLegend(shares: sh, names: names, palette: palette)
                }
            }
            .padding(.horizontal, 4).contentShape(Rectangle()) }.buttonStyle(.plain)
            // A Button (button trait, press highlight); the value names what the tap does (the chevron's job for sighted users).
            .accessibilityValue(L(legend ? "show_less" : "show_more"))
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
                // A caption-size text button was a ~16 pt tall target; give it the 44 pt height without widening it.
                ChevronToggle(title: L(expanded ? "show_less" : "show_more"), expanded: expanded) { withAnimation { expanded.toggle() } }
            }
        }
    }
}

/// The iOS disclosure affordance: an accent borderless button, a label and a chevron that points where the
/// content goes. Shared by Show more / Show less and Show process / Hide process.
struct ChevronToggle: View {
    let title: String
    let expanded: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) { Label(title, systemImage: expanded ? "chevron.up" : "chevron.down").labelStyle(TrailingIconLabel()).tapHeight() }
            .buttonStyle(.borderless).font(.subheadline)
    }
}

/// Title then icon, the order iOS uses for disclosure links.
struct TrailingIconLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) { configuration.title; configuration.icon.imageScale(.small) }
    }
}

/// The player bar under a meeting: the scrubber with elapsed / total under it, then −5 s, play, +5 s centred and
/// the volume menu at the trailing edge. The saved meeting and the live view of a job being processed share it.
/// Large text: the times are capped and the volume menu drops out when the row no longer fits (the hardware
/// buttons still set the volume), so no control ever overlaps another.
struct PlayerBar: View {
    @ObservedObject var player: Player
    /// The side of the ±5 s / volume targets: ≥ 44 pt, grows with the text up to 60 pt.
    @ScaledMetric(relativeTo: .title2) private var side: CGFloat = 44
    private var target: CGFloat { min(max(side, 44), 60) }
    private var transport: some View {
        HStack(spacing: 24) {
            PlayerSkipBack(player: player, side: target)
            PlayerPlayPause(player: player)
            PlayerSkipForward(player: player, side: target)
        }
    }
    var body: some View {
        VStack(spacing: 2) {
            PlayerSeekSlider(player: player)
            HStack {
                Text(Export.mmss(player.time)).accessibilityHidden(true)   // the slider's value says it
                Spacer()
                Text(Export.mmss(player.duration)).accessibilityHidden(true)
            }
            .font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(1)
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            ViewThatFits(in: .horizontal) {
                // Room for all: transport centred, volume trailing (a leading spacer of the same width keeps the centre).
                HStack(spacing: 0) {
                    Color.clear.frame(width: target, height: 1)
                    Spacer(minLength: 8)
                    transport
                    Spacer(minLength: 8)
                    PlayerVolumeMenu(player: player).frame(width: target, height: target)
                }
                transport
            }
            .dynamicTypeSize(...DynamicTypeSize.accessibility3)
            .padding(.top, 2)
        }
        .padding(.horizontal, Vox.gutterWide).padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }
}
