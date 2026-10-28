import XCTest

/// Dynamic Type at an accessibility size (AX3): the library, an open meeting and the capture timer, as screenshots
/// to check by eye (rows stack the glyph above the title, the timer is scaled and capped). Drives the installed DEV
/// app by bundle id with seeded meetings in the library. testAX3Capture records from PM_MICFILE (VOX_MICFILE) and
/// leaves that recording in the library: run it on the simulator or a test install only.
/// The size comes from the standard UIKit launch argument, so the phone's own text setting is left alone.
final class DynamicTypeUITests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "tw.com.pesi.primeminutes")
    var env: [String: String] { ProcessInfo.processInfo.environment }

    override func setUp() { continueAfterFailure = true }

    func launch(_ extra: [String: String] = [:]) {
        app.terminate()
        app.launchArguments = ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launchEnvironment = ["VOX_DEV": "1", "VOX_UILANG": env["PM_UILANG"] ?? "en", "VOX_THEME": "light", "VOX_SHOTS": "1"].merging(extra) { $1 }
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        sleep(3)
        let later = app.buttons["Later"]; if later.exists { later.tap(); sleep(1) }
    }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
        let t = XCTAttachment(string: app.debugDescription); t.name = name + ".tree"; t.lifetime = .keepAlways; add(t)
    }

    func testAX3Library() {
        launch()
        shot("ax3-library-top")
        // The header fills the first screen at AX3: scroll to the meeting rows.
        app.swipeUp(); sleep(1); app.swipeUp(); sleep(2)
        shot("ax3-library-rows")
    }

    func testAX3Session() {
        launch(["VOX_OPEN": "0"]); sleep(2)
        shot("ax3-session")
    }

    func testAX3Capture() throws {
        guard let mic = env["PM_MICFILE"] else { throw XCTSkip("PM_MICFILE not set") }
        launch(["VOX_RECORD": "1", "VOX_MICFILE": mic])
        sleep(6)
        shot("ax3-capture")
    }
}
