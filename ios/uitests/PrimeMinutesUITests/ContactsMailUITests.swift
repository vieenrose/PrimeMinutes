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

    // MARK: player <-> transcript sync (real taps and slider drags on the newest meeting, which must have audio)

    /// The player's elapsed time in seconds, read from the "m:ss" label left of the controls.
    func elapsed() -> Int? {
        for t in app.staticTexts.allElementsBoundByIndex where t.frame.minY > 700 && t.frame.minX < 120 {
            let p = t.label.split(separator: ":"); if p.count == 2, let m = Int(p[0]), let sec = Int(p[1]) { return m * 60 + sec }
        }
        return nil
    }
    func isPlaying() -> Bool { app.buttons["Pause"].exists || app.buttons["暫停"].exists }

    func playerSync() {
        app.terminate()
        app.launchEnvironment = ["VOX_DEV": "1", "VOX_OPEN": env["PM_OPENID"] ?? "0", "VOX_TAB": "1", "VOX_THEME": "light", "VOX_UILANG": "en"]
        app.launch(); sleep(5)
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<4 {   // system notification prompt, then the app's own "Download the AI models?" sheet
            var hit = false
            for l in ["Allow", "允許", "允许"] { let b = sb.alerts.buttons[l]; if b.exists { b.tap(); hit = true; break } }
            for l in ["Later", "稍後", "稍后"] { let b = app.buttons[l]; if b.exists { b.tap(); hit = true; break } }
            if !hit { break }; sleep(2)
        }
        if !app.sliders.firstMatch.exists {   // the DEV auto-open did not land: open the seeded meeting by hand
            let row = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Remote Control'")).firstMatch
            if row.waitForExistence(timeout: 5) { row.tap(); sleep(2) }
            for l in ["Transcript", "逐字稿"] { let b = app.buttons[l]; if b.exists { b.tap(); break } }
            sleep(1)
        }
        shot("sync-0-open", tree: true)
        XCTAssertTrue(app.sliders.firstMatch.exists, "the player bar is there")
        // 1. Tap a line far from the start: playback starts there, the cursor moves.
        let lines = app.staticTexts.allElementsBoundByIndex.filter { $0.label.count > 25 && $0.isHittable }
        XCTAssertTrue(lines.count >= 2, "transcript lines are on screen")
        let target = lines.count > 2 ? lines[2] : lines[0]
        target.tap(); sleep(3); shot("sync-1-after-tap")
        let t1 = elapsed() ?? -1
        XCTAssertTrue(isPlaying(), "tap on a line: playing")
        XCTAssertTrue(t1 > 0, "tap on a line: cursor moved (\(t1) s)")
        // 2. Drag the slider far away: cursor and playback follow, the transcript scrolls there.
        let before = app.staticTexts.allElementsBoundByIndex.filter { $0.label.count > 25 && $0.isHittable }.map(\.label)
        app.sliders.firstMatch.adjust(toNormalizedSliderPosition: 0.8); sleep(3); shot("sync-2-after-drag")
        let t2 = elapsed() ?? -1
        XCTAssertTrue(t2 > t1 + 30, "drag: cursor at \(t2) s, was \(t1) s")
        XCTAssertTrue(isPlaying(), "drag: playing from the new position")
        let after = app.staticTexts.allElementsBoundByIndex.filter { $0.label.count > 25 && $0.isHittable }.map(\.label)
        XCTAssertTrue(Set(before) != Set(after), "drag: the transcript scrolled to the new position")
        // 3. Playback keeps the highlight and scroll moving by itself.
        sleep(8); shot("sync-3-later")
        XCTAssertTrue((elapsed() ?? -1) > t2, "playback advances")
    }

    /// People == Contacts (os-contacts-ios): the Contacts prompt is granted, a speaker named as a new person becomes a
    /// contact (PM_NAME, default "PMTEST Zed"), and the Contacts app finds it. Test contacts are named PMTEST… and
    /// removed afterwards with the VOX_PEOPLE_DO=delcontacts:PMTEST hook.
    func testOSContacts() {
        let name = env["PM_NAME"] ?? "PMTEST Zed"
        launch(["VOX_OPEN": "id:" + meeting, "VOX_TAB": "1", "VOX_DIALOG": "speaker", "VOX_SPEAKER": "1"])
        sleep(3); shot("oc-1-prompt", tree: true)
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let yes = ["Allow Full Access", "允許完整取用", "允许完全访问", "Continue", "繼續", "继续", "OK", "好"]
        for step in 0..<4 {   // iOS 18+: Continue, then Allow Full Access (system alert or the app's sheet)
            var hit: XCUIElement?
            for root in [sb, app] {
                for l in yes { let b = root.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", l)).firstMatch; if b.exists { hit = b; break } }
                if hit != nil { break }
            }
            guard let hit else { break }
            hit.tap(); sleep(2); shot("oc-1-granted-\(step)", tree: true)
        }
        launch(["VOX_OPEN": "id:" + meeting, "VOX_TAB": "1", "VOX_DIALOG": "speaker", "VOX_SPEAKER": "1", "VOX_PICKAUTO": name])
        sleep(7); shot("oc-2-named", tree: true)
        let contacts = XCUIApplication(bundleIdentifier: "com.apple.MobileAddressBook")
        contacts.launch(); sleep(3)
        let f = contacts.searchFields.firstMatch
        if f.waitForExistence(timeout: 6) { f.tap(); f.typeText(name); sleep(3) }
        shot("oc-3-contacts", tree: true)
        contacts.terminate()
    }

    /// "This is me in Contacts…" on the Me person page (PM_PERSON): picks the first contact, then unlinks it.
    func testMeContact() {
        launch(["VOX_SETTINGS": "1", "VOX_PEOPLE": env["PM_PERSON"] ?? "PMTEST Cy"])
        sleep(3); shot("me-1-page", tree: true)
        let b = app.buttons["contact-me"]
        if b.waitForExistence(timeout: 5) { b.tap() } else { XCTFail("no This is me button"); return }
        sleep(3); shot("me-2-picker")
        if let c = app.cells.allElementsBoundByIndex.first(where: { $0.isHittable }) { c.tap() } else { XCTFail("no contact") }
        sleep(3); shot("me-3-linked", tree: true)
        if let u = button(["Unlink", "取消連結", "取消关联"]) { u.tap(); sleep(2) } else { XCTFail("no Unlink") }
        shot("me-4-unlinked", tree: true)
    }

    /// A NEW person (PM_NAME, default "PMTEST Nia") → "Add an e-mail for X" right away → typed + Save → the contact has it
    /// (Contacts app search), and the meeting's mail sheet lists it as a recipient.
    func testNewPersonEmail() {
        let name = env["PM_NAME"] ?? "PMTEST N\(Int(Date().timeIntervalSince1970) % 100000)", addr = env["PM_ADDR"] ?? "nia.pmtest@example.com"
        launch(["VOX_OPEN": "id:" + meeting, "VOX_TAB": "1", "VOX_DIALOG": "speaker", "VOX_SPEAKER": "2", "VOX_PICKAUTO": name])
        let f = app.alerts.textFields.firstMatch   // alerts drop the identifier
        XCTAssertTrue(f.waitForExistence(timeout: 12), "the add-e-mail prompt follows the new person")
        shot("ne-1-prompt", tree: true)
        f.tap(); f.typeText(addr); shot("ne-2-typed")
        button(["Save", "儲存", "保存"])?.tap(); sleep(2); shot("ne-3-saved")
        let contacts = XCUIApplication(bundleIdentifier: "com.apple.MobileAddressBook")
        contacts.launch(); sleep(3)
        let s = contacts.searchFields.firstMatch
        if s.waitForExistence(timeout: 6) { s.tap(); s.typeText(name); sleep(2) }
        contacts.cells.firstMatch.tap(); sleep(2)   // the card: the address is there
        shot("ne-4-contacts", tree: true)
        XCTAssertTrue(contacts.staticTexts[addr].exists || contacts.buttons[addr].exists || contacts.debugDescription.contains(addr), "address in Contacts")
        contacts.terminate()
        launch(["VOX_OPEN": "id:" + meeting, "VOX_MAIL": "1"]); sleep(4); shot("ne-5-mail", tree: true)
        XCTAssertTrue(app.debugDescription.contains(addr), "address as a mail recipient")
    }

    /// "Download the AI models?" → Later: the prompt stays away (it came back a few seconds later, on a network
    /// update) and Later is reachable at every text size (at AX sizes the prompt is a scrolling sheet).
    /// Needs the models missing (fresh simulator) or PM_DLGATE=metered|wifi to force the prompt.
    func testConsentLater() {
        app.terminate()
        var e = ["VOX_DEV": "1", "VOX_UILANG": "en", "VOX_THEME": "light"]
        if let g = env["PM_DLGATE"] { e["VOX_DLGATE"] = g }
        app.launchEnvironment = e
        app.launch()
        let title = app.staticTexts["Download the AI models?"]
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")   // a fresh install asks for notifications first
        for _ in 0..<10 where !title.exists {
            for l in ["Allow", "允許", "允许"] { let b = sb.alerts.buttons[l]; if b.exists { b.tap(); break } }
            sleep(2)
        }
        XCTAssertTrue(title.waitForExistence(timeout: 20), "the download prompt is up")
        sleep(1); shot("consent-0-prompt", tree: true)
        let later = app.buttons["Later"]
        XCTAssertTrue(later.exists, "Later is there")
        // On screen for real (isHittable lets XCUITest scroll the alert by itself, which a thumb cannot rely on).
        let screen = app.windows.firstMatch.frame
        for _ in 0..<6 where !screen.contains(later.frame) { app.swipeUp(); usleep(500_000) }
        shot("consent-1-later-visible")
        XCTAssertTrue(screen.contains(later.frame), "Later is on screen: \(later.frame) in \(screen)")
        later.tap()
        for i in 0..<8 { sleep(2); XCTAssertFalse(title.exists, "the prompt came back after \(2 * (i + 1)) s"); if title.exists { break } }
        shot("consent-2-after-16s", tree: true)
    }
}

