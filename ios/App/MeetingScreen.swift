import SwiftUI

/// What the screen shows of the meeting being recorded or processed (capture view, processing banner, live panel),
/// and the status line every part of the app writes to.
@MainActor final class MeetingScreen: ObservableObject {
    @Published var lines: [Utterance] = []
    @Published var notes: [Note] = []
    /// Speaker names confirmed while this run is going (Android SessionState.speakerNames): rows read their label from
    /// here, so a rename reaches the lines already on screen in the same frame. The run folds them into the meeting.
    @Published var names: [Int: SpeakerLink] = [:]
    let agent = AgentUi()
    @Published var title = ""
    @Published var summary = ""
    @Published var status = L("app_ready") { didSet { StatusLog.add(status) } }
    /// A speaker's label as the live rows show it (Android speakerLabel): the confirmed name, else "Speaker n".
    func name(_ spk: Int) -> String { LiveNames.label(names, spk, numbered: { L("speaker_n", $0 + 1) }) }
    /// A new recording or run starts from an empty page.
    func clear() { lines = []; notes = []; title = ""; summary = ""; names = [:] }
}
