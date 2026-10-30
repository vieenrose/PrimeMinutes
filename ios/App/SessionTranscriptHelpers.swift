import SwiftUI

/// SessionView transcript-tab views, extracted verbatim from SessionView to keep the
/// whole-module solver under its ceiling. Same-module structs taking plain values,
/// bindings and closures; SessionView keeps thin forwarders with identical parameters.

/// The horizontal speaker-colour legend above the transcript lines.
struct SessionSpeakerStrip: View {
    let speakers: [Int]
    let tint: (Int) -> Color
    let name: (Int) -> String
    var body: some View {
        if speakers.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack { ForEach(speakers, id: \.self) { k in
                    HStack(spacing: 5) { Circle().fill(tint(k)).frame(width: 8, height: 8); Text(name(k)).font(.caption.weight(.semibold)) }
                        .padding(.horizontal, 10).padding(.vertical, 5).background(Vox.card, in: Capsule())
                } }
            }
        }
    }
}

/// The transcript search bar: field, ≈ toggle, match stepper, results toggle, Ask, close.
struct SessionSearchBar: View {
    @Binding var query: String
    @Binding var match: Int
    @Binding var resultsOpen: Bool
    @Binding var semantic: Bool
    @FocusState.Binding var searchFocus: Bool
    let semIndexing: Bool
    let hasResults: Bool
    let askMeetingReady: Bool
    let onQueryChange: () -> Void
    let onToggleSemantic: () -> Void
    let onStep: (Int) -> Void
    let onToggleResults: () -> Void
    let onAskMeeting: () -> Void
    let onClose: () -> Void
    let matchText: String
    let resultsView: AnyView
    var body: some View {
        HStack {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
            TextField(L("search_transcript_hint"), text: $query).focused($searchFocus).submitLabel(.search).autocorrectionDisabled()
                .onChange(of: query) { _, _ in onQueryChange() }
            if EmbedHost.ready {
                Button { onToggleSemantic() } label: { Text("≈").font(.headline).padding(.horizontal, 6).padding(.vertical, 2).background(semantic ? Color.accentColor.opacity(0.25) : Color.clear, in: RoundedRectangle(cornerRadius: Vox.radiusSM)).tapHeight() }
                    .buttonStyle(.plain).accessibilityLabel(L("search_semantic_cd")).accessibilityAddTraits(semantic ? .isSelected : [])
            }
            if !query.isEmpty {
                Text(matchText).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                Button { onStep(-1) } label: { Image(systemName: "chevron.up").tapHeight() }.accessibilityLabel(L("search_prev"))
                Button { onStep(1) } label: { Image(systemName: "chevron.down").tapHeight() }.accessibilityLabel(L("search_next"))
            }
            if !query.isEmpty && hasResults {
                Button { onToggleResults() } label: { Image(systemName: resultsOpen ? "chevron.up.square" : "list.bullet").tapHeight() }
                    .accessibilityLabel(L(resultsOpen ? "search_results_hide" : "search_results_show"))
            }
            if askMeetingReady {
                Button { onAskMeeting() } label: { Image(systemName: "sparkles").tapHeight() }
                    .accessibilityLabel(L("ask_meeting_title")).accessibilityIdentifier("search-ask-meeting")
            }
            Button { onClose() } label: { Image(systemName: "xmark.circle.fill").tapHeight() }.accessibilityLabel(L("search_close"))
        }.padding(Vox.insetTight).voxRow()
        if semantic && semIndexing {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small).accessibilityHidden(true)
                Vox.meta(L("search_semantic_indexing"))
            }.padding(.horizontal, Vox.inset).accessibilityElement(children: .combine).accessibilityIdentifier("search-semantic-indexing")
        }
        if resultsOpen && hasResults { resultsView }
    }
}

