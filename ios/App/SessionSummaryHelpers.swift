import SwiftUI
import UIKit

/// SessionView summary-tab views, extracted verbatim from SessionView to keep the
/// whole-module solver under its ceiling. Same-module structs taking plain values
/// and closures; SessionView keeps thin forwarders with identical parameters.
struct SessionSummaryTab: View {
    let session: Session
    let busy: Bool
    let showActions: Bool
    let showProcess: Bool
    let hasMailRecipients: Bool
    let palette: [Color]
    let names: [(String, Color)]
    let refs: (String) -> String
    let playerAvailable: Bool
    let onSeekPlay: (Double) -> Void
    let onCopySummary: () -> Void
    let onEditSummary: () -> Void
    let onCopyActions: () -> Void
    let onEditActions: () -> Void
    let onToggleProcess: () -> Void
    let onOpenMail: () -> Void
    let notesSection: (String, [String]) -> AnyView
    var body: some View {
        VStack(spacing: 12) {
            // Android: the agent card exists only when the reading left notes (agent.restore found a journal).
            if !session.notes.isEmpty { VStack(alignment: .leading, spacing: 6) { SessionCard {
                HStack {
                    Circle().fill(Vox.success.color).frame(width: 9, height: 9)
                    VStack(alignment: .leading) {
                        Vox.head(L("ai_notes"))
                        Vox.meta(L("notes_n", session.notes.count))
                    }
                    Spacer()
                    Text(L("done_pill")).font(.caption.weight(.semibold)).padding(.horizontal, 10).padding(.vertical, 4)
                        .voxChip(Vox.success).voxFore(Vox.success)
                }
                // Android AgentPanel: "show process" under the header; opened, the notes, the caution, then "hide process".
                if showProcess {
                    ForEach(session.notes) { n in NoteRow(n: n) { sec in onSeekPlay(Double(sec)) } }
                    // DEV builds: the saved reading's off-protocol counts (model guardrail).
                    if Dev.on, let g = session.readerGuard { Text("DEV guard · " + g.summary).font(.caption2.monospaced()).foregroundStyle(g.exceeds(ModelCard.default.reader.guardrail) ? Vox.danger.color : Color.secondary) }
                }
                ChevronToggle(title: L(showProcess ? "hide_process" : "show_process"), expanded: showProcess) { onToggleProcess() }
            }
            if showProcess { CardFooter(text: L("agent_notes_caution")) } } }
            if !session.summary.isEmpty { VStack(alignment: .leading, spacing: 6) {
                SessionCard {
                    // No heading: the tab already carries the name (Android SummaryCard showHeading = false in portrait).
                    // One overflow menu instead of an icon-only row above the text; the same pair on long press.
                    HStack(alignment: .top, spacing: 8) {
                        Collapsible(lines: 12) { Text(Markdown.render(refs(session.summary), anchors: playerAvailable ? Color.secondary : nil, speakers: names)) }.environment(\.openURL, OpenURLAction { u in
                            if u.scheme == "vox", let t = Double(u.host ?? "") { onSeekPlay(t); return .handled }
                            return .systemAction })
                            .contextMenu { CardActionItems(onCopy: onCopySummary, onEdit: onEditSummary) }
                        CardActionsMenu(copyLabel: L("cd_copy_summary"), onCopy: onCopySummary, onEdit: onEditSummary)
                    }
                }
                CardFooter(text: L("ai_disclaimer"))
            } }
            if showActions, let a = session.actionItems { VStack(alignment: .leading, spacing: 6) {
                SessionCard {
                    HStack(spacing: 16) {
                        Text(L("card_action_items")).font(.headline); Spacer()
                        CardActionsMenu(copyLabel: L("cd_copy_actions"), onCopy: onCopyActions, onEdit: onEditActions)
                    }
                    Collapsible(lines: 8) { Text(Markdown.render(refs(a), anchors: playerAvailable ? Color.secondary : nil, speakers: names)) }.environment(\.openURL, OpenURLAction { u in
                        if u.scheme == "vox", let t = Double(u.host ?? "") { onSeekPlay(t); return .handled }
                        return .systemAction })
                        .contextMenu { CardActionItems(onCopy: onCopyActions, onEdit: onEditActions) }
                }
                CardFooter(text: L("actions_verify_hint"), warning: true)
            } }
            // Android NotesSection: what the v2 NOTES format adds to a prose summary, an empty section shows nothing.
            if let n = session.legacyNotes.flatMap(MeetingNotes.parse) {
                notesSection(L("notes_decisions"), n.decisions)
                notesSection(L("notes_open"), n.open)
                notesSection(L("notes_topics"), n.topics)
                ForEach(n.extra) { notesSection($0.key, $0.lines) }
            }
            if !session.lines.isEmpty { SpeakerStats(lines: session.lines, names: session.speakerNames, palette: palette).id("stats") }
            if hasMailRecipients {
                HStack { Spacer(); Button { onOpenMail() } label: { Label(L("mail_chip"), systemImage: "envelope") }.buttonStyle(.bordered).accessibilityIdentifier("speakers-email") }
            }
            if session.summary.isEmpty { Vox.hint(L(busy ? "summary_pending_hint" : session.lines.isEmpty ? "status_no_speech" : "summary_missing_hint")).padding(.top, 12) }
        }.padding(.horizontal, Vox.gutter)
    }
}

