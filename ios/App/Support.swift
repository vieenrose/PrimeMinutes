import SwiftUI
import UserNotifications
import os

/// Mic chunks arrive on the audio thread, the engine loop drains them on another.
final class ChunkBuffer: @unchecked Sendable {
    private var data: [Float] = []; private let lock = NSLock()
    func add(_ c: [Float]) { lock.lock(); data += c; lock.unlock() }
    func take() -> [Float] { lock.lock(); defer { lock.unlock() }; let d = data; data = []; return d }
    private var shut = false
    var closed: Bool { lock.lock(); defer { lock.unlock() }; return shut }
    func close() { lock.lock(); shut = true; lock.unlock() }
}

/// Local notification when a meeting is ready (Android posts one from its foreground service).
enum Notifier {
    static func requestPermission() {
        if Dev.env["VOX_NONOTIF"] != nil { return }   // DEV: simulator runs over ssh cannot answer the system alert
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
    static func done(_ title: String) {
        // One notification that each new meeting replaces (Android reuses one id); never an empty body (untitled meeting).
        let c = UNMutableNotificationContent(); c.title = L("notif_session_ready"); c.body = title.isEmpty ? L("meeting") : title; c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "session-ready", content: c, trigger: nil))
    }
    /// Android: a queued item that could not be processed (it stays in the queue with Retry).
    static func failed(_ title: String) {
        let c = UNMutableNotificationContent(); c.title = L("svc_queue_item_failed", title); c.body = L("svc_queue_item_failed_hint"); c.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }
}

/// Every status line, timestamped, in Documents/status.log (pullable with devicectl: the console is not always attached).
enum StatusLog {
    // DEV builds keep it in Documents for the device test scripts; a release keeps it in Caches, which is
    // never backed up (it carries meeting titles and progress lines).
    #if VOX_DEV
    static let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("status.log")
    #else
    static let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("status.log")
    #endif
    private static let q = DispatchQueue(label: "statuslog", qos: .utility)   // never block the app on file I/O (a stalled devicectl copy held the file)
    nonisolated(unsafe) private static var lastKey = "", lastAt = Date.distantPast
    static func add(_ s: String) {
        let now = Date()
        q.async {
            // progress lines ("轉錄中 12 / 300 秒") differ only by digits: keep one every 15 s
            let key = String(s.filter { !$0.isNumber })
            if key == lastKey && now.timeIntervalSince(lastAt) < 15 { return }
            lastKey = key; lastAt = now
            if let n = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int, n > 1_000_000 { try? FileManager.default.removeItem(at: url) }
            guard let d = (ISO8601DateFormatter().string(from: now) + " " + s + "\n").data(using: .utf8) else { return }
            if let h = try? FileHandle(forWritingTo: url) { defer { try? h.close() }; _ = try? h.seekToEnd(); try? h.write(contentsOf: d) } else { try? d.write(to: url) }
        }
    }
}

/// Debug watchdog: logs the engine stage when it has not changed for 60 s (a hung call shows up in status.log).
enum Stage {
    nonisolated(unsafe) static var name = "idle"; nonisolated(unsafe) static var since = Date()
    static func set(_ n: String) { name = n; since = Date() }
    static let watchdog: Void = { Thread.detachNewThread { while true { Thread.sleep(forTimeInterval: 60)
        let d = Int(Date().timeIntervalSince(since)); if name != "idle" && d >= 60 { StatusLog.add("trace stage \(name) stuck \(d) s, \(os_proc_available_memory() / 1_048_576) MB free") } } } }()
}

/// Test hooks (VOX_* environment variables, the Sample button) exist only in builds made with DEV=1.
/// DEV: VOX_SNAP="3,8" writes the screen to Documents/snap1.png, snap2.png… at those seconds after launch, so a
/// device run can be checked without Xcode (pulled with devicectl). Not compiled into release builds.
enum Snap {
    @MainActor static func schedule(_ spec: String) {
        for (i, t) in spec.split(separator: ",").compactMap({ Double($0) }).enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + t) {
                guard let w = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return }
                let img = UIGraphicsImageRenderer(bounds: w.bounds).image { _ in w.drawHierarchy(in: w.bounds, afterScreenUpdates: true) }
                try? img.pngData()?.write(to: FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("snap\(i + 1).png"))
            }
        }
    }
}

/// DEV: VOX_FRAMES=<seconds> writes the screen every <seconds> to Documents/frames/f00001.jpg… (point size, scale 1)
/// until Documents/frames/STOP exists; real-device stand-in for a screen recorder, for the README demo.
enum Frames {
    @MainActor static func start(_ every: Double) {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("frames")
        try? FileManager.default.removeItem(at: dir); try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let c = Capture(dir)
        Timer.scheduledTimer(withTimeInterval: every, repeats: true) { t in
            if !MainActor.assumeIsolated({ c.frame() }) { t.invalidate() }   // a main run loop timer
        }
    }
    /// The frame counter and render format, main actor only.
    @MainActor private final class Capture {
        let dir: URL, fmt = UIGraphicsImageRendererFormat(); var n = 0
        init(_ dir: URL) { self.dir = dir; fmt.scale = 1 }
        /// False once Documents/frames/STOP exists.
        func frame() -> Bool {
            if FileManager.default.fileExists(atPath: dir.appendingPathComponent("STOP").path) { return false }
            guard let w = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first else { return true }
            let img = UIGraphicsImageRenderer(bounds: w.bounds, format: fmt).image { _ in w.drawHierarchy(in: w.bounds, afterScreenUpdates: false) }
            n += 1; try? img.jpegData(compressionQuality: 0.8)?.write(to: dir.appendingPathComponent(String(format: "f%05d.jpg", n)))
            return true
        }
    }
}

enum Dev {
    /// The root view's onAppear runs again when a cover closes; the launch hooks must run once.
    @MainActor static var ranOnce = false
    @MainActor static var ranPeopleOps = false
    @MainActor static var ranVoiceOps = false
    #if VOX_DEV
    static let on = true
    static let env = ProcessInfo.processInfo.environment
    #else
    static let on = false
    static let env: [String: String] = [:]
    #endif
}

enum StopFlag { nonisolated(unsafe) static var on = false }

/// Touch confirmation at the moments the app answers the user: on the result of a long task, not on the tap.
/// Not main-actor-isolated: it is also called from queue callbacks and DispatchQueue closures, and it hops to the
/// main thread itself (the generators must run there).
enum Haptics {
    /// A long task ended: a meeting was saved, an answer finished, an export is ready.
    nonisolated static func success() { onMain { UINotificationFeedbackGenerator().notificationOccurred(.success) } }
    /// A destructive step completed (meetings deleted).
    nonisolated static func destructive() { onMain { UIImpactFeedbackGenerator(style: .heavy).impactOccurred() } }
    /// A momentary switch flipped (recording started / stopped).
    nonisolated static func toggle(_ on: Bool) { onMain { UIImpactFeedbackGenerator(style: on ? .medium : .light).impactOccurred() } }
    private nonisolated static func onMain(_ f: @escaping () -> Void) { if Thread.isMainThread { f() } else { DispatchQueue.main.async(execute: f) } }
}

extension View {
    /// At least 44x44 pt of hit area (HIG): grows the control's frame, its drawing is untouched.
    func tapTarget(_ side: CGFloat = 44) -> some View {
        frame(minWidth: side, minHeight: side).contentShape(Rectangle())
    }
    /// The 44 pt height only: for controls packed in a dense row, where the width must stay intrinsic.
    func tapHeight(_ h: CGFloat = 44) -> some View {
        frame(minHeight: h).contentShape(Rectangle())
    }
}