/// The transcript lines: pipeline credit, empty-state guidance, and one row per line.
struct SessionTranscriptLines: View {
    let lines: [Utterance]
    let noSpeech: Bool
    let busy: Bool
    let currentId: UUID?
    let query: String
    let matchId: UUID?
    let tint: (Int) -> Color
    let name: (Int) -> String
    let marked: (String, String) -> AttributedString
    let onSeekPlay: (Double) -> Void
    let onRenameSpeaker: (Int) -> Void
    let onEditLine: (UUID, String) -> Void
    let onMoveLine: (UUID) -> Void
    let onSeam: (Int) -> Void
    let lineMenu: (Utterance) -> AnyView
    var body: some View {
        if !lines.isEmpty { Vox.micro(L("pipeline_transcript_via", "X-ASR", L("pipeline_diar_nemotron"))) }
        // A session with no lines had no guidance at all (the summary tab has one): say why the tab is blank.
        if lines.isEmpty {
            ContentUnavailableView(L(noSpeech ? "status_no_speech" : "transcript_empty_title"), systemImage: noSpeech ? "waveform.slash" : "text.alignleft",
                                   description: noSpeech ? nil : Text(L("transcript_empty"))).padding(.top, 24)
        }
        ForEach(Array(lines.enumerated()), id: \.element.id) { idx, l in
            // A seam handle between two speakers: adjust where one stops and the next starts.
            if idx > 0, lines[idx - 1].speaker != l.speaker, !busy {
                SpeakerSeam(colorA: tint(lines[idx - 1].speaker), colorB: tint(l.speaker), labelA: name(lines[idx - 1].speaker), labelB: name(l.speaker)) { onSeam(idx) }
                    .padding(.vertical, -4)
            }
            // A Button, not a tap gesture: press highlight, the VoiceOver button trait (play from this line),
            // Full Keyboard Access. The speaker name inside stays its own (borderless) button.
            Button { onSeekPlay(l.start) } label: { VStack(alignment: .leading, spacing: TranscriptStyle.textSpacing) {
                // Redesign: the live capture's layout — the name in its tint, the time, then the text on the page (no card).
                HStack(spacing: TranscriptStyle.headerSpacing) {
                    Button { onRenameSpeaker(l.speaker) } label: { Text(name(l.speaker)).transcriptSpeaker(tint(l.speaker)) }.buttonStyle(.borderless).disabled(busy)
                    Text(Export.mmss(l.start)).transcriptTime()
                }
                Text(marked(l.text, query)).transcriptText()
            }
            .id(l.id).padding(.vertical, TranscriptStyle.rowPadding(stack: 8))   // SessionView's LazyVStack spacing
           .padding(.horizontal, Vox.insetTight).frame(maxWidth: .infinity, alignment: .leading)
            .background(l.id == currentId ? Vox.accent.opacity(0.12) : (matchId != nil && matchId == l.id ? Vox.highlight.opacity(0.22) : Color.clear), in: RoundedRectangle(cornerRadius: Vox.radiusMD))
            .contentShape(Rectangle()) }
            .buttonStyle(.plain)
            .contextMenu { if !busy { lineMenu(l) } }
            .accessibilityAction(named: L("cd_line_actions")) { if !busy { onEditLine(l.id, l.text) } }
            // The line menu (move/merge) is long-press only, unreachable to VoiceOver: a named action opens the same move.
            .accessibilityAction(named: L("speaker_move_line")) { if !busy { onMoveLine(l.id) } }
        }
    }
}

/// The player bar's seek slider with its VoiceOver label. Split out of PlayerBar
/// (SessionView.swift) so the bar's body stays a tiny composition.
struct PlayerSeekSlider: View {
    @ObservedObject var player: Player
    var body: some View {
        Slider(value: Binding(get: { player.time }, set: { player.seek($0) }), in: 0...max(1, player.duration)) { editing in if !editing, !player.playing { player.toggle() } }
            // VoiceOver otherwise reads a bare percentage of the meeting; say what it is and where playback is.
            .accessibilityLabel(L("cd_seek")).accessibilityValue(L("cd_seek_value", Export.mmss(player.time), Export.mmss(player.duration)))
    }
}

