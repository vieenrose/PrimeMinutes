import SwiftUI

/// The unfolded search-results list: notes group, transcript group, "more" hint,
/// and the Ask-this-meeting button. Takes plain values so the solver sees a tiny view.
struct SessionResultsList: View {
    let results: [MeetingSearch.Result]
    let query: String
    let askMeetingReady: Bool
    let name: (Int) -> String
    let pick: (MeetingSearch.Result) -> Void
    let askMeeting: () -> Void
    var body: some View {
        let notes = results.filter { $0.kind == .note }, lines = results.filter { $0.kind == .line }
        return ScrollView {
            // A VStack (≤ 50 rows): a LazyVStack nested in the page's own scroll view left rows blank.
            VStack(alignment: .leading, spacing: 0) {
                if !notes.isEmpty { SessionResultsHeader(title: L("ai_notes"), count: notes.count); ForEach(Array(notes.enumerated()), id: \.offset) { _, r in SessionResultRow(result: r, query: query, name: name, pick: pick) } }
                if !lines.isEmpty { SessionResultsHeader(title: L("search_results_transcript"), count: lines.count); ForEach(Array(lines.enumerated()), id: \.offset) { _, r in SessionResultRow(result: r, query: query, name: name, pick: pick) } }
                if results.count >= MeetingSearch.maxResults { Vox.micro(L("search_results_more", MeetingSearch.maxResults)).padding(.horizontal, Vox.inset).padding(.vertical, 4) }
                if askMeetingReady {
                    Button { askMeeting() } label: {
                        Label(L("ask_meeting_query", query.trimmingCharacters(in: .whitespaces)), systemImage: "sparkles").font(.subheadline.weight(.semibold)).lineLimit(1)
                    }.buttonStyle(.plain).foregroundStyle(Color.accentColor).padding(.horizontal, Vox.inset).padding(.vertical, Vox.insetTight).accessibilityIdentifier("results-ask-meeting")
                }
            }
        }.frame(maxHeight: 280).voxRow()
        .accessibilityIdentifier("meeting-search-results")
    }
}

/// One header line over a results group.
struct SessionResultsHeader: View {
    let title: String
    let count: Int
    var body: some View {
        Text("\(title) · \(count)").font(.caption.weight(.bold)).foregroundStyle(.secondary).padding(.horizontal, Vox.inset).padding(.top, 8).padding(.bottom, 2)
    }
}

/// One tappable result row: time pill, ≈ marker, speaker, marked snippet.
struct SessionResultRow: View {
    let result: MeetingSearch.Result
    let query: String
    let name: (Int) -> String
    let pick: (MeetingSearch.Result) -> Void
    var body: some View {
        Button { pick(result) } label: {
            HStack(alignment: .top, spacing: 6) {
                Text(Export.mmss(result.timeSec)).font(.caption.monospacedDigit()).foregroundStyle(Color.accentColor).fixedSize().frame(minWidth: 48, alignment: .leading)   // a fixed 48 pt clipped the time at large text sizes
                if result.semanticOnly { Text("≈").font(.caption.bold()).foregroundStyle(Color.accentColor).accessibilityLabel(L("search_semantic_marker")) }
                (result.speaker.map { Text(name($0) + "  ").font(.caption.weight(.semibold)).foregroundColor(.secondary) } ?? Text(""))
                    + Text(SessionView.marked(MeetingSearch.snippet(result.text, query), query)).font(.caption)
            }.lineLimit(2).multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, Vox.inset).padding(.vertical, 6).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}
