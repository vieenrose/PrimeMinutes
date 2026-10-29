import XCTest

/// AirDrop of a meeting between two phones (P0 baseline + P1 checks), with real taps. The sender test opens the newest
/// meeting (VOX_OPEN), shares it (PM_MODE=p1: "Share meeting"; else the older Export > Save) and picks the receiver in
/// AirDrop (PM_TARGET, a substring of its AirDrop name). The receiver test, started first on the other phone, watches
/// SpringBoard for the AirDrop alert / app chooser for PM_WAIT seconds, taps Accept and Prime Minutes when offered
/// (PM_OPEN=1), and attaches what it saw. Run with ios/uitests/airdrop.zsh.
final class AirDropUITests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "tw.com.pesi.primeminutes")
    let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    var env: [String: String] { ProcessInfo.processInfo.environment }

    override func setUp() { continueAfterFailure = true }

    func shot(_ name: String, _ root: XCUIApplication? = nil) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
        let t = XCTAttachment(string: (root ?? app).debugDescription); t.name = name + ".tree"; t.lifetime = .keepAlways; add(t)
    }
    func first(_ labels: [String], in r: XCUIApplication, _ timeout: TimeInterval) -> XCUIElement? {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            for l in labels {
                for q in [r.buttons, r.cells, r.staticTexts] {
                    let e = q.matching(NSPredicate(format: "label CONTAINS[c] %@", l)).firstMatch
                    if e.exists && e.isHittable { return e }
                }
            }
            usleep(400_000)
        }
        return nil
    }

    func testSend() {
        app.terminate()
        app.launchEnvironment = ["VOX_DEV": "1", "VOX_OPEN": "1", "VOX_UILANG": "en", "VOX_THEME": "light"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20)); sleep(4)
        shot("s1-meeting")
        // A received meeting written by another reader asks "Re-summarize?": not now.
        if app.alerts.count > 0 || app.buttons["Re-summarize"].exists, let c = first(["Cancel"], in: app, 3) { c.tap(); sleep(1) }
        let nav = app.navigationBars.buttons.matching(NSPredicate(format: "label == %@", "More options")).firstMatch
        guard nav.waitForExistence(timeout: 10) else { XCTFail("no More options"); return }
        let more = nav
        more.tap(); sleep(1); shot("s2-menu")
        if env["PM_MODE"] == "p1" {
            guard let share = first(["Share meeting"], in: app, 5) else { XCTFail("no Share meeting"); return }
            share.tap(); sleep(2); shot("s3-preparing")
        } else {
            guard let ex = first(["Export & share"], in: app, 5) else { XCTFail("no Export"); return }
            ex.tap(); sleep(2)
            guard let save = first(["Save"], in: app, 5) else { XCTFail("no Save"); return }
            save.tap()
        }
        let airdrop = app.cells.matching(NSPredicate(format: "identifier == 'shareCell' AND label == 'AirDrop'")).firstMatch
        guard airdrop.waitForExistence(timeout: 90) else { shot("s4-nosheet"); XCTFail("no AirDrop in the share sheet"); return }
        shot("s4-sheet"); airdrop.tap(); sleep(5); shot("s5-airdrop")
        // Only a device whose name is exactly the receiver's (never a person suggestion).
        let target = env["PM_TARGET"] ?? "iPhone 14 Pro Max"
        let peer = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@ AND NOT (identifier == 'peopleCell')", target)).firstMatch
        guard peer.waitForExistence(timeout: 40), peer.isHittable else { shot("s5-nopeer"); XCTFail("receiver \(target) not listed"); return }
        peer.tap()
        for i in 0..<8 { sleep(5); shot("s6-sent-\(i)") }
        if let done = first(["Done", "完成"], in: app, 3) { done.tap() }
    }

    func testReceive() {
        let wait = Double(env["PM_WAIT"] ?? "120") ?? 120
        let end = Date().addingTimeInterval(wait)
        var n = 0, accepted = false, opened = false
        while Date() < end {
            if !accepted, let a = first(["Accept", "接受"], in: sb, 1) { shot("r-alert-\(n)", sb); a.tap(); accepted = true; sleep(2) }
            if !opened, env["PM_OPEN"] != nil, let p = first(["Prime Minutes"], in: sb, 1) { shot("r-chooser-\(n)", sb); p.tap(); opened = true; sleep(3) }
            if n % 4 == 0 { shot("r-\(n)", sb) }
            n += 1; sleep(2)
            if opened && n > 10 { break }
        }
        sleep(6); shot("r-final", app)
    }
}