/// The −5 s skip button of the player bar.
struct PlayerSkipBack: View {
    @ObservedObject var player: Player
    var side: CGFloat = 44
    var body: some View {
        Button { player.seek(player.time - 5) } label: { Image(systemName: "gobackward.5").font(.title2).frame(width: side, height: side).contentShape(Rectangle()) }.accessibilityLabel(L("cd_back5"))
    }
}

/// The play/pause button of the player bar.
struct PlayerPlayPause: View {
    @ObservedObject var player: Player
    var body: some View {
        Button { player.toggle() } label: {
            Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title2)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 40, height: 40)
        }
        .buttonStyle(.borderedProminent).buttonBorderShape(.circle).controlSize(.large)
        .accessibilityLabel(L(player.playing ? "pause" : "play"))
    }
}

/// The +5 s skip button of the player bar.
struct PlayerSkipForward: View {
    @ObservedObject var player: Player
    var side: CGFloat = 44
    var body: some View {
        Button { player.seek(player.time + 5) } label: { Image(systemName: "goforward.5").font(.title2).frame(width: side, height: side).contentShape(Rectangle()) }.accessibilityLabel(L("cd_forward5"))
    }
}

/// The mute/volume menu of the player bar.
struct PlayerVolumeMenu: View {
    @ObservedObject var player: Player
    var body: some View {
        Menu {
            Button { player.muted.toggle() } label: { Label(L(player.muted ? "cd_unmute" : "cd_mute"), systemImage: player.muted ? "speaker.wave.2" : "speaker.slash") }
            ForEach([0.25, 0.5, 0.75, 1.0], id: \.self) { v in Button("\(Int(v * 100)) %") { player.muted = false; player.volume = Float(v) } }
        } label: { Image(systemName: player.muted || player.volume == 0 ? "speaker.slash" : "speaker.wave.2").font(.body).foregroundStyle(.secondary).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }
            .accessibilityLabel(L("cd_volume")).accessibilityValue(player.muted || player.volume == 0 ? L("cd_muted") : "\(Int(player.volume * 100)) %")
    }
}

/// Plain values the follow-scroll modifier reads: one snapshot per body pass,
/// so the modifier's own onChanges stay tiny expressions.
struct SessionFollowScroll {
    let proxy: ScrollViewProxy
    let playing: Bool
    let queryEmpty: Bool
    let tab: Int
    let userScrolledAt: Date
    let current: UUID?
    let match: Int
    let matchId: UUID?
    let jumpTo: UUID?
    let firstHitId: UUID?
    let resultsOpen: Bool
    let hasResults: Bool
    let onFollow: (UUID) -> Void
}

/// Follows the playing line and the search hits (SessionView body): the four
/// onChange handlers as one modifier, each a small explicit function.
struct SessionFollow: ViewModifier {
    let scroll: SessionFollowScroll
    func body(content: Content) -> some View {
        content
            // Follow the playing line only while it plays, not while a search is open, and not right after a manual scroll.
            .onChange(of: scroll.current) { _, id in followCurrent(id) }
            // Search: the current hit scrolls into view as the query changes and on Next/Prev (Android TranscriptSearchBar), not only once the player plays.
            .onChange(of: scroll.match) { _, _ in followMatch() }
            .onChange(of: scroll.jumpTo) { _, id in if let id { withAnimation { scroll.onFollow(id) } } }
            // Not while the results list is open: the bar and the list sit in this scroll view, and jumping to the
            // first hit as the query was typed (or the ≈ passages landed) scrolled them off screen (14 Pro Max).
            .onChange(of: scroll.firstHitId) { _, _ in followFirstHit() }
    }
    private func followCurrent(_ id: UUID?) {
        let s = scroll
        if s.playing, s.queryEmpty, s.tab == 1, Date().timeIntervalSince(s.userScrolledAt) > 4, let id { withAnimation { s.onFollow(id) } }
    }
    private func followMatch() {
        let s = scroll
        if let id = s.matchId { withAnimation { s.onFollow(id) } }
    }
    private func followFirstHit() {
        let s = scroll
        if !(s.resultsOpen && s.hasResults), let id = s.matchId { withAnimation { s.onFollow(id) } }
    }
}