/// "This is me…" (People > Recognise voices) with real taps: the sheet must still be up seconds after the tap
/// (it used to flash and close). Never records: the sheet is cancelled. If recognition was off it is turned on for
/// the check and off again afterwards (switching off keeps no data, and nothing is enrolled).
final class MeEnrolUITests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "tw.com.pesi.primeminutes")
    var lang: String { ProcessInfo.processInfo.environment["PM_UILANG"] ?? "en" }
    func shot(_ name: String) { let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a) }
    func first(_ labels: [String], _ timeout: TimeInterval = 10) -> XCUIElement? {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            for l in labels { let b = app.buttons[l]; if b.exists { return b }; let s = app.staticTexts[l]; if s.exists { return s } }
            usleep(300_000)
        }
        return nil
    }

    func testMeSheetStays() {
        app.terminate()
        app.launchEnvironment = ["VOX_DEV": "1", "VOX_UILANG": lang]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        sleep(3)
        guard let gear = first(["Settings", "設定", "设置"]) else { return XCTFail("settings button") }
        gear.tap(); sleep(2)
        let people = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'People' OR label BEGINSWITH '說話者名錄' OR label BEGINSWITH '说话人名录'")).firstMatch
        for _ in 0..<6 where !people.isHittable { app.swipeUp(); sleep(1) }
        people.tap(); sleep(2)
        shot("01-people")
        var turnedOn = false
        let me = ["This is me…", "Record my voice again", "這是我…", "重新錄製我的聲音", "这是我…", "重新录制我的声音"]
        if first(me, 3) == nil {
            let sw = app.switches.firstMatch
            XCTAssertTrue(sw.waitForExistence(timeout: 5)); sw.tap(); sleep(1)
            first(["Turn on", "開啟", "开启"])?.tap(); turnedOn = true
        }
        guard let b = first(me, 60) else { return XCTFail("This is me button") }
        b.tap()
        usleep(300_000); shot("02-tap+0.3s")
        sleep(1); shot("03-tap+1.3s")
        sleep(4); shot("04-tap+5.3s")
        let title = ["Teach the app your voice", "讓 App 認得你的聲音", "让 App 认得你的声音", "This is me…", "這是我…", "这是我…"]
        let start = app.buttons["voice-me-start"].exists || app.buttons["Start"].exists || app.buttons["開始"].exists || app.buttons["开始"].exists
        XCTAssertTrue(start, "the sheet is still up 5 s after the tap")
        XCTAssertNotNil(first(title, 1), "sheet title visible")
        sleep(5); shot("05-tap+10s")
        (app.buttons["voice-me-cancel"].exists ? app.buttons["voice-me-cancel"] : first(["Cancel", "取消"], 2))?.tap()
        sleep(1); shot("06-cancelled")
        if turnedOn { app.switches.firstMatch.tap(); sleep(1) }
    }
}
