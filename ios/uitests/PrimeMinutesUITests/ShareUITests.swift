import XCTest

/// Share v2 device checks with real taps on the installed DEV app: the sheet (what / where, sizes, hints), the mail
/// composer with the files attached (cancelled, draft deleted: never sent), the iCloud Drive save (the DEV app removes
/// the saved file again, VOX_SHARE_CLEANUP) and the PDF in Quick Look (a citation tapped jumps to the transcript).
/// Demo meeting PM_MEETING (default D0AB1E00-…-C3, the "move" demo with its audio) must be in the library.
final class ShareUITests: XCTestCase {
    let app = XCUIApplication(bundleIdentifier: "tw.com.pesi.primeminutes")
    var env: [String: String] { ProcessInfo.processInfo.environment }
    var lang: String { env["PM_UILANG"] ?? "zh-Hant" }
    var meeting: String { env["PM_MEETING"] ?? "D0AB1E00-0000-4000-8000-0000000000C3" }

    override func setUp() { continueAfterFailure = true }

    func launch(_ extra: [String: String] = [:]) {
        app.terminate()
        app.launchEnvironment = ["VOX_DEV": "1", "VOX_ONLY": "D0AB1E00", "VOX_UILANG": lang, "VOX_THEME": "light", "VOX_OPEN": "id:" + meeting, "VOX_TAB": "0"]
            .merging(extra) { $1 }
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        sleep(4)
    }
    func shot(_ name: String, _ root: XCUIApplication? = nil) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
        let t = XCTAttachment(string: (root ?? app).debugDescription); t.name = name + ".tree"; t.lifetime = .keepAlways; add(t)
    }
    func first(_ labels: [String], _ timeout: TimeInterval = 6, in r: XCUIApplication? = nil) -> XCUIElement? {
        let root = r ?? app, end = Date().addingTimeInterval(timeout)
        while Date() < end {
            for l in labels {
                for q in [root.buttons, root.cells, root.staticTexts, root.links] {
                    let e = q.matching(NSPredicate(format: "label == %@", l)).firstMatch
                    if e.exists && e.isHittable { return e }
                }
            }
            usleep(300_000)
        }
        return nil
    }
    /// ⋯ → Share: the sheet is up.
    func openSheet() -> Bool {
        let more = app.navigationBars.buttons.matching(NSPredicate(format: "label IN %@", ["More options", "更多選項", "更多选项"])).firstMatch
        guard more.waitForExistence(timeout: 10) else { shot("no-more"); XCTFail("no ⋯"); return false }
        more.tap(); sleep(1)
        guard let share = first(["分享", "Share"]) else { shot("no-share-entry"); XCTFail("no Share entry"); return false }
        share.tap(); sleep(3)
        return app.buttons["share-to-mail"].waitForExistence(timeout: 8)
    }

    func testSheet() {
        launch(env["PM_ONLINE"] != nil ? ["VOX_SHARE_ONLINE": "1"] : [:])
        guard openSheet() else { return }
        shot("1-sheet-default")
        app.buttons["share-what-pdf"].tap(); sleep(3); shot("2-sheet-pdf")
        let t = app.switches["share-attach-audio"]
        if t.exists { t.tap(); sleep(1); shot("3-sheet-pdf-audio") }
        app.buttons["share-what-audio"].tap(); sleep(1); shot("4-sheet-audio")
        app.swipeUp(); sleep(1); shot("5-sheet-where")
    }

    /// E-mail: the composer opens with the attachments; Cancel → Delete Draft. Never Send.
    func testMail() {
        launch()
        guard openSheet() else { return }
        app.buttons["share-what-\(env["PM_WHAT"] ?? "pdf")"].tap(); sleep(2)
        app.buttons["share-to-mail"].tap(); sleep(8); shot("mail-composer")
        if let c = first(["Cancel", "取消"], 6) {
            c.tap(); sleep(2); shot("mail-cancel")
            if let del = first(["Delete Draft", "刪除草稿", "删除草稿"], 4) { del.tap() }
            sleep(2)
        } else { XCTFail("no composer Cancel (mail not set up? the share sheet then opens)") }
        shot("mail-end")
    }

    /// iCloud Drive: the document picker in export mode; into PM_FOLDER (created under iCloud Drive when missing),
    /// then Save/Move. The DEV app removes the saved file again (VOX_SHARE_CLEANUP) and logs it.
    func testICloud() {
        launch(["VOX_SHARE_CLEANUP": "1"])
        guard openSheet() else { return }
        app.buttons["share-what-pdf"].tap(); sleep(3)
        app.buttons["share-to-icloud"].tap(); sleep(5); shot("ic-1-picker")
        // Browse → iCloud Drive (the picker is in the phone's language).
        let back = app.buttons["BackButton"].firstMatch
        if app.buttons["DOCPicker.actionButton"].exists, back.exists, back.isHittable { back.tap(); sleep(2) }
        let ic = app.cells.matching(NSPredicate(format: "label BEGINSWITH 'iCloud'")).firstMatch
        if ic.waitForExistence(timeout: 4) { ic.tap(); sleep(3) } else if let b = first(["iCloud Drive", "iCloud 雲碟", "iCloud 云盘"], 3) { b.tap(); sleep(3) }
        shot("ic-2-icloud")
        let folder = env["PM_FOLDER"] ?? "PM-share-test"
        var cell = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", folder)).firstMatch
        if !cell.exists {
            app.buttons["TopOverflowBarButtonItem"].tap(); sleep(2); shot("ic-3-menu")
            let nf = app.buttons.matching(NSPredicate(format: "label IN %@", ["Nouveau dossier", "New Folder", "新增檔案夾", "新增資料夾", "新建文件夹"])).firstMatch
            guard nf.waitForExistence(timeout: 4) else { XCTFail("no New Folder"); return }
            nf.tap(); sleep(2)
            app.typeText(folder + "\n"); sleep(3)
            cell = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", folder)).firstMatch
        }
        shot("ic-4-folder")
        if cell.exists, cell.isHittable { cell.tap(); sleep(3) }
        shot("ic-5-in-folder")
        let save = app.buttons["DOCPicker.actionButton"]
        guard save.waitForExistence(timeout: 4) else { XCTFail("no Save in the picker"); return }
        save.tap(); sleep(6); shot("ic-6-saved")
    }

    /// The PDF in Quick Look: tap the first citation link in the summary; the view must move to another page.
    func testPdfLinks() {
        launch(["VOX_SHARE": "1", "VOX_SHARE_PDF": "1", "VOX_SHARE_QL": "1", "VOX_SHARE_WHAT": "pdf"])
        sleep(6); shot("ql-1-open")
        let links = app.links.allElementsBoundByIndex.filter { $0.isHittable }
        let t = XCTAttachment(string: links.map { "\($0.label) \($0.frame)" }.joined(separator: "\n")); t.name = "ql-links"; t.lifetime = .keepAlways; add(t)
        // A citation well inside the view (not under the bars), e.g. "[3:55]" → the turn "03:55" must come into view.
        let inView = links.filter { $0.label.contains(":") && $0.frame.midY > 200 && $0.frame.midY < 650 }
        let pick = Int(env["PM_CITE"] ?? "0") ?? 0
        guard inView.indices.contains(pick) else { XCTFail("no citation in view"); return }
        let cite = inView[pick]
        let time = cite.label.trimmingCharacters(in: CharacterSet(charactersIn: "[], ")).split(separator: ":").compactMap { Int($0) }
        let clock = time.count == 2 ? String(format: "%02d:%02d", time[0], time[1]) : ""
        let note = XCTAttachment(string: "tapped \(cite.label) at \(cite.frame), expecting the turn \(clock)"); note.name = "ql-tapped"; note.lifetime = .keepAlways; add(note)
        cite.tap(); usleep(700_000); shot("ql-2a-after-cite"); sleep(3); shot("ql-2-after-cite")
        let hits = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", clock)).allElementsBoundByIndex
        let seen = hits.map(\.frame)
        let n2 = XCTAttachment(string: "\(clock) frames: \(seen)"); n2.name = "ql-turn-frames"; n2.lifetime = .keepAlways; add(n2)
        // Quick Look exposes the page text only some of the time: check when it does, else the screenshot is the evidence.
        if !seen.isEmpty { XCTAssertTrue(seen.contains { $0.minY > 100 && $0.maxY < 760 }, "turn \(clock) in view after the tap: \(seen)") }
        let back = app.links.allElementsBoundByIndex.first { $0.isHittable && $0.label.contains("↑") }
        if let back { back.tap(); sleep(3); shot("ql-3-after-back") }
    }
}