/// VoiceOver route for per-line re-attribution: one row per other speaker,
/// same reassign as the line menu. Split out of SessionView's sheet.
struct SessionMoveLineSheet: View {
    let lineId: UUID
    let lineSpeaker: Int
    let speakers: [Int]
    let tint: (Int) -> Color
    let name: (Int) -> String
    let onPick: (Int) -> Void
    let onCancel: () -> Void
    var body: some View {
        NavigationStack {
            List {
                ForEach(speakers.filter { $0 != lineSpeaker }, id: \.self) { k in
                    SessionMoveLineRow(speaker: k, tint: tint(k), name: name(k), onPick: { onPick(k) })
                }
            }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { onCancel() } } }
            .navigationTitle(L("speaker_move_line")).navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium]).presentationDragIndicator(.visible)
    }
}

/// One speaker row in the move-line sheet.
struct SessionMoveLineRow: View {
    let speaker: Int
    let tint: Color
    let name: String
    let onPick: () -> Void
    var body: some View {
        Button { onPick() } label: {
            HStack(spacing: 8) { Circle().fill(tint).frame(width: 10, height: 10); Text(name) }
                .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

/// The line-text edit sheet: a full editor (an alert field shows one line of a
/// paragraph-long utterance). Split out of SessionView's sheet.
struct SessionLineEditSheet: View {
    @Binding var draft: String
    let doneEnabled: Bool
    let onCancel: () -> Void
    let onDone: () -> Void
    @FocusState private var focused: Bool
    var body: some View {
        NavigationStack {
            TextEditor(text: $draft).focused($focused).padding(.horizontal, Vox.inset)
                .scrollDismissesKeyboard(.interactively)   // a long paragraph: scroll to read the tail under the keyboard
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { onCancel() } }
                    ToolbarItem(placement: .confirmationAction) { Button(L("done")) { onDone() }.disabled(!doneEnabled) }   // like Android: an emptied line is not a save
                }
                // Hide the keyboard, keep editing. Not a .keyboard toolbar: like Ask's composer, it never showed in this
                // sheet on iOS 26.5; a keyboard-down button sits on the editor's bottom edge while it has focus.
                .safeAreaInset(edge: .bottom) {
                    if focused {
                        HStack { Spacer(); Button { focused = false } label: { Image(systemName: "keyboard.chevron.compact.down").tapTarget() }
                            .accessibilityLabel(L("done")) }.padding(.horizontal, Vox.inset)
                    }
                }
                .onAppear { focused = true }
                .navigationTitle(L("cd_edit")).navigationBarTitleDisplayMode(.inline)
        }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
    }
}

/// The session's ⋯ menu (rename, Ask, share, re-run). Split out
/// of SessionView's toolbar; takes plain values and closures.
struct SessionMoreMenu: View {
    let hasLines: Bool
    let hasAudio: Bool
    let noSpeech: Bool
    let askMeetingReady: Bool
    let busy: Bool
    let title: String
    let onRename: () -> Void
    let onAsk: () -> Void
    let onShare: () -> Void
    let onRetranscribe: () -> Void
    let onResummarize: () -> Void
    var hasSummary = false
    var onCopySummary: () -> Void = {}
    var onEditSummary: () -> Void = {}
    var onDelete: (() -> Void)? = nil
    var body: some View {
        Menu {
            // Redesign: the summary's Copy / Edit moved here from its card.
            if hasSummary {
                Button(L("cd_copy_summary"), systemImage: "doc.on.doc") { onCopySummary() }
                Button(L("summary_edit"), systemImage: "square.and.pencil") { onEditSummary() }.disabled(busy)
            }
            Button(L("rename"), systemImage: "pencil") { onRename() }.disabled(busy).accessibilityIdentifier("renameTitle")
            if askMeetingReady { Button { onAsk() } label: { Label(L("ask_meeting_title"), systemImage: "sparkles") } }
            // Share v2: the one entry (the whole session, the PDF, the audio; AirDrop, mail, other apps).
            if hasAudio || hasLines { Button(L("share_title"), systemImage: "square.and.arrow.up") { onShare() }.disabled(busy).accessibilityIdentifier("shareMeeting") }
            if hasAudio {
                Button(L("re_transcribe")) { onRetranscribe() }.disabled(busy)
                if !noSpeech { Button(L("re_summarize")) { onResummarize() }.disabled(busy) }   // nothing to summarize (Android canReSummarize)
            }
            if let onDelete { Divider(); Button(L("delete"), systemImage: "trash", role: .destructive) { onDelete() }.disabled(busy) }
        } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(L("cd_more_options")) }
    }
}

