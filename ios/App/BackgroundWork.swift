import SwiftUI
import BackgroundTasks

/// Queued jobs keep going when the user leaves the app: iOS grants a processing window (usually while charging) that
/// resumes the queue; a job cut short stays queued and restarts at the next launch (Android: foreground service).
enum BackgroundWork {
    static let id = "tw.com.pesi.primeminutes.queue"
    @MainActor static weak var model: Model?
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            // Handed to the main actor below and not touched here after that (BGTask is not Sendable).
            nonisolated(unsafe) let task = task
            StatusLog.add("trace background task started")
            // Out of time: checkpoint the running job and leave it queued (not parked), or iOS kills the app mid-run.
            task.expirationHandler = { StatusLog.add("trace background task expired"); Task { @MainActor in Model.shared.runner.yieldToSystem() }; schedule() }
            Task { @MainActor in
                // A background launch has no UI, so no ContentView ever built the model: use the shared one (it used to be
                // nil here, and the task ended at once with the queue untouched).
                let m = Model.shared; model = m
                m.runner.drain(); await m.runner.drainTask?.value
                task.setTaskCompleted(success: true)
            }
        }
    }
    /// iOS 26: a user-started queue run keeps going when the user leaves the app, with the system's progress UI
    /// (BGContinuedProcessingTask). One task per drain, submitted while the app is in front; it reports the current
    /// job's progress and ends with the queue. Before iOS 26 only the processing window above remains.
    static let continuedPrefix = "tw.com.pesi.primeminutes.process"
    /// Registered under the exact id, just before its submission: a handler registered under the wildcard is not
    /// found for a submitted id (NSInternalInconsistencyException); continued tasks may register after launch.
    @available(iOS 26, *) private static func registerContinued(_ id: String) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            // Handed to the main actor below and not touched here after that (BGTask is not Sendable).
            guard let continued = task as? BGContinuedProcessingTask else { task.setTaskCompleted(success: false); return }
            nonisolated(unsafe) let task = continued
            StatusLog.add("trace continued task started")
            task.progress.totalUnitCount = 1000
            // The system takes the time back (user cancel, load): checkpoint the job and leave it queued.
            task.expirationHandler = { StatusLog.add("trace continued task expired"); Task { @MainActor in Model.shared.runner.yieldToSystem() } }
            Task { @MainActor in
                let m = Model.shared
                while m.runner.isDraining {
                    task.progress.completedUnitCount = Int64(min(max(m.runner.jobProgress, 0), 1) * 1000)
                    task.updateTitle(L("bg_task_title"), subtitle: m.screen.status)
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
                task.progress.completedUnitCount = 1000
                StatusLog.add("trace continued task done")
                task.setTaskCompleted(success: true)
            }
        }
    }
    @MainActor static func continueInBackground() {
        guard #available(iOS 26, *), Dev.env["VOX_NOCONTINUED"] == nil else { return }
        guard UIApplication.shared.applicationState != .background else { StatusLog.add("trace continued task: app in background, not submitted"); return }
        let id = "\(continuedPrefix).\(UUID().uuidString)"
        guard registerContinued(id) else { StatusLog.add("trace continued task: handler not registered"); return }
        let r = BGContinuedProcessingTaskRequest(identifier: id, title: L("bg_task_title"), subtitle: L("status_starting"))
        r.strategy = .fail   // now or not at all: a queued request could start after the run is over
        do { try BGTaskScheduler.shared.submit(r) } catch { StatusLog.add("trace continued task not submitted: \(error)") }
    }
    static func schedule() {
        Task {
            guard await Model.shared.queue.count > 0 else { return }
            let r = BGProcessingTaskRequest(identifier: id)
            r.requiresNetworkConnectivity = false; r.requiresExternalPower = false
            do { try BGTaskScheduler.shared.submit(r) } catch { StatusLog.add("trace background schedule failed: \(error)") }
        }
    }
}
