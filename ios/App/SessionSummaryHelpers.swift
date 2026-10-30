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
    let notesSection: (String, [String]) -> AnyView
    var body: some View {
        VStack(spacing: 12) {
            // Redesign: one AI status line (看過程 opens the 筆記 segment) instead of the agent card.
            if !session.notes.isEmpty {
                Button { onToggleProcess() } label: {
                    HStack(spacing: 8) {
                        Circle().fill(Vox.success.color).frame(width: 7, height: 7).accessibilityHidden(true)
                        Text(L("ai_status_read", session.notes.count)).font(Vox.fontMeta).foregroundStyle(Vox.ink2).lineLimit(2)
                        Spacer(minLength: 4)
                        Text(L("see_process")).font(Vox.fontGroup).foregroundStyle(Vox.accent)
                    }.frame(minHeight: 44).contentShape(Rectangle())
                }.buttonStyle(.plain)
                .accessibilityElement(children: .combine).accessibilityHint(L("see_process"))
                if Dev.on, showProcess, let g = session.readerGuard { Text("DEV guard · " + g.summary).font(.caption2.monospaced()).foregroundStyle(g.exceeds(ModelCard.default.reader.guardrail) ? Vox.danger.color : Color.secondary) }
            }
            if !session.summary.isEmpty { VStack(alignment: .leading, spacing: 10) {
                // No card, no heading: the prose sits on the page; citations are pills (seek + play). Copy / Edit are in ⋯
                // and on long press.
                Collapsible(lines: 14) { Text(Markdown.render(refs(session.summary), anchors: playerAvailable ? Vox.accent : nil, speakers: names)).font(Vox.fontBody).lineSpacing(6).foregroundStyle(Vox.ink) }.environment(\.openURL, OpenURLAction { u in
                    if u.scheme == "vox", let t = Double(u.host ?? "") { onSeekPlay(t); return .handled }
                    return .systemAction })
                    .contextMenu { CardActionItems(onCopy: onCopySummary, onEdit: onEditSummary) }
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(L("ai_disclaimer")).font(Vox.fontMeta).foregroundStyle(Vox.ink3)
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

/// The 筆記 segment (redesign): the reader's notes grouped by type, one line each; the time on the left plays from
/// there; 核對 only on the error-prone types (decision, action, number).
struct SessionNotesTab: View {
    let notes: [Note]
    let playerAvailable: Bool
    let onSeekPlay: (Double) -> Void
    @Environment(\.speakerRefs) private var refs
    static func kind(_ n: Note) -> String {
        let t = (n.tag ?? "").uppercased()
        return ["DECISION", "ACTION", "PROPOSAL", "OPEN-ISSUE", "NUMBER"].first { !t.isEmpty && $0.hasPrefix(t) } ?? "OTHER"
    }
    static func color(_ k: String) -> Color {
        switch k { case "DECISION": return Vox.noteDecision; case "ACTION": return Vox.noteAction; case "PROPOSAL": return Vox.noteProposal
        case "OPEN-ISSUE": return Vox.noteOpen; case "NUMBER": return Vox.noteNumber; default: return Vox.ink2 }
    }
    static func label(_ k: String) -> String {
        switch k { case "DECISION": return L("agent_tag_decision"); case "ACTION": return L("agent_tag_action"); case "OPEN-ISSUE": return L("agent_tag_open")
        case "NUMBER": return L("agent_tag_number"); case "PROPOSAL": return L("agent_tag_proposal"); default: return L("notes_other") }
    }
    var body: some View {
        let order = ["DECISION", "ACTION", "PROPOSAL", "OPEN-ISSUE", "NUMBER", "OTHER"]
        let groups = Dictionary(grouping: notes, by: Self.kind)
        LazyVStack(alignment: .leading, spacing: 0) {
            if notes.isEmpty { Vox.hint(L("notes_none")).padding(.top, 12) }
            ForEach(order.filter { groups[$0] != nil }, id: \.self) { k in
                HStack(spacing: 6) {
                    Text(Self.label(k)).font(Vox.fontPill).foregroundStyle(Self.color(k))
                        .padding(.horizontal, 7).padding(.vertical, 3).background(Self.color(k).opacity(0.12), in: RoundedRectangle(cornerRadius: Vox.radiusTag))
                    Text("\(groups[k]!.count)").font(Vox.fontMeta).foregroundStyle(Vox.ink3)
                }.padding(.top, 16).padding(.bottom, 6).accessibilityAddTraits(.isHeader)
                ForEach(groups[k]!) { n in row(n, k); Vox.hairline.frame(height: 1) }
            }
        }.padding(.horizontal, Vox.gutter)
    }
    private func row(_ n: Note, _ k: String) -> some View {
        let sec = ReaderProtocol.parseTs(n.ts)
        return HStack(alignment: .firstTextBaseline, spacing: 10) {
            Button { if let sec { onSeekPlay(Double(sec)) } } label: {
                Text(n.ts).font(.footnote.monospacedDigit()).foregroundStyle(playerAvailable ? Vox.accent : Vox.ink3).frame(minWidth: 44, minHeight: 36, alignment: .leading)
            }.buttonStyle(.plain).disabled(sec == nil || !playerAvailable).accessibilityLabel(L("cd_play_from", n.ts))
            Text(refs(n.text)).font(.subheadline).foregroundStyle(Vox.ink).frame(maxWidth: .infinity, alignment: .leading)
            if ["DECISION", "ACTION", "NUMBER"].contains(k), let sec, playerAvailable {
                Button { onSeekPlay(Double(sec)) } label: { Text(L("agent_verify")).font(Vox.fontPill).voxFore(Vox.warning).padding(.horizontal, 7).padding(.vertical, 3)
                    .background(Vox.warning.color.opacity(0.12), in: Capsule()).tapHeight() }.buttonStyle(.plain)
            }
        }.padding(.vertical, 6)
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