/// The rename-speaker sheet: voice suggestions around the SpeakerNameFlow
/// picker. Split out of SessionView's sheet; same callbacks, one call.
struct SessionRenameSheet: View {
    let session: Session
    let speakerKey: Int
    let sessions: [Session]
    let canPlaySample: Bool
    let speaker: PickerSpeaker
    let speakers: [PickerSpeaker]
    let onPlaySample: () -> Void
    let onLink: (Person) -> Void
    let onNew: (String) -> Void
    let onClear: () -> Void
    let onMerge: (Int) -> Void
    let onDismiss: () -> Void
    var body: some View {
        VoiceSuggested(session: session, speaker: speakerKey) { suggestions, note in
            SpeakerNameFlow(speaker: speaker, speakers: speakers, sessions: sessions,
                            suggestions: suggestions, voiceNote: note,
                            onPlaySample: canPlaySample ? onPlaySample : nil,
                            onLink: onLink,
                            onNew: onNew,
                            onClear: onClear,
                            onMerge: onMerge,
                            onDismiss: onDismiss)
        }
        .presentationDetents([.medium, .large]).presentationDragIndicator(.visible)
    }
}

/// The session toolbar: undo/redo, transcript search, ⋯ menu. Split out of
/// SessionView's toolbar; takes plain values and closures.
struct SessionToolbar: ToolbarContent {
    let showUndo: Bool
    let canUndo: Bool
    let canRedo: Bool
    let busy: Bool
    let hasLines: Bool
    let hasAudio: Bool
    let noSpeech: Bool
    let askMeetingReady: Bool
    let title: String
    /// The title as shown (speaker refs resolved): the nav bar's tap-to-rename button.
    let displayTitle: String
    let onUndo: () -> Void
    let onRedo: () -> Void
    let onSearch: () -> Void
    let onRename: () -> Void
    let onAsk: () -> Void
    let onShare: () -> Void
    let onRetranscribe: () -> Void
    let onResummarize: () -> Void
    var hasSummary = false
    var onCopySummary: () -> Void = {}
    var onEditSummary: () -> Void = {}
    var onDelete: (() -> Void)? = nil
    var body: some ToolbarContent {
        // Tap the title to rename the meeting (the ⋯ menu's Rename does the same).
        ToolbarItem(placement: .principal) {
            Button { onRename() } label: {
                Text(displayTitle).font(.headline).lineLimit(1).foregroundStyle(Color.primary)
            }.buttonStyle(.plain).disabled(busy)
                .accessibilityLabel(displayTitle).accessibilityHint(L("rename"))
                .accessibilityIdentifier("sessionTitle")
        }
        if showUndo {
            ToolbarItem(placement: .primaryAction) {
                Button { onUndo() } label: { Image(systemName: "arrow.uturn.backward") }.disabled(!canUndo || busy).accessibilityLabel(L("undo"))
            }
            ToolbarItem(placement: .primaryAction) {
                Button { onRedo() } label: { Image(systemName: "arrow.uturn.forward") }.disabled(!canRedo || busy).accessibilityLabel(L("redo"))
            }
        }
        ToolbarItem(placement: .primaryAction) {
            Button { onSearch() } label: { Image(systemName: "magnifyingglass") }.accessibilityLabel(L("search_transcript"))
        }
        ToolbarItem(placement: .primaryAction) {
            SessionMoreMenu(
                hasLines: hasLines, hasAudio: hasAudio, noSpeech: noSpeech,
                askMeetingReady: askMeetingReady, busy: busy, title: title,
                onRename: onRename, onAsk: onAsk, onShare: onShare,
                onRetranscribe: onRetranscribe, onResummarize: onResummarize,
                hasSummary: hasSummary, onCopySummary: onCopySummary, onEditSummary: onEditSummary, onDelete: onDelete)
        }
    }
}

