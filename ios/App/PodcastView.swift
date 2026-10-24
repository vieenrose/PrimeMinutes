import SwiftUI

struct PodcastView: View {
    let pick: (Episode) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var series: [PodcastSeries] = []
    @State private var episodes: [Episode] = []
    @State private var current: PodcastSeries?
    @State private var busy = false
    @State private var busyLabel = "dl_searching"
    @State private var error: String?
    @State private var searched = ""   // the query whose results are shown, for the no-result state
    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error).foregroundStyle(.red) }
                if current == nil {
                    ForEach(series) { s in
                        Button { open(s) } label: {
                            VStack(alignment: .leading) { Text(s.title).font(.headline); Text(s.artist).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                } else {
                    ForEach(episodes) { e in
                        Button { pick(e); dismiss() } label: {
                            VStack(alignment: .leading) {
                                Text(e.title)
                                Text(L("podcast_transcribe")).font(.caption2.weight(.semibold)).foregroundStyle(.blue)
                                Text([e.duration, String(e.published.prefix(16))].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if busy { HStack(spacing: 8) { ProgressView(); Text(L(busyLabel)).font(.footnote).foregroundStyle(.secondary) } }
            }
            .overlay {
                if !busy && error == nil {
                    if current == nil && series.isEmpty {
                        if searched.isEmpty {
                            ContentUnavailableView(L("podcast"), systemImage: "antenna.radiowaves.left.and.right", description: Text(L("podcast_empty_hint")))
                        } else { ContentUnavailableView(L("podcast_no_results"), systemImage: "magnifyingglass", description: Text(L("podcast_no_results_hint", searched))) }
                    } else if current != nil && episodes.isEmpty {
                        ContentUnavailableView(L("podcast_no_episodes"), systemImage: "list.bullet")
                    }
                }
            }
            .navigationTitle(current?.title ?? L("podcast"))
            .searchable(text: $query, prompt: L("podcast_search"))
            .onSubmit(of: .search) { search() }
            .toolbar {
                if current != nil { ToolbarItem(placement: .navigation) { Button(L("back")) { current = nil; episodes = [] } } }
                ToolbarItem { Button(L("done")) { dismiss() } }
            }
        }
    }
    private func search() {
        busy = true; busyLabel = "dl_searching"; error = nil; current = nil
        Task { defer { busy = false }; do { series = try await Podcast.search(query); searched = query } catch { self.error = L("status_error", error.localizedDescription) } }
    }
    private func open(_ s: PodcastSeries) {
        busy = true; busyLabel = "dl_loading_episodes"; error = nil; current = s
        Task { defer { busy = false }; do { episodes = try await Podcast.episodes(s.feedUrl) } catch { self.error = L("status_error", error.localizedDescription) } }
    }
}

/// A PeerTube video link (any instance): resolved to its smallest file, then queued like a podcast episode.
struct PeerTubeView: View {
    let pick: (Episode) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var link = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                TextField(L("source_peertube_desc"), text: $link).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .textContentType(.URL).submitLabel(.go)   // the key shows Go and offers the user's saved links
                    .onSubmit { go() }
                if let error { Text(error).foregroundStyle(.red) }
                if busy { HStack(spacing: 8) { ProgressView(); Text(L("dl_searching")).font(.footnote).foregroundStyle(.secondary) } }
            }
            .navigationTitle(L("source_peertube"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("peertube_add")) { go() }.disabled(busy || link.trimmingCharacters(in: .whitespaces).isEmpty) }
                ToolbarItem(placement: .cancellationAction) { Button(L("done")) { dismiss() } }
            }
        }
    }
    private func go() {
        guard !busy else { return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let v = try await PeerTube.resolve(link)
                pick(Episode(title: v.title, audioUrl: v.fileUrl, published: "", duration: Podcast.formatDuration(String(v.seconds))))
                dismiss()
            } catch { self.error = error.localizedDescription }
        }
    }
}