struct SessionNotesSection: View {
    let header: String
    let items: [String]
    let names: [(String, Color)]
    let refs: (String) -> String
    let playerAvailable: Bool
    let onSeekPlay: (Double) -> Void
    var body: some View {
        if !items.isEmpty {
            SessionCard {
                Vox.subhead(header)
                ForEach(Array(items.enumerated()), id: \.offset) { _, it in
                    Text(Markdown.render("- " + refs(it), anchors: playerAvailable ? Vox.info.color : nil, speakers: names)).font(.subheadline)
                }
            }.environment(\.openURL, OpenURLAction { u in
                if u.scheme == "vox", let t = Double(u.host ?? "") { onSeekPlay(t); return .handled }
                return .systemAction })
        }
    }
}

/// Copy and Edit, shared by a card's overflow menu and its long-press context menu.
struct CardActionItems: View {
    let onCopy: () -> Void
    let onEdit: () -> Void
    var body: some View {
        Button { onCopy() } label: { Label(L("copy"), systemImage: "doc.on.doc") }
        Button { onEdit() } label: { Label(L("cd_edit"), systemImage: "pencil") }
    }
}

/// A card's overflow menu (ellipsis.circle): Copy, Edit.
struct CardActionsMenu: View {
    let copyLabel: String
    let onCopy: () -> Void
    let onEdit: () -> Void
    /// After a copy the glyph turns into a checkmark for a beat (the haptic comes with the copy itself).
    @State private var copied = false
    var body: some View {
        Menu { CardActionItems(onCopy: copy, onEdit: onEdit) } label: { Image(systemName: copied ? "checkmark" : "ellipsis.circle").contentTransition(.symbolEffect(.replace)).tapTarget() }
            .accessibilityLabel(L("cd_more_options")).accessibilityHint(copyLabel)
            .task(id: copied) { if copied { try? await Task.sleep(nanoseconds: 1_500_000_000); withAnimation { copied = false } } }
    }
    private func copy() { onCopy(); withAnimation { copied = true } }
}

/// The grouped-list footer under a card: a secondary footnote, or an orange warning with its triangle.
struct CardFooter: View {
    let text: String
    var warning = false
    var body: some View {
        Group {
            if warning { Label(text, systemImage: "exclamationmark.triangle").voxFore(Vox.warning) }
            else { Text(text).foregroundStyle(.secondary) }
        }.font(.footnote).padding(.horizontal, Vox.inset).frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// SessionView's card container, shared by the extracted summary views.
@ViewBuilder func SessionCard<C: View>(@ViewBuilder _ c: () -> C) -> some View {
    VStack(alignment: .leading, spacing: 8) { c() }.padding(Vox.inset).frame(maxWidth: .infinity, alignment: .leading)
        .voxCard()
}

/// Talk-time shares per speaker, sorted loudest first. Pure value computation
/// factored out of SpeakerStats so the bar view below stays a tiny expression.
enum SpeakerStatsModel {
    static func shares(_ lines: [Utterance]) -> [(spk: Int, pct: Double)] {
        var d = [Int: Double]()
        for l in lines { d[max(0, l.speaker), default: 0] += max(0, l.end - l.start) }
        let total = d.values.reduce(0, +)
        return total > 0 ? d.map { ($0.key, $0.value / total * 100) }.sorted { $0.1 > $1.1 } : []
    }
}

/// The stacked talk-time bar: one segment per speaker in its colour.
struct SpeakerStatsBar: View {
    let shares: [(spk: Int, pct: Double)]
    let palette: [Color]
    var body: some View {
        GeometryReader { g in
            HStack(spacing: 0) { ForEach(shares, id: \.spk) { x in SpeakerStatsSegment(spk: x.spk, pct: x.pct, palette: palette, width: g.size.width) } }
                .clipShape(Capsule())
        }.frame(height: 8)
    }
}

/// One segment of the stacked talk-time bar.
struct SpeakerStatsSegment: View {
    let spk: Int
    let pct: Double
    let palette: [Color]
    let width: CGFloat
    var body: some View {
        palette[spk % palette.count].frame(width: width * pct / 100)
    }
}

/// The per-speaker legend under the talk-time bar.
struct SpeakerStatsLegend: View {
    let shares: [(spk: Int, pct: Double)]
    let names: [String: String]?
    let palette: [Color]
    var body: some View {
        HStack(spacing: 14) {
            ForEach(shares, id: \.spk) { x in
                SpeakerStatsLegendRow(spk: x.spk, pct: x.pct, names: names, palette: palette)
            }
        }
    }
}

/// One legend row: colour chip plus "name · pct%".
struct SpeakerStatsLegendRow: View {
    let spk: Int
    let pct: Double
    let names: [String: String]?
    let palette: [Color]
    var body: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: Vox.radiusXS).fill(palette[spk % palette.count]).frame(width: 8, height: 8)
            Vox.meta("\(SessionView.nameOf(spk, names)) · \(Int(pct.rounded()))%")
        }
    }
}