/// The tab page: segmented picker plus the selected tab. Split out of
/// SessionView's ScrollView so the Picker labels leave the body chain.
struct SessionTabPage: View {
    @Binding var tab: Int
    let summary: AnyView
    let transcript: AnyView
    var notes: AnyView = AnyView(EmptyView())
    var body: some View {
        VStack(spacing: 12) {
            switch tab { case 0: summary; case 2: notes; default: transcript }
        }.padding(.top, 4)
    }
}

/// The pinned header over the scroll page: the tab picker, then (transcript tab) the speaker strip.
struct SessionStickyHeader: View {
    @Binding var tab: Int
    let speakers: [Int]
    let tint: (Int) -> Color
    let name: (Int) -> String
    /// Notes in the 筆記 segment (0: the segment is not offered).
    var notes = 0
    /// The meta line: date · length, then the speakers as tinted chips.
    var meta = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SessionMetaLine(meta: meta, speakers: speakers, tint: tint, name: name)
            SessionTabPicker(tab: $tab, notes: notes)
        }
    }
}

/// The meeting's meta line (redesign): date · length, then each speaker as a tinted chip with its name.
struct SessionMetaLine: View {
    let meta: String
    let speakers: [Int]
    let tint: (Int) -> Color
    let name: (Int) -> String
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                if !meta.isEmpty { Text(meta).font(Vox.fontMeta).foregroundStyle(Vox.ink2).padding(.trailing, 2) }
                ForEach(speakers, id: \.self) { k in VoxSpeakerChip(name: name(k), color: tint(k)) }
            }.padding(.horizontal, Vox.gutter)
        }
    }
}

/// The 摘要 / 筆記 n / 逐字稿 segmented picker (tags: 0 summary, 2 notes, 1 transcript — 1 stays the transcript for
/// the deep links that open it).
struct SessionTabPicker: View {
    @Binding var tab: Int
    var notes = 0
    var body: some View {
        Picker(L("cd_meeting_view"), selection: $tab) {
            Text(L("tab_summary")).tag(0)
            if notes > 0 { Text(L("tab_notes_n", notes)).tag(2) }
            Text(L("tab_transcript")).tag(1)
        }
        .pickerStyle(.segmented).padding(.horizontal, Vox.gutter)
    }
}

/// The resummary-undo banner over the session: restores the pre-run summary.
/// Takes the session as a binding so the undo writes through.
struct SessionResummaryUndo: View {
    @Binding var session: Session
    let onUndo: () -> Void
    let onExpire: () -> Void
    var body: some View {
        HStack {
            Vox.hint(L("resummary_done")); Spacer()
            Button(L("undo")) { onUndo() }.bold()
        }.padding(Vox.inset).background(.thinMaterial, in: RoundedRectangle(cornerRadius: Vox.radiusMD)).padding(.horizontal, Vox.gutter).padding(.bottom, 110)
            .task { try? await Task.sleep(nanoseconds: 8_000_000_000); withAnimation { onExpire() } }
    }
}

/// The speaker-edit undo banner over the session.
struct SessionSpeakerUndoBanner: View {
    let message: String
    let busy: Bool
    let onUndo: () -> Void
    let onExpire: () -> Void
    var body: some View {
        HStack {
            Vox.hint(message); Spacer()
            Button(L("undo")) { onUndo() }.bold().disabled(busy)
        }.padding(Vox.inset).background(.thinMaterial, in: RoundedRectangle(cornerRadius: Vox.radiusMD)).padding(.horizontal, Vox.gutter).padding(.bottom, 110)
    }
}

