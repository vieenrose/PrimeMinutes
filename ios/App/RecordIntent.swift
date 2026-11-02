import AppIntents
import Foundation

/// "Record with Prime Minutes": opens the app already recording (Siri, Shortcuts, Spotlight, the Action button and
/// the Control Center control). Compiled into the app AND the widget extension: the control needs the type, but
/// with `openAppWhenRun` the system always runs `perform` in the app, which presses Record like the in-app button.
struct StartRecordingIntent: AppIntent {
    static let title: LocalizedStringResource = "rec_intent_title"
    static let description = IntentDescription("rec_intent_desc")
    static let openAppWhenRun: Bool = true
    @MainActor func perform() async throws -> some IntentResult { RecordEntry.request(); return .result() }
}

/// A record request from outside the app (intent or the widget's primeminutes://record link). Cold launch: the
/// request can arrive before the library screen exists, so it waits in `pending` until the screen takes it.
@MainActor enum RecordEntry {
    static let url = URL(string: "primeminutes://record")!
    static var pending = false
    static let notification = Notification.Name("tw.com.pesi.primeminutes.record")
    static func request() { pending = true; NotificationCenter.default.post(name: notification, object: nil) }
    static func isRecordURL(_ u: URL) -> Bool { u.scheme == url.scheme && u.host == url.host }
    /// True once per request.
    static func take() -> Bool { defer { pending = false }; return pending }
}
