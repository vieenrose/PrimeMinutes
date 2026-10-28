import Foundation

/// What to do with a model file's `.part` before asking the network (pure, host-tested).
enum PartPlan: Equatable {
    /// Nothing usable: download the whole file.
    case fresh
    /// Ask for the rest with `Range: bytes=<from>-`.
    case range(Int64)
    /// The `.part` is already whole (a background transfer finished while the app was suspended or killed, or a kill
    /// came during the sha check): check it, no request. Asking for `bytes=<size>-` used to get a 416 and restart.
    case verify
    /// Longer than the file: corrupt, delete it and start over.
    case discard

    static func of(have: Int64, size: Int64) -> PartPlan {
        if have <= 0 { return .fresh }
        if have == size { return .verify }
        if have > size { return .discard }
        return .range(have)
    }

    /// Folds a finished transfer (`downloaded`, a temp file) into `part`: a 206 continues the `.part` (appended in
    /// chunks), anything else (200) replaces it.
    static func merge(_ downloaded: URL, into part: URL, status: Int) throws {
        let fm = FileManager.default
        if status == 206, fm.fileExists(atPath: part.path) {
            let out = try FileHandle(forWritingTo: part), inp = try FileHandle(forReadingFrom: downloaded)
            defer { try? out.close(); try? inp.close() }
            try out.seekToEnd()
            while let d = try inp.read(upToCount: 4 << 20), !d.isEmpty { try out.write(contentsOf: d) }
            try? fm.removeItem(at: downloaded)
        } else {
            try? fm.removeItem(at: part)
            try fm.moveItem(at: downloaded, to: part)
        }
    }
}

#if canImport(UIKit)
import UIKit

/// Model files ride a BACKGROUND URLSession: the system daemon keeps transferring with the screen locked, another
/// app in front, or the app killed by iOS, and wakes the app to hand the file over. (Before, a foreground session
/// died with the suspension and the download only paused until the app came back.) Each task is tagged with its
/// `.part` path: whoever finishes it, this process or a relaunched one, folds the bytes into that `.part`, and a later
/// `fetch` re-attaches to a still-running task instead of starting a second one.
final class BackgroundDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    static let shared = BackgroundDownloader()
    static let identifier = "tw.com.pesi.primeminutes.models"
    /// Set by the app delegate when iOS relaunches the app for this session's events; called once they are delivered.
    var systemCompletion: (() -> Void)?

    private struct Waiter { let cont: CheckedContinuation<Void, Error>; let progress: (Int64) -> Void; let base: Int64 }
    private let lock = NSLock()
    private var waiters: [String: Waiter] = [:]
    private var lastReport: [String: Date] = [:]
    /// Background time asked for when a file lands while the app is suspended: iOS wakes the app only briefly, and the
    /// sha check + the next file's request must happen before it suspends again, else the chain stalls until the
    /// app is back in front (seen on the 14 Pro Max 2026-10-28: the 1.28 GB file landed, the next never started).
    private var bridge = UIBackgroundTaskIdentifier.invalid
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        c.isDiscretionary = false
        c.sessionSendsLaunchEvents = true
        c.allowsCellularAccess = true
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    /// Reconnects to transfers a previous process left running (called at launch).
    func reconnect() { _ = session }

    /// Brings `part` (holding `resumeFrom` bytes) to the end of `req`'s resource. Returns when the bytes are in `part`.
    func fetch(_ req: URLRequest, to part: URL, resumeFrom: Int64, onProgress: @escaping (Int64) -> Void) async throws {
        let key = part.path
        let tasks = await session.allTasks
        let running = tasks.first { $0.taskDescription == key && $0.state == .running }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                lock.lock()
                if let old = waiters.removeValue(forKey: key) { old.cont.resume(throwing: CancellationError()) }
                waiters[key] = Waiter(cont: c, progress: onProgress, base: resumeFrom)
                lock.unlock()
                if running != nil { StatusLog.add("download re-attached \(part.lastPathComponent)"); endBridge(); return }
                var r = req
                if resumeFrom > 0 { r.setValue("bytes=\(resumeFrom)-", forHTTPHeaderField: "Range") }
                let t = session.downloadTask(with: r)
                t.taskDescription = key
                t.resume()
                StatusLog.add("background download \(part.lastPathComponent) started at \(resumeFrom)")
                endBridge()
            }
        } onCancel: {
            // The user's Cancel: stop the transfer (the .part keeps what was already folded in).
            self.session.getAllTasks { $0.filter { $0.taskDescription == key }.forEach { $0.cancel() } }
        }
    }

    private func beginBridge() {
        lock.lock(); defer { lock.unlock() }
        guard bridge == .invalid else { return }
        bridge = UIApplication.shared.beginBackgroundTask(withName: "model download chain") { [weak self] in self?.endBridge() }
    }
    /// The next transfer is queued (or the model is complete): the system carries on without the app.
    func endBridge() {
        lock.lock(); let b = bridge; bridge = .invalid; lock.unlock()
        if b != .invalid { UIApplication.shared.endBackgroundTask(b) }
    }

    private func take(_ key: String) -> Waiter? { lock.lock(); defer { lock.unlock() }; return waiters.removeValue(forKey: key) }

    func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite _: Int64) {
        guard let key = downloadTask.taskDescription else { return }
        lock.lock(); let w = waiters[key]
        let due = Date().timeIntervalSince(lastReport[key] ?? .distantPast) > 0.5
        if due { lastReport[key] = Date() }
        lock.unlock()
        // A 200 restarts the file, so the bytes before it no longer count.
        let base = (downloadTask.response as? HTTPURLResponse)?.statusCode == 206 ? (w?.base ?? 0) : 0
        if due, let w { w.progress(base + totalBytesWritten) }
    }

    func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let key = downloadTask.taskDescription else { return }
        let code = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        // `location` is deleted when this returns: fold it in now, even with no one waiting (relaunched in background).
        let result: Error?
        if code == 200 || code == 206 {
            do { try PartPlan.merge(location, into: URL(fileURLWithPath: key), status: code); result = nil } catch { result = error }
        } else { result = ModelStoreError.badStatus(code) }
        StatusLog.add("background download \(URL(fileURLWithPath: key).lastPathComponent) finished: HTTP \(code)")
        beginBridge()
        if let w = take(key) {
            if let result { w.cont.resume(throwing: result) } else { w.progress(w.base + downloadTask.countOfBytesReceived); w.cont.resume() }
        }
    }

    func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // Success was handled in didFinishDownloadingTo (which took the waiter); here only failures remain.
        guard let error, let key = task.taskDescription, let w = take(key) else { return }
        w.cont.resume(throwing: error)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { let h = self.systemCompletion; self.systemCompletion = nil; h?() }
    }
}

/// Receives the relaunch iOS does for background-session events (the app may have been killed meanwhile).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ app: UIApplication, didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BackgroundDownloader.shared.reconnect(); return true
    }
    func application(_ app: UIApplication, handleEventsForBackgroundURLSession id: String, completionHandler: @escaping () -> Void) {
        guard id == BackgroundDownloader.identifier else { completionHandler(); return }
        BackgroundDownloader.shared.systemCompletion = completionHandler
        BackgroundDownloader.shared.reconnect()
    }
}
#endif
