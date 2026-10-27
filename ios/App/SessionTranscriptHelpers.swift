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
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Circle().fill(tint(l.speaker)).frame(width: 8, height: 8)
                    Button { onRenameSpeaker(l.speaker) } label: { Text(name(l.speaker)).font(.caption.bold()) }.buttonStyle(.borderless).disabled(busy)
                    Text(Export.mmss(l.start)).font(.caption2).foregroundStyle(.secondary)
                }
                Text(marked(l.text, query))
            }
            .id(l.id).padding(Vox.insetTight).frame(maxWidth: .infinity, alignment: .leading)
            .background(l.id == currentId ? Color.accentColor.opacity(0.18) : (matchId != nil && matchId == l.id ? Vox.highlight.opacity(0.22) : Vox.card), in: RoundedRectangle(cornerRadius: Vox.radiusMD))
            .contentShape(Rectangle())
            .onTapGesture { onSeekPlay(l.start) }
            .contextMenu { if !busy { lineMenu(l) } }
            .accessibilityAction { onSeekPlay(l.start) }   // VoiceOver's default action: play from this line
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
    var body: some View {
        Button { player.seek(player.time - 5) } label: { Image(systemName: "gobackward.5").font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }.accessibilityLabel(L("cd_back5"))
    }
}

/// The play/pause button of the player bar.
struct PlayerPlayPause: View {
    @ObservedObject var player: Player
    var body: some View {
        Button { player.toggle() } label: {
            Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title2).foregroundStyle(.white)
                .frame(width: 56, height: 56).background(Color.accentColor, in: Circle())
        }.accessibilityLabel(L(player.playing ? "pause" : "play"))
    }
}

/// The +5 s skip button of the player bar.
struct PlayerSkipForward: View {
    @ObservedObject var player: Player
    var body: some View {
        Button { player.seek(player.time + 5) } label: { Image(systemName: "goforward.5").font(.title2).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) }.accessibilityLabel(L("cd_forward5"))
    }
}

/// The volume menu plus total length at the player bar's trailing end.
struct PlayerVolumeCluster: View {
    @ObservedObject var player: Player
    var body: some View {
        HStack(spacing: 12) {
            PlayerVolumeMenu(player: player)
            Text(Export.mmss(player.duration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                .lineLimit(1).fixedSize()
        }.frame(minWidth: 96, alignment: .trailing)
    }
}

/// The mute/volume menu of the player bar.
struct PlayerVolumeMenu: View {
    @ObservedObject var player: Player
    var body: some View {
        Menu {
            Button { player.muted.toggle() } label: { Label(L(player.muted ? "cd_unmute" : "cd_mute"), systemImage: player.muted ? "speaker.wave.2" : "speaker.slash") }
            ForEach([0.25, 0.5, 0.75, 1.0], id: \.self) { v in Button("\(Int(v * 100)) %") { player.muted = false; player.volume = Float(v) } }
        } label: { Image(systemName: player.muted || player.volume == 0 ? "speaker.slash" : "speaker.wave.2").font(.body).foregroundStyle(.secondary).tapTarget() }
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
