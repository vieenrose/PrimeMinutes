import AppIntents

/// The App Shortcut behind the Siri phrase and the Shortcuts / Spotlight entry (phrases localized in AppShortcuts.strings).
struct PrimeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartRecordingIntent(), phrases: ["Record with \(.applicationName)", "Start recording with \(.applicationName)", "Record a meeting with \(.applicationName)"],
                    shortTitle: "rec_shortcut_short", systemImageName: "record.circle")
    }
    static let shortcutTileColor: ShortcutTileColor = .red
}