/// The boundary-move sheet at seam k: where speaker k-1 stops and k starts.
/// Split out of SessionView's sheet; same BoundaryEditorSheet, one call.
struct SessionSeamSheet: View {
    let seam: Int
    let lines: [Utterance]
    let tint: (Int) -> Color
    let name: (Int) -> String
    let canPlay: Bool
    let onPreview: (Double, Double) -> Void
    let onApply: (Int) -> Void
    let onCancel: () -> Void
    var body: some View {
        let a = lines[seam - 1], b = lines[seam], sm = BoundaryEdits.seam(a, b)
        return SessionSeamInner(tokens: sm.tokens, starts: sm.starts, end: sm.end, initialSplit: sm.split,
            colorA: tint(a.speaker), labelA: name(a.speaker), colorB: tint(b.speaker), labelB: name(b.speaker),
            estimated: BoundaryEdits.estimated(a) || BoundaryEdits.estimated(b),
            canPlay: canPlay, onPreview: onPreview, onApply: onApply, onCancel: onCancel)
    }
}

/// The BoundaryEditorSheet for a seam move, with its detents.
struct SessionSeamInner: View {
    let tokens: [String], starts: [Double], end: Double, initialSplit: Int
    let colorA: Color, labelA: String, colorB: Color, labelB: String
    let estimated: Bool
    let canPlay: Bool
    let onPreview: (Double, Double) -> Void
    let onApply: (Int) -> Void
    let onCancel: () -> Void
    var body: some View {
        BoundaryEditorSheet(tokens: tokens, starts: starts, end: end, initialSplit: initialSplit,
                            colorA: colorA, labelA: labelA, colorB: colorB, labelB: labelB,
                            estimated: estimated,
                            canPlay: canPlay, demo: SessionView.devDemo,
                            onPreview: onPreview,
                            onApply: { split, _ in onApply(split) },
                            onCancel: onCancel)
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible).presentationBackground(Vox.page)
    }
}

/// The line-split sheet at index: cut the line, second half to a speaker.
/// Split out of SessionView's sheet; same BoundaryEditorSheet, one call.
struct SessionSplitSheet: View {
    let index: Int
    let lines: [Utterance]
    let tint: (Int) -> Color
    let name: (Int) -> String
    let canPlay: Bool
    let onPreview: (Double, Double) -> Void
    let onApply: (Int, Int) -> Void
    let onCancel: () -> Void
    var body: some View {
        let u = lines[index], toks = BoundaryEdits.tokenize(u.text), order = BoundaryEdits.splitCandidates(lines, index: index)
        let known = Set(lines.map(\.speaker))
        return BoundaryEditorSheet(tokens: toks, starts: BoundaryEdits.tokenStarts(u, toks), end: u.end, initialSplit: toks.count / 2,
                            colorA: tint(u.speaker), labelA: name(u.speaker), colorB: tint(order[0]), labelB: name(order[0]),
                            choices: order.map { SpeakerChoice(id: $0, label: name($0), color: tint($0), isNew: !known.contains($0)) },
                            estimated: BoundaryEdits.estimated(u),
                            canPlay: canPlay, demo: SessionView.devDemo,
                            onPreview: onPreview,
                            onApply: { at, spk in onApply(at, spk ?? order[0]) },
                            onCancel: onCancel)
            .presentationDetents([.medium, .large]).presentationDragIndicator(.visible).presentationBackground(Vox.page)
    }
}

/// The ScrollView's trailing modifiers: pinned player bar, pinned run status,
/// follow-scroll. One modifier so the body chain stays short.
struct SessionInsets: ViewModifier {
    let follow: SessionFollowScroll
    let sessionId: UUID
    let busy: Bool
    let playerAvailable: Bool
    let playerBar: AnyView
    let header: AnyView
    var preview: AnyView? = nil
    func body(content: Content) -> some View {
        content
            // The citation preview sits just above the player (never over it); without audio it stands alone.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if preview != nil || playerAvailable { VStack(spacing: 0) { if let preview { preview }; if playerAvailable { playerBar } } }
            }
            // A run's status stays pinned above the scrollable content (Android TranscriptSearchBar),
            // not in it: scrolling either tab kept pushing it off screen.
            // So do the Summary/Transcript tabs and the speaker strip (a pinned header, as in native iOS apps).
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 8) { SessionRunStatusTop(id: sessionId, busy: busy); header }
                    .padding(.vertical, 8).frame(maxWidth: .infinity).background(Vox.bg)
                    .overlay(alignment: .bottom) { Vox.hairline.frame(height: 1) }
            }
            .modifier(SessionFollow(scroll: follow))
    }
}

