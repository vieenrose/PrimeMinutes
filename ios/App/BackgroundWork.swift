import SwiftUI
import BackgroundTasks
#if VOX_DEV
import AVFoundation
import os
#endif

/// Queued jobs keep going when the user leaves the app: iOS grants a processing window (usually while charging) that
/// resumes the queue; a job cut short stays queued and restarts at the next launch (Android: foreground service).
enum BackgroundWork {
    static let id = "tw.com.pesi.primeminutes.queue"
    @MainActor static weak var model: Model?
    static func register() {
        #if VOX_DEV
        Task { @MainActor in DevProbes.start() }
        #endif
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

#if VOX_DEV
/// DEV device-test probes (status.log): app lifecycle, audio session events, a resource monitor, and timed
/// interruption / route-change / record-stop / resume triggers. Times are seconds after launch.
@MainActor enum DevProbes {
    static func start() {
        let env = Dev.env, nc = NotificationCenter.default
        let t0 = Date()
        func after(_ key: String, _ f: @escaping @MainActor () -> Void) {
            guard let v = env[key].flatMap(Double.init) else { return }
            Task { @MainActor in try? await Task.sleep(nanoseconds: UInt64(v * 1e9)); f() }
        }
        for (n, label) in [(UIApplication.didEnterBackgroundNotification, "background"), (UIApplication.willEnterForegroundNotification, "foreground"), (UIApplication.willTerminateNotification, "terminate"), (UIApplication.didReceiveMemoryWarningNotification, "memory warning")] {
            nc.addObserver(forName: n, object: nil, queue: .main) { _ in StatusLog.add("trace probe app \(label) t=\(Int(Date().timeIntervalSince(t0)))") }
        }
        nc.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil) { _ in StatusLog.add("trace probe thermal \(ProcessInfo.processInfo.thermalState.rawValue)") }
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { n in
            let ty = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt ?? 99, op = n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let why = n.userInfo?[AVAudioSessionInterruptionReasonKey] as? UInt ?? 99
            StatusLog.add("trace probe audio interruption type=\(ty) options=\(op) reason=\(why) synthetic=\(n.userInfo?["vox"] != nil)")
        }
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { n in
            let r = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt ?? 99
            let ins = AVAudioSession.sharedInstance().currentRoute.inputs.map { "\($0.portName)/\($0.selectedDataSource?.dataSourceName ?? "-")" }
            StatusLog.add("trace probe route change reason=\(r) inputs=\(ins)")
        }
        nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil) { _ in StatusLog.add("trace probe media services reset") }
        // VOX_MON=s: footprint, thermal state, recording / queue state every s seconds.
        if let s = env["VOX_MON"].flatMap(Double.init) {
            Task { @MainActor in
                var peak = 0.0
                while true {
                    var i = task_vm_info_data_t(); var c = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
                    _ = withUnsafeMutablePointer(to: &i) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(c)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &c) } }
                    let fp = Double(i.phys_footprint) / 1048576; peak = max(peak, fp)
                    let m = Model.shared
                    StatusLog.add(String(format: "trace mon t=%d fp=%.0f peak=%.0f kpeak=%.0f avail=%d thermal=%d app=%d rec=%d active=%d parked=%d queued=%d prog=%.3f", Int(Date().timeIntervalSince(t0)), fp, peak, Double(i.ledger_phys_footprint_peak) / 1048576, Int(os_proc_available_memory() / 1048576), ProcessInfo.processInfo.thermalState.rawValue, UIApplication.shared.applicationState.rawValue, m.capture.recording ? 1 : 0, m.runner.activeJob != nil ? 1 : 0, m.runner.parked.count, m.runner.pending, m.runner.jobProgress))
                    try? await Task.sleep(nanoseconds: UInt64(s * 1e9))
                }
            }
        }
        // VOX_INTERRUPT=s: a synthetic "interruption began" (what a call or another app taking audio posts).
        after("VOX_INTERRUPT") { NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance(), userInfo: [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue, "vox": 1]) }
        // VOX_ROUTE=s: a real route change: the built-in mic moves to another of its data sources (front / back / bottom).
        after("VOX_ROUTE") {
            let s = AVAudioSession.sharedInstance()
            guard let mic = s.availableInputs?.first(where: { $0.portType == .builtInMic }) else { StatusLog.add("trace probe route: no built-in mic"); return }
            let cur = s.currentRoute.inputs.first?.selectedDataSource?.dataSourceID
            let next = mic.dataSources?.first { $0.dataSourceID != cur }
            do { try s.setPreferredInput(mic); if let next { try mic.setPreferredDataSource(next) }; StatusLog.add("trace probe route: asked \(next?.dataSourceName ?? "none") of \(mic.dataSources?.map(\.dataSourceName) ?? [])") }
            catch { StatusLog.add("trace probe route failed: \(error)") }
        }
        // VOX_RECSTOP=s: tap Stop on the recording.
        after("VOX_RECSTOP") { if Model.shared.capture.recording { Model.shared.capture.toggleRecord() } else { StatusLog.add("trace probe recstop: not recording") } }
        // VOX_RESUME=s: Resume every parked job.
        after("VOX_RESUME") { let r = Model.shared.runner; StatusLog.add("trace probe resume \(r.parked.count) parked"); for j in r.waiting where r.parked.contains(j.id) { r.resume(j) } }
    }
}
#endif
