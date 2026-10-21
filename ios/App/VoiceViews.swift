import SwiftUI

// Voice recognition's screens (Phase 2, Android VoiceSuggestions.kt): the suggestions of the rename sheet, the
// "Recognise voices" card of the People screen, "This is me", a person's voice row and the outlier prompt.

/// The voice suggestions for `speaker` of `session` and the note shown when there are none (docs/design/speaker-tags.md
/// §2.6), handed to `content`: the meeting's speakers against every person's confirmed voice, assigned jointly, people
/// already named in this meeting left out. Nothing when recognition is off.
struct VoiceSuggested<Content: View>: View {
    let session: Session
    let speaker: Int
    @ViewBuilder let content: ([VoiceSuggestion], String?) -> Content
    @ObservedObject private var voice = VoiceStore.shared
    @ObservedObject private var people = PeopleStore.shared
    @State private var ranked: ([VoiceSuggestion], Int)?
    private struct Key: Hashable { let speaker: Int, version: Int, on: Bool, links: [String: String]?, people: Int }

    var body: some View {
        content(people.doc.voice ? ranked?.0 ?? [] : [], note)
            .task(id: Key(speaker: speaker, version: voice.version, on: people.doc.voice, links: session.speakerPeople, people: people.doc.people.count)) {
                ranked = people.doc.voice ? voice.rank(session, speaker: speaker) : nil
            }
    }
    private var note: String? {
        guard people.doc.voice, let r = ranked else { return nil }
        if r.1 == 1 { return L("voice_note_short") }
        if r.1 == 2 || voice.working.contains(session.id) { return L("voice_note_working") }
        if r.1 == 3 { return L("voice_note_none_yet") }
        return r.0.isEmpty ? L("voice_note_no_match") : nil
    }
}

/// The "Recognise voices" switch, its explanation and the "Me" enrolment, at the top of the People screen.
struct VoiceSettingsCard: View {
    @ObservedObject private var voice = VoiceStore.shared
    @ObservedObject private var people = PeopleStore.shared
    @State private var explain = false
    @State private var deleteAsk = false
    @State private var failed = false
    @State private var enrol = false

    var body: some View {
        let on = people.doc.voice
        let known = voice.known()
        Section {
            Toggle(isOn: Binding(get: { on }, set: { v in if v { explain = true } else { voice.disable(deleteData: false) } })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("voice_title")).fontWeight(.semibold)
                    Text(L(on ? "voice_on_desc" : "voice_off_desc")).font(.footnote).foregroundStyle(.secondary)
                }
            }.disabled(voice.download != nil)
            if let d = voice.download {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("voice_downloading")).font(.footnote).foregroundStyle(.secondary)
                    ProgressView(value: d)
                }
            }
            if failed { Text(L("voice_download_failed")).font(.footnote).foregroundStyle(.red) }
            if on {
                Text(L(known.count == 1 ? "voice_known_one" : "voice_known_other", known.count)).font(.footnote).foregroundStyle(.secondary)
                if !voice.working.isEmpty { Text(L("voice_note_working")).font(.footnote).foregroundStyle(.secondary) }
                Button(L(people.doc.people.contains { $0.me && known[$0.id] != nil } ? "voice_me_again" : "voice_me")) { enrol = true }
                Button(L("voice_delete_all"), role: .destructive) { deleteAsk = true }
            }
        }
        .alert(L("voice_explain_title"), isPresented: $explain) {
            Button(L("voice_enable")) { failed = false; Task { failed = !(await voice.enable()) } }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("voice_explain_text", Int(VoiceStore.card.totalBytes / 1_000_000))) }
        .alert(L("voice_delete_all"), isPresented: $deleteAsk) {
            Button(L("people_delete"), role: .destructive) { voice.deleteAll() }
            Button(L("cancel"), role: .cancel) {}
        } message: { Text(L("voice_delete_all_text")) }
        .sheet(isPresented: $enrol) { MeEnrolSheet { enrol = false }.presentationDetents([.medium, .large]).presentationDragIndicator(.visible) }
        .task {   // DEV: VOX_VOICEME opens "This is me" (with VOX_MICFILE standing in for the mic)
            if Dev.env["VOX_VOICEME"] != nil, people.doc.voice { try? await Task.sleep(nanoseconds: 800_000_000); enrol = true }
        }
    }
}

