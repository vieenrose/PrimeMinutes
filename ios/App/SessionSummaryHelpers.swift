import SwiftUI
import UIKit

/// SessionView summary-tab views, extracted verbatim from SessionView to keep the
/// whole-module solver under its ceiling. Same-module structs taking plain values
/// and closures; SessionView keeps thin forwarders with identical parameters.
struct SessionSummaryTab: View {
    let session: Session
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
            if !session.notes.isEmpty { SessionCard {
                HStack {
                    Circle().fill(Vox.success).frame(width: 9, height: 9)
                    VStack(alignment: .leading) {
                        Vox.head(L("ai_notes"))
                        Vox.meta(L("notes_n", session.notes.count))
                    }
                    Spacer()
                    Text(L("done_pill")).font(.caption.weight(.semibold)).padding(.horizontal, 10).padding(.vertical, 4)
                        .voxChip(Vox.success).foregroundStyle(Vox.success)
                }
                // Android AgentPanel: "show process" under the header; opened, the notes, the caution, then "hide process".
                if showProcess {
                    ForEach(session.notes) { n in NoteRow(n: n) { sec in onSeekPlay(Double(sec)) } }
                    Vox.micro(L("agent_notes_caution"))
                    // DEV builds: the saved reading's off-protocol counts (model guardrail).
                    if Dev.on, let g = session.readerGuard { Text("DEV guard · " + g.summary).font(.caption2.monospaced()).foregroundStyle(g.exceeds(ModelCard.default.reader.guardrail) ? Vox.danger : Color.secondary) }
                }
                Button(L(showProcess ? "hide_process" : "show_process")) { onToggleProcess() }.font(.subheadline)
            } }
            if !session.summary.isEmpty {
                SessionCard {
                    // No heading: the tab already carries the name (Android SummaryCard showHeading = false in portrait).
                    HStack(spacing: 16) { Spacer()
                        Button { onCopySummary() } label: { Image(systemName: "doc.on.doc").tapTarget() }.accessibilityLabel(L("cd_copy_summary"))
                        Button { onEditSummary() } label: { Image(systemName: "pencil").tapTarget() }.accessibilityLabel(L("cd_edit")) }
                    Collapsible(lines: 12) { Text(Markdown.render(refs(session.summary), anchors: playerAvailable ? Color.secondary : nil, speakers: names)) }.environment(\.openURL, OpenURLAction { u in
                        if u.scheme == "vox", let t = Double(u.host ?? "") { onSeekPlay(t); return .handled }
                        return .systemAction })
                    Vox.micro(L("ai_disclaimer"))
                }
            }
            if showActions, let a = session.actionItems {
                SessionCard {
                    HStack(spacing: 16) {
                        Text(L("card_action_items")).font(.headline); Spacer()
                        Button { onCopyActions() } label: { Image(systemName: "doc.on.doc").tapTarget() }.accessibilityLabel(L("cd_copy_actions"))
                        Button { onEditActions() } label: { Image(systemName: "pencil").tapTarget() }.accessibilityLabel(L("cd_edit"))
                    }
                    Collapsible(lines: 8) { Text(Markdown.render(refs(a), anchors: playerAvailable ? Color.secondary : nil, speakers: names)) }.environment(\.openURL, OpenURLAction { u in
                        if u.scheme == "vox", let t = Double(u.host ?? "") { onSeekPlay(t); return .handled }
                        return .systemAction })
                    Vox.micro(L("actions_verify_hint")).foregroundStyle(Vox.warning)
                }
            }
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
            if session.summary.isEmpty { Vox.hint(L(session.lines.isEmpty ? "status_no_speech" : "summary_missing_hint")).padding(.top, 12) }
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
                    Text(Markdown.render("- " + refs(it), anchors: playerAvailable ? Vox.info : nil, speakers: names)).font(.subheadline)
                }
            }.environment(\.openURL, OpenURLAction { u in
                if u.scheme == "vox", let t = Double(u.host ?? "") { onSeekPlay(t); return .handled }
                return .systemAction })
        }
    }
}

/// SessionView's card container, shared by the extracted summary views.
@ViewBuilder func SessionCard<C: View>(@ViewBuilder _ c: () -> C) -> some View {
    VStack(alignment: .leading, spacing: 8) { c() }.padding(Vox.inset).frame(maxWidth: .infinity, alignment: .leading)
        .voxCard()
}
