import SwiftUI

struct SettingsView: View {
    @ObservedObject var m: Model
    private var inferenceFooter: String {
        let capped: String = Prefs.capped ? "\n" + L("settings_inference_capped") : ""
        let failed: String = benchFailed ? "\n" + L("settings_inference_failed") : ""
        return L("threads_note", Prefs.effectiveThreads, Prefs.cores) + capped + failed + "\n" + L("settings_inference_hint")
    }
    @Binding var language: String
    @Binding var theme: String
    @State private var models = Storage.models()
    @State private var toDelete: Storage.Item?
    @State private var threads = Prefs.threads
    @State private var benching = false
    @State private var benchTick = 0
    @State private var benchFailed = false
    @AppStorage("hwMonitor") private var hwMonitor = true
    @State private var reader = Prefs.readerId
    @State private var delay = Prefs.speakerDelay
    @State private var asrIndex = Double(AsrRegistry.latencies.firstIndex(of: Prefs.asrLatency) ?? 3)
    @AppStorage("textSize") private var textSize = 0
    @AppStorage("showActions") private var showActions = false
    @AppStorage("askOn") private var askEnabled = true
    @ObservedObject private var indexer = LibraryIndexer.shared
    @Environment(\.dismiss) private var dismiss
    /// Opens a meeting at a time (the People screen's meeting rows).
    var openMeeting: (Session, Double) -> Void = { _, _ in }
    @ObservedObject private var people = PeopleStore.shared
    @State private var showPeople = Dev.env["VOX_PEOPLE"] != nil
    /// A recording or job is running: the engine settings it reads stay put until it ends (Android `enabled = !s.running`).
    private var locked: Bool { m.runner.activeJob != nil || m.capture.recording }
    var body: some View {
        NavigationStack { ScrollViewReader { proxy in
            Form {
                ModelDownloadSection(downloads: m.downloads)   // the first-use download in progress, with Cancel
                if locked { Section { Label(L("settings_locked_running"), systemImage: "lock").foregroundStyle(.secondary) } }
                Section {
                    Picker(L("language"), selection: $language) { ForEach(AppLanguage.allCases) { Text($0.autonym).tag($0.rawValue) } }
                } header: { Text(L("settings_language")) } footer: { Text(L("settings_language_hint")) }
                Section { Picker(L("theme"), selection: $theme) { ForEach(Theme.allCases) { Text($0.label).tag($0.rawValue) } }.pickerStyle(.segmented) }
                    header: { Text(L("settings_appearance")) } footer: { Text(L("theme_eink_hint")) }
                Section {
                    Picker(L("text_size"), selection: $textSize) { ForEach(0..<Prefs.textSizeLabels.count, id: \.self) { Text(L(Prefs.textSizeLabels[$0])).tag($0) } }
                } header: { Text(L("text_size")) } footer: { Text(L("settings_font_size_hint")) }
                Section {
                    Stepper(value: $delay, in: 5...30, step: 5) { HStack { Text(L("settings_speaker_delay")); Spacer(); Text(L("settings_seconds", delay)).foregroundStyle(.secondary) } }
                        .onChange(of: delay) { Prefs.speakerDelay = delay }
                } header: { Text(L("settings_recording")) } footer: { Text(L("settings_speaker_delay_hint")) }
                // Recognizer latency (Android AsrLatencySlider): 4 stops, a new choice is fetched in the background.
                Section {
                    VStack(alignment: .leading) {
                        let ms = AsrRegistry.latencies[Int(asrIndex)]
                        HStack { Text(L("settings_asr_latency")); Spacer()
                            Text(L("settings_asr_latency_value", String(format: "%g", Double(ms) / 1000), L(["settings_asr_latency_fastest", "settings_asr_latency_fast", "settings_asr_latency_balanced", "settings_asr_latency_accurate"][Int(asrIndex)])))
                                .foregroundStyle(.secondary) }
                        Slider(value: $asrIndex, in: 0...Double(AsrRegistry.latencies.count - 1), step: 1) { editing in
                            guard !editing else { return }
                            Prefs.asrLatency = AsrRegistry.latencies[Int(asrIndex)]; m.downloads.maybePrefetchSpeech()
                        }.accessibilityIdentifier("settings-asr-latency").disabled(locked)
                        // A disabled Slider keeps most of its tint; grey it so the lock is obvious.
                        .opacity(locked ? 0.4 : 1)
                        // VoiceOver read the raw stop index ("0", "1"...); name the slider and speak the chosen stop.
                        .accessibilityLabel(L("settings_asr_latency"))
                        .accessibilityValue(L(["settings_asr_latency_fastest", "settings_asr_latency_fast", "settings_asr_latency_balanced", "settings_asr_latency_accurate"][Int(asrIndex)]))
                    }
                } footer: { Text(L("settings_asr_latency_hint")) }
                // The people directory (names shared by all meetings; voice recognition will live there too).
                Section(L("people_title")) {
                    NavigationLink { PeopleScreen(library: m.library, openMeeting: openMeeting) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("people_settings_open", people.doc.people.count))
                            Text(L("people_settings_desc")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    Picker(L("notes_model"), selection: $reader) {
                        Text(L("reader_model_e2b") + " · 2.2 GB").tag("E2B")
                        if Prefs.e4bAllowed { Text(L("reader_model_e4b") + " · 3.3 GB").tag("E4B") }
                    }.pickerStyle(.inline).labelsHidden()
                    .onChange(of: reader) { Prefs.readerId = reader }
                    .disabled(locked)
                    .id("readerModel")
                } header: { Text(L("settings_reader_model")) } footer: {
                    Text(L("reader_model_hint", SizeFormat.bytes(ReaderModel.e2b.totalBytes), SizeFormat.bytes(ReaderModel.e4bV2.totalBytes)) + (Prefs.e4bAllowed ? "" : "\n" + L("reader_model_e4b_ram")))
                }
                Section { Toggle(L("settings_hw_monitor"), isOn: $hwMonitor) } footer: { Text(L("settings_hw_monitor_hint")) }
                Section {
                    Group {
                    Toggle(L("threads_auto"), isOn: Binding(get: { threads == 0 }, set: { threads = $0 ? 0 : Prefs.effectiveThreads; Prefs.threads = threads }))
                    if threads > 0 {
                        Stepper(L("threads_n", threads), value: Binding(get: { threads }, set: { threads = $0; Prefs.threads = $0 }), in: 2...max(2, Prefs.cores))
                    }
                    }.disabled(locked)
                    Button(L(benching ? "settings_inference_running" : "settings_inference_run")) {
                        let previous = threads
                        benching = true; benchFailed = false; threads = 0; Prefs.threads = 0
                        Task {
                            // No pick (benchmark failed): keep the user's previous choice and say so instead of silently going Auto.
                            if await Prefs.runBench() == nil { threads = previous; Prefs.threads = previous; benchFailed = true }
                            benchTick += 1; benching = false
                        }
                    }.disabled(benching || locked)
                } header: { Text(L("settings_inference")) } footer: { Text(inferenceFooter).id(benchTick) }
                Section {
                    if !EmbedHost.ready {
                        if let p = m.downloads.embedProgress { Text(L("search_semantic_downloading", p)).foregroundStyle(.secondary) }
                        else { Button(L("settings_search_model_get", SizeFormat.bytes(ReaderModel.embed.files.reduce(0) { $0 + $1.size }))) { m.downloads.downloadEmbed() } }
                    } else {
                        let stale = LibraryIndexer.stale(m.library.sessions).count
                        if indexer.pending > 0 || !indexer.indexing.isEmpty { Text(L("settings_index_pending", indexer.pending + indexer.indexing.count)).foregroundStyle(.secondary) }
                        else if stale == 0 { Text(L("settings_index_all_done")).foregroundStyle(.secondary) }
                        else { Button(L("settings_index_old") + " (\(stale))") { indexer.catchUp(m.library.sessions) } }
                    }
                } header: { Text(L("model_kind_embed")) } footer: { Text(L("settings_search_hint")) }
                Section {
                    Toggle(L("settings_ask"), isOn: $askEnabled)
                } header: { Text(L("ask_title")) } footer: { Text(L("settings_ask_hint")) }
                Section {
                    Toggle(L("settings_show_actions"), isOn: $showActions)
                } header: { Text(L("settings_experimental")) } footer: { Text(L("settings_show_actions_hint")) }
                Section {
                    if !Storage.ready(Prefs.readerTarget) { Text(L("storage_model_pending", SizeFormat.bytes(Prefs.readerTarget.totalBytes))).font(.footnote).foregroundStyle(.secondary) }
                    if models.isEmpty { Text(L("storage_none")).foregroundStyle(.secondary) }
                    ForEach(models) { i in
                        HStack { VStack(alignment: .leading) { Text(i.label); Text(L(i.kind)).font(.caption2).foregroundStyle(.secondary)
                            if i.cacheBytes > 0 { Text(L("storage_compile_cache", SizeFormat.bytes(i.cacheBytes))).font(.caption2).foregroundStyle(.secondary) } }; Spacer(); Text(SizeFormat.bytes(i.bytes)).foregroundStyle(.secondary) }
                            .swipeActions { if m.runner.activeJob == nil && !m.capture.recording { Button(L("storage_delete"), role: .destructive) { toDelete = i } } }   // not under a run: its models are mmaped
                    }
                } header: { Text(L("settings_storage")) } footer: { if !models.isEmpty { Text(L("storage_total", SizeFormat.bytes(models.reduce(0) { $0 + $1.bytes }))) } }
                Section(L("settings_about")) {
                    HStack { Text("Prime Minutes"); Spacer(); Text(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "").foregroundStyle(.secondary) }
                    Text(L("about_license"))
                    DisclosureGroup(L("about_components")) {
                        ForEach(["lic_nemo", "lic_crispasr", "lic_audiocpp", "lic_ggml", "lic_xasr", "lic_nemotron", "lic_opencc"], id: \.self) { Text(L($0)).font(.footnote) }
                    }
                }
            }
            // DEV (guide shot 03b): VOX_SETSCROLL scrolls to the AI notes model picker.
            .onAppear { if Dev.env["VOX_SETSCROLL"] != nil { DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { proxy.scrollTo("readerModel", anchor: .center) } } }
            }
            .confirmationDialog(L("storage_delete_title"), isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } }), titleVisibility: .visible) {
                Button(L("storage_delete"), role: .destructive) { if let i = toDelete { Storage.delete(i); models = Storage.models() } }
            } message: { if let i = toDelete { Text(L("storage_delete_body", i.label, SizeFormat.bytes(i.bytes))) } }
            .navigationDestination(isPresented: $showPeople) { PeopleScreen(library: m.library, openMeeting: openMeeting) }
            .navigationTitle(L("settings"))
            .toolbar { Button(L("done")) { dismiss() } }
        }
    }
}