/// "This is me": ~20 s of the owner reading aloud, kept only as a voice vector (the audio is not stored).
private struct MeEnrolSheet: View {
    let onDone: () -> Void
    static let seconds = 20
    /// 0 ready, 1 recording, 2 computing, 3 done, -1 failed, -2 no microphone permission.
    @State private var phase = 0
    @State private var progress = 0.0

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L("voice_me")).font(.headline)
            Text(L(phase == 0 ? "voice_me_text" : phase == 1 ? "voice_me_recording" : phase == 2 ? "voice_me_computing" : phase == 3 ? "voice_me_done"
                   : phase == -2 ? "voice_me_permission" : "voice_me_failed"))
            if phase == 1 { ProgressView(value: progress) }
            if phase == 2 { ProgressView() }
            if phase == 0 || phase == 1 {
                Text(L("voice_me_script")).font(.callout).padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
            }
            Spacer(minLength: 0)
            HStack {
                if phase == 0 { Button(L("cancel"), action: onDone) }
                Spacer()
                if phase == 0 { Button(L("voice_me_start")) { start() }.bold() }
                if phase == 3 || phase < 0 { Button(L("people_save"), action: onDone).bold() }
            }
        }
        .padding(20)
        .interactiveDismissDisabled(phase == 1 || phase == 2)
        .task {   // DEV: VOX_VOICEME=auto starts the recording
            if Dev.env["VOX_VOICEME"] == "auto" { try? await Task.sleep(nanoseconds: 1_500_000_000); start() }
        }
    }

    private func start() {
        guard phase == 0 else { return }
        Task {
            guard await Recorder.requestPermission() else { phase = -2; return }
            phase = 1
            let pcm = await Self.record { p in progress = p }
            phase = 2
            let me = pcm.isEmpty ? nil : await VoiceStore.shared.enrolMe(pcm, name: L("voice_me_name"))
            phase = me != nil ? 3 : -1
        }
    }

    /// `seconds` of the microphone at 16 kHz (the shared Recorder: VOX_MICFILE stands in for it in test builds).
    @MainActor static func record(_ onProgress: @escaping @MainActor (Double) -> Void) async -> [Float] {
        let want = seconds * 16_000
        final class Buf: @unchecked Sendable { let lock = NSLock(); var pcm: [Float] = [] }
        let buf = Buf(), rec = Recorder()
        do { try rec.start { c in buf.lock.withLock { buf.pcm += c } } } catch { StatusLog.add("voice: me recording failed: \(error)"); return [] }
        let t0 = Date()
        while true {
            let n = buf.lock.withLock { buf.pcm.count }
            onProgress(min(1, Double(n) / Double(want)))
            if n >= want || Date().timeIntervalSince(t0) > Double(seconds + 5) { break }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        rec.stop()
        return buf.lock.withLock { Array(buf.pcm.prefix(want)) }
    }
}

/// What the app knows of `person`'s voice: learned from N meetings, "learn this voice" on or off, "Forget voice".
struct PersonVoiceRow: View {
    let person: Person
    @ObservedObject private var voice = VoiceStore.shared
    @ObservedObject private var people = PeopleStore.shared
    @State private var forgetAsk = false

    var body: some View {
        if people.doc.voice {
            let info = voice.known()[person.id]
            let pending = voice.pendingOutliers(person.id).count
            Section {
                Toggle(isOn: Binding(get: { person.voiceEnabled }, set: { voice.setLearning(person.id, $0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("voice_person_title")).fontWeight(.semibold)
                        Text(info.map { L($0.meetings == 1 ? "voice_person_learned_one" : "voice_person_learned_other", $0.meetings, Int($0.seconds)) } ?? L("voice_person_none"))
                            .font(.footnote).foregroundStyle(.secondary)
                        if pending > 0 { Text(L(pending == 1 ? "voice_person_pending_one" : "voice_person_pending_other", pending)).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
                if info != nil { Button(L("voice_forget"), role: .destructive) { forgetAsk = true } }
            }
            .alert(L("voice_forget"), isPresented: $forgetAsk) {
                Button(L("voice_forget_yes"), role: .destructive) { voice.forget(person.id) }
                Button(L("cancel"), role: .cancel) {}
            } message: { Text(L("voice_forget_text", person.name)) }
        }
    }
}

/// "This doesn't sound like Mark's other meetings": learn anyway, or keep the name only. Shown a moment after the
/// outlier is found: the rename sheet that confirmed the name is still closing then, and SwiftUI drops an alert
/// presented during that dismissal.
struct VoiceOutlierPrompt: ViewModifier {
    @ObservedObject private var voice = VoiceStore.shared
    @ObservedObject private var people = PeopleStore.shared
    @State private var shown: VoiceStore.Outlier?
    func body(content: Content) -> some View {
        let name = shown.flatMap { people.doc.byId($0.person)?.name } ?? ""
        content
            .task(id: voice.outlier) {
                guard voice.outlier != nil else { shown = nil; return }
                try? await Task.sleep(nanoseconds: 800_000_000)
                shown = voice.outlier
            }
            .alert(L("voice_outlier_title", name), isPresented: Binding(get: { shown != nil && !name.isEmpty }, set: { if !$0 { shown = nil; voice.outlier = nil } })) {
                Button(L("voice_outlier_learn")) { if let o = shown { voice.learnAnyway(o.person, meeting: o.session.uuidString) }; shown = nil; voice.outlier = nil }
                Button(L("voice_outlier_keep"), role: .cancel) { shown = nil; voice.outlier = nil }
            } message: { Text(L("voice_outlier_text", name)) }
    }
}
