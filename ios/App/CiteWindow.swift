import Foundation

/// The citation transcript preview (CitePreviewPanel): which turn a cite points at, which lines the panel shows
/// around it, and when it closes by itself. Plain values, host-tested (tests/CiteWindowTests.swift).
enum CiteWindow {
    /// Lines the panel shows: one before the cited turn, the turn, three after (fewer near the ends).
    static let before = 1, after = 3
    /// Seconds after the cited turn ends before the panel closes by itself while playing.
    static let linger = 4.0

    /// The cited turn: the last one starting at or before `sec` (whole seconds, as ShareCore.target), else the first.
    static func turn(_ sec: Double, starts: [Double]) -> Int? {
        ShareCore.target(Int(sec.rounded(.down)), starts.map { Int($0.rounded(.down)) })
    }
    /// The lines around turn `i` of `count`: `before` + 1 + `after`, shifted inward at either end of the meeting.
    static func window(around i: Int, count: Int) -> Range<Int> {
        guard count > 0 else { return 0..<0 }
        let size = min(count, before + 1 + after), i = min(max(0, i), count - 1)
        let lo = min(max(0, i - before), count - size)
        return lo..<(lo + size)
    }
    /// The line under the playhead inside the panel (nil when playback left the window or never started).
    static func playing(_ time: Double, engaged: Bool, starts: [Double], window: Range<Int>) -> Int? {
        guard engaged, let i = turn(time, starts: starts), window.contains(i) else { return nil }
        return i
    }
    /// True once playback is `linger` s past the cited turn's end (or past the window): the panel closes.
    static func expired(_ time: Double, playing: Bool, citedEnd: Double, windowEnd: Double) -> Bool {
        playing && (time > citedEnd + linger || time > windowEnd)
    }
}
