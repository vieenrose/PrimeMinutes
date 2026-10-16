import SwiftUI

/// What the screen shows of the meeting being recorded or processed (capture view, processing banner, live panel),
/// and the status line every part of the app writes to.
@MainActor final class MeetingScreen: ObservableObject {
    @Published var lines: [Utterance] = []
    @Published var notes: [Note] = []
    let agent = AgentUi()
    @Published var title = ""
    @Published var summary = ""
    @Published var status = L("app_ready") { didSet { StatusLog.add(status) } }
    /// A new recording or run starts from an empty page.
    func clear() { lines = []; notes = []; title = ""; summary = "" }
}