/// A plain text-edit sheet (summary, action items): editor plus cancel/done.
/// Split out of SessionView's two sheets; same layout, one view.
struct SessionTextEditSheet: View {
    @Binding var draft: String
    let title: String
    let doneEnabled: Bool
    let onCancel: () -> Void
    let onDone: () -> Void
    @FocusState private var focused: Bool
    var body: some View {
        NavigationStack {
            TextEditor(text: $draft).focused($focused).padding(.horizontal, Vox.inset)
                .scrollDismissesKeyboard(.interactively)   // a long paragraph: scroll to read the tail under the keyboard
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { onCancel() } }
                    ToolbarItem(placement: .confirmationAction) { Button(L("done")) { onDone() }.disabled(!doneEnabled) }
                }
                // Hide the keyboard, keep editing. Not a .keyboard toolbar: like Ask's composer, it never showed in this
                // sheet on iOS 26.5; a keyboard-down button sits on the editor's bottom edge while it has focus.
                .safeAreaInset(edge: .bottom) {
                    if focused {
                        HStack { Spacer(); Button { focused = false } label: { Image(systemName: "keyboard.chevron.compact.down").tapTarget() }
                            .accessibilityLabel(L("done")) }.padding(.horizontal, Vox.inset)
                    }
                }
                .onAppear { focused = true }
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// The session-export progress overlay.
struct SessionExporting: View {
    /// 0…1 while the audio encodes (nil: indeterminate); `onCancel` adds a Cancel button.
    var progress: Float? = nil
    var title: String = L("exporting")
    var onCancel: (() -> Void)? = nil
    var body: some View {
        VStack(spacing: 8) {
            if let progress { ProgressView(value: Double(progress)).frame(width: 180) } else { ProgressView() }
            Vox.head(title)
            if let onCancel { Button(L("cancel"), role: .cancel) { onCancel() }.buttonStyle(.bordered).accessibilityIdentifier("shareCancel") }
            else { Vox.meta(L("exporting_hint")).multilineTextAlignment(.center) }
        }
            .padding(20).background(.regularMaterial, in: RoundedRectangle(cornerRadius: Vox.radiusXL)).padding(40)
    }
}

/// The session's scroll page: tab page plus scroll modifiers, insets and
/// follow behavior. Split out of SessionView's body so the ScrollView leaves
/// the body chain (the solver ceiling sat on the ScrollView block itself).
struct SessionScrollPage: View {
    let proxy: ScrollViewProxy
    @Binding var tab: Int
    let summary: AnyView
    let transcript: AnyView
    let appear: () -> Void
    let scrolled: () -> Void
    let follow: SessionFollowScroll
    let sessionId: UUID
    let busy: Bool
    let playerAvailable: Bool
    let playerBar: AnyView
    let header: AnyView
    var notes: AnyView = AnyView(EmptyView())
    var preview: AnyView? = nil
    var body: some View {
        ScrollView {
            SessionTabPage(tab: $tab, summary: summary, transcript: transcript, notes: notes)
        }
        .scrollDismissesKeyboard(.interactively)   // the transcript search keyboard: dragging the transcript drops it
        .modifier(UserScrollWatch { scrolled() })   // on the scroll view only: a drag on the player bar's slider is not a scroll
        .background(Vox.page)
        .onAppear { appear() }
        .modifier(SessionInsets(follow: follow, sessionId: sessionId, busy: busy, playerAvailable: playerAvailable, playerBar: playerBar, header: header, preview: preview))
    }
}
