import XCTest

/// Device checks of the contacts + "Email participants" flows with real taps (no app code: the installed DEV app
/// is driven by bundle id). Demo meetings D0AB1E00-…-C1 (AMI IS1009c, en) / -C2 (move, zh) must be in the library;
/// ios/uitests/run.zsh puts them there and removes them, with the people directory restored, afterwards.
/// The flows never send mail: the composer is always cancelled and its draft deleted.
final class ContactsMailUITests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "tw.com.pesi.primeminutes")
    var env: [String: String] { ProcessInfo.processInfo.environment }
    var lang: String { env["PM_UILANG"] ?? "en" }
    var meeting: String { lang == "en" ? "D0AB1E00-0000-4000-8000-0000000000C1" : "D0AB1E00-0000-4000-8000-0000000000C2" }

    override func setUp() { continueAfterFailure = true }

    func launch(_ extra: [String: String]) {
        app.terminate()
        app.launchEnvironment = ["VOX_DEV": "1", "VOX_ONLY": "D0AB1E00", "VOX_UILANG": lang, "VOX_THEME": "light", "VOX_SHOTS": "1"]
            .merging(extra) { $1 }
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
    }

    func shot(_ name: String, tree: Bool = false) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
        if tree || env["PM_TREE"] != nil {
            let t = XCTAttachment(string: app.debugDescription); t.name = name + ".tree"; t.lifetime = .keepAlways; add(t)
        }
    }

    /// First button whose label is one of `labels` (the system sheets are in the phone's language, the app in its own).
    func button(_ labels: [String], in root: XCUIElement? = nil) -> XCUIElement? {
        let r = root ?? app
        for l in labels { let b = r.buttons[l]; if b.exists { return b } }
        return nil
    }
    func waitAny(_ labels: [String], _ timeout: TimeInterval = 8) -> XCUIElement? {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if let b = button(labels) { return b }; usleep(300_000) }
        return nil
    }
    static let cancel = ["Cancel", "取消"]

    /// The contact picker (out of process) is up: its search field or its Cancel; then the first enabled contact is
    /// tapped when `pick`, else it is cancelled. Returns whether a contact was chosen.
    @discardableResult func contactPicker(_ step: String, pick: Bool) -> Bool {
        sleep(3)
        shot(step + "-picker", tree: true)
        let picker = app.navigationBars.matching(NSPredicate(format: "identifier IN %@", ["Contacts", "聯絡人", "通讯录", "联系人"])).firstMatch
        XCTAssertTrue(picker.exists || app.searchFields.firstMatch.exists, "\(step): the contact picker is up")
        if pick {
            // Contacts with an e-mail are enabled; the first one, then its first address if it has several.
            let cells = app.cells.allElementsBoundByIndex.filter { $0.isEnabled && $0.isHittable }
            if let c = cells.first {
                c.tap(); sleep(2); shot(step + "-picked", tree: true)
                if let e = app.cells.allElementsBoundByIndex.first(where: { $0.label.contains("@") && $0.isHittable }) { e.tap(); sleep(2) }
                return true
            }
            XCTFail("\(step): no contact with an e-mail on this phone")
        }
        if let c = button(Self.cancel) { c.tap() }
        sleep(2)
        return false
    }

    /// Person page → "Choose from contacts…" → the system picker.
    func testPersonPageContactPicker() {
        launch(["VOX_SETTINGS": "1", "VOX_PEOPLE": env["PM_PERSON"] ?? "Ben"])
        sleep(4); shot("person-page", tree: true)
        let b = app.buttons["contact-pick"]
        XCTAssertTrue(b.waitForExistence(timeout: 8), "person page: contact button")
        if !b.isHittable { app.swipeUp() }
        b.tap()
        contactPicker("person", pick: env["PM_PICK"] != nil)
        sleep(2); shot("person-after", tree: true)
    }

    /// Naming a speaker → "Choose from contacts…" → the picker → "Name in Prime Minutes" → saved.
    func testNameSpeakerFromContacts() {
        launch(["VOX_OPEN": "id:" + String(meeting.prefix(36)), "VOX_TAB": "1", "VOX_DIALOG": "speaker", "VOX_SPEAKER": env["PM_SPEAKER"] ?? "1"])
        sleep(4); shot("speaker-sheet", tree: true)
        let b = app.buttons["picker-contacts"]
        XCTAssertTrue(b.waitForExistence(timeout: 8), "speaker sheet: Choose from contacts")
        b.tap()
        if contactPicker("speaker", pick: env["PM_PICK"] != nil) {
            sleep(2); shot("speaker-name-alert", tree: true)
            if let save = waitAny(["Save", "儲存", "保存"]) { save.tap() } else { XCTFail("no name alert") }
            sleep(2)
            if let same = waitAny(["Same person", "同一人"], 2) { same.tap() }
            sleep(2); shot("speaker-named", tree: true)
        }
    }

    /// The meeting menu → "Email participants…" → the sheet → preview → open the mail; the composer is cancelled
    /// and its draft deleted (never sent); mailto / share sheet fallbacks are only observed.
    func testEmailParticipants() {
        launch(["VOX_OPEN": "id:" + String(meeting.prefix(36)), "VOX_TAB": "0"])
        sleep(4)
        let more = app.navigationBars.buttons.allElementsBoundByIndex.last
        XCTAssertNotNil(more)
        more?.tap(); sleep(1); shot("menu", tree: true)
        guard let entry = waitAny(["Email participants…", "寄給與會者…", "发给与会者…"], 4) else { XCTFail("menu entry"); return }
        entry.tap(); sleep(2); shot("mail-sheet", tree: true)
        if env["PM_ADDR"] != nil, let add = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", env["PM_ADDR_BUTTON"] ?? (lang == "en" ? "Add" : "新增"))).allElementsBoundByIndex.first {
            add.tap(); sleep(1)
            let f = app.textFields["mail-inline-address"]; if f.waitForExistence(timeout: 3) { f.tap(); f.typeText(env["PM_ADDR"]!); sleep(1); shot("mail-inline", tree: true); app.keyboards.buttons.allElementsBoundByIndex.last?.tap() }
            sleep(1); shot("mail-after-inline", tree: true)
        }
        let pv = app.buttons["mail-preview"]
        if pv.waitForExistence(timeout: 4) { pv.tap(); sleep(2); shot("mail-preview", tree: true)
            if let close = waitAny(["Close", "關閉", "关闭", "Done", "完成"], 3) { close.tap() }; sleep(1) }
        guard env["PM_OPEN"] != nil else { return }
        let open = app.buttons["mail-open"]
        if !open.isHittable { app.swipeUp() }
        open.tap(); sleep(5); shot("mail-opened", tree: true)
        // The composer (in process tree as a remote view): Cancel → Delete Draft. Never Send.
        if let c = button(Self.cancel) {
            c.tap(); sleep(2); shot("composer-cancel", tree: true)
            if let del = waitAny(["Delete Draft", "刪除草稿", "删除草稿"], 4) { del.tap() }
            sleep(2)
        }
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let t = XCTAttachment(string: sb.debugDescription); t.name = "springboard.tree"; t.lifetime = .keepAlways; add(t)
        shot("mail-end", tree: true)
    }
}
