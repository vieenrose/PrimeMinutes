import Foundation

// The citation preview panel's turn window (CiteWindow): same turn rule as the PDF cites (ShareCore.target).
@main struct CiteWindowTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }

    static func main() {
        let starts: [Double] = [0.4, 12.8, 30.2, 41.0, 55.5, 70.9, 88.1]
        // Turn rule: the last turn starting at or before the cite (whole seconds), else the first.
        check(CiteWindow.turn(30, starts: starts) == 2, "cite on a turn start")
        check(CiteWindow.turn(40.9, starts: starts) == 2, "cite inside a turn")
        check(CiteWindow.turn(12, starts: starts) == 1, "12.8 starts in second 12")
        check(CiteWindow.turn(0, starts: [5, 9]) == 0, "before the first turn: the first")
        check(CiteWindow.turn(999, starts: starts) == 6, "after the end: the last")
        check(CiteWindow.turn(10, starts: []) == nil, "no transcript")
        for sec in [0, 13, 30, 41, 56, 71, 88, 120] { check(CiteWindow.turn(Double(sec), starts: starts) == ShareCore.target(sec, starts.map { Int($0) }), "same as ShareCore at \(sec)") }

        // Window: one before, the turn, three after; shifted inward at the ends, never past the transcript.
        check(CiteWindow.window(around: 3, count: 7) == 2..<7, "middle")
        check(CiteWindow.window(around: 0, count: 7) == 0..<5, "first turn: shifted down")
        check(CiteWindow.window(around: 6, count: 7) == 2..<7, "last turn: shifted up")
        check(CiteWindow.window(around: 1, count: 2) == 0..<2, "short meeting")
        check(CiteWindow.window(around: 0, count: 1) == 0..<1, "one line")
        check(CiteWindow.window(around: 4, count: 0).isEmpty, "empty")
        for c in 1...9 { for i in 0..<c { let w = CiteWindow.window(around: i, count: c); check(w.contains(i) && w.count == min(c, 5) && w.lowerBound >= 0 && w.upperBound <= c, "window \(i)/\(c)") } }

        // The highlight follows playback inside the window only.
        let w = CiteWindow.window(around: 2, count: 7)
        check(CiteWindow.playing(45, engaged: true, starts: starts, window: w) == 3, "follows playback")
        check(CiteWindow.playing(45, engaged: false, starts: starts, window: w) == nil, "not played yet")
        check(CiteWindow.playing(5, engaged: true, starts: starts, window: w) == nil, "outside the window")

        // Auto-close: a few seconds after the cited turn ends, only while playing.
        check(!CiteWindow.expired(42, playing: true, citedEnd: 40, windowEnd: 99), "within linger")
        check(CiteWindow.expired(44.5, playing: true, citedEnd: 40, windowEnd: 99), "past linger")
        check(!CiteWindow.expired(60, playing: false, citedEnd: 40, windowEnd: 99), "paused: stays")
        check(CiteWindow.expired(42, playing: true, citedEnd: 40, windowEnd: 41), "past the window")
        print("cite window tests: OK")
    }
}
