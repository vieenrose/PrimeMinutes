import Foundation
import CryptoKit
import Network
import UIKit

/// Reader model files on the device (port of Android LlmRegistry + ModelManager download path):
/// Hugging Face, revision-pinned, size + sha256 checked, resumable via HTTP Range.
struct ModelFile { let name: String, remote: String, size: Int64, sha256: String? }

struct ReaderModel {
    let id: String, repo: String, rev: String
    let files: [ModelFile]
    /// The GPU's prefill/decode graph (ML Drift), fetched only when the user tests the GPU; E2B only.
    var gpu: ModelFile? = nil
    /// How to drive these weights (model_cards/<name>.json): chat template, sampler, budgets, the
    /// reader protocol and the title/prose/Ask prompts.
    var card: ModelCard = .default
    /// The model that replaces this one (Android `LlmSpec.replacedBy`): not offered any more; it reads on a
    /// phone that has it until its successor is installed, then its files are deleted.
    var replacedBy: String? = nil
    var template: ChatTemplate { card.template }
    var budget: ReaderBudget { card.budget }
    /// Bytes to download for the whole model (the registry's sizes; the GPU graph is not part of it).
    var totalBytes: Int64 { files.reduce(0) { $0 + $1.size } }
    func url(_ f: ModelFile) -> URL { URL(string: f.remote.hasPrefix("https://") ? f.remote : "https://huggingface.co/\(repo)/resolve/\(rev)/\(f.remote)")! }

    /// Speech engine, diarizer part (Android `ModelManager.DIAR_SET`), in models/nemo; CPU only. The recognizer is one of
    /// the `asr(_:)` variants (the Settings latency). Installs from before the variants also hold the 480 ms non-punct
    /// pair in models/nemo (`legacyXasr*`): it transcribes until the chosen variant is installed, then it is deleted.
    static let speech: ReaderModel = {
        let host = "37d2f9c8056f3427af324d0c1fc7910c70b65375"
        return ReaderModel(id: "nemo", repo: "", rev: "", files: [
            ModelFile(name: "diar_host.bin", remote: "https://huggingface.co/Luigi/primeminutes-nemo-litert/resolve/\(host)/diar_host.bin",
                      size: 133_648, sha256: "5286c71593db47460a51d319605432d3103eaf1c4e99fbdebf53d5bf60e62c36"),
            // LiteRT CPU graph of the diarizer. Pinned to the Luigi/primeminutes-nemo-litert revision below.
            ModelFile(name: "sortformer_int8_f190.tflite", remote: "https://huggingface.co/Luigi/primeminutes-nemo-litert/resolve/ec1b218f4d152e09bf30733de0836afc3f6b686b/sortformer_int8_f190.tflite",
                      size: 101_279_632, sha256: "c298ab66e0c8246661eb6f1a5bff1a22c14f4e5bb09c500ca4b83a3fdd57d4cf"),
        ])
    }()

    static let legacyXasrHost = "xasr_host.bin", legacyXasrTflite = "xasr_stream_q8_octav.tflite"
    static let legacyXasrSizes: [String: Int64] = [legacyXasrHost: 16_497_008, legacyXasrTflite: 152_653_712]
    /// One recognizer variant (AsrRegistry): the shared host weights + its graph, in models/xasr-punct-<ms>ms.
    static func asr(_ v: AsrVariant) -> ReaderModel {
        ReaderModel(id: v.id, repo: "", rev: v.tfliteSha, files: [
            ModelFile(name: AsrRegistry.hostFile, remote: "\(AsrRegistry.repo)/\(AsrRegistry.rev)/\(AsrRegistry.hostFile)", size: AsrRegistry.hostBytes, sha256: AsrRegistry.hostSha),
            ModelFile(name: v.tfliteFile, remote: "\(AsrRegistry.repo)/\(AsrRegistry.rev)/\(v.tfliteFile)", size: v.tfliteBytes, sha256: v.tfliteSha),
        ])
    }

    private static let tokSha = "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03"
    private static let promptSha = "406040c70270b5b9d47a4222138fcf2177f361dcbfb79fba164ca2559e0ffbf3"
    private static let promptShaV2 = "044150e8e18c05f807c88ca88358953f81fcc74fe85959184d66c65d81fd4843"
    private static func make(_ id: String, _ repo: String, _ rev: String, dir: String, prompt: String, promptSha: String = promptSha, _ g: [(Int64, String)]) -> ReaderModel {
        ReaderModel(id: id, repo: repo, rev: rev, files: [
            ModelFile(name: "Section1_SP_Tokenizer.spiece", remote: dir + "/Section1_SP_Tokenizer.spiece", size: 4_689_013, sha256: tokSha),
            ModelFile(name: "Section2_TFLiteModel_tf_lite_embedder.tflite", remote: dir + "/Section2_TFLiteModel_tf_lite_embedder.tflite", size: g[0].0, sha256: g[0].1),
            ModelFile(name: "Section3_TFLiteModel_tf_lite_per_layer_embedder.tflite", remote: dir + "/Section3_TFLiteModel_tf_lite_per_layer_embedder.tflite", size: g[1].0, sha256: g[1].1),
            ModelFile(name: "prefill_decode_fused.tflite", remote: dir + "/prefill_decode_fused.tflite", size: g[2].0, sha256: g[2].1),
            ModelFile(name: "system_prompt.txt", remote: prompt, size: 1_686, sha256: promptSha),
        ])
    }
    static let e2b: ReaderModel = {
        var m = make("E2B", "Luigi/gemma-4-E2B-meeting-agent-zh-GGUF", "f47086170552f9b8e8719e9884af9fb9b56156d1",
            dir: "mobile-v1/mfa", prompt: "mobile-v1/system_prompt.txt", [
            (103_811_720, "280327ee5720663acd2268c2e5d42caad33f70ba7931eef8c8b3018b4e2a4980"),
            (1_284_518_392, "dca1e5553b4159558b17073c94fcc7ff16646e99a56a0614728435ac4b571720"),
            (818_394_320, "6a7555ccc349be490fca4ed63ebf7fdafd8e4a4510012f3ae9c38f8009b5fcae")])
        m.gpu = ModelFile(name: "prefill_decode_fused.gpu.tflite",
            remote: "https://huggingface.co/Luigi/gemma-4-E2B-meeting-agent-zh-GGUF/resolve/0b682ba385e1d635cae685a60a07e8f9e6accbac/mobile-v1/mfa-gpu/prefill_decode_fused.tflite",
            size: 798_240_440, sha256: "a3504d11e97a346ec81b1db4d47aff55d1acf74bb2cd2129fb016e523b36c4ae")
        return m
    }()
    /// E4B mobile-v1: replaced by `e4bV2` (2026-10-18), kept only to migrate phones that have it.
    static let e4b: ReaderModel = {
        var m = make("E4B", "Luigi/gemma-4-E4B-meeting-agent-zh-LiteRT", "70e095dc5db8d4edac901578a6e0f04d994ad95e",
            dir: "mfa", prompt: "system_prompt.txt", [
            (170_920_584, "94cf45ffd3d0dd7040d27b22e70845cf1054db90613bf31d8ffc1623d23d4a40"),
            (836_778_512, "d35fe41db89fa0128f5da9530886a12581cee715369e1b9ed9a3644e43d16a6a"),
            (2_260_210_576, "34858194f4f596fae132470a2e0f2f0f276e540c7af8502e4ef7cca99e871424")])
        m.replacedBy = e4bV2Id
        return m
    }()
    static let e4bV2Id = "E4B-v2"
    /// E4B mobile-v2 (Android `gemma4-e4b-meeting-agent-zh-mobile-v2`): a new main graph and system prompt; the
    /// embedders and tokenizer are byte-identical to mobile-v1 E4B (reused, not downloaded again). Its card sets
    /// its note cap and prose budget (docs/model-eval-mobile-v2.md). CPU only (no GPU graph).
    static let e4bV2: ReaderModel = {
        var m = make(e4bV2Id, "Luigi/gemma-4-E4B-meeting-agent-zh-LiteRT", "389bc2f944f1c5a4d74bc30348e32e13e931d987",
            dir: "mobile-v2/mfa", prompt: "mobile-v2/system_prompt.txt", promptSha: promptShaV2, [
            (170_920_584, "94cf45ffd3d0dd7040d27b22e70845cf1054db90613bf31d8ffc1623d23d4a40"),
            (836_778_512, "d35fe41db89fa0128f5da9530886a12581cee715369e1b9ed9a3644e43d16a6a"),
            (2_260_210_576, "2d346d0e5f4c1a91ae3ab9df7cea2e29992f4d60a7c661ccbc886e964fe618d0")])
        m.card = .load("gemma4-meeting-agent-zh-v2")
        return m
    }()
    /// Every notes model the app knows (offered or being migrated away from).
    static let readers: [ReaderModel] = [.e2b, .e4b, .e4bV2]
    static var replacements: [String: String] { Dictionary(uniqueKeysWithValues: readers.compactMap { m in m.replacedBy.map { (m.id, $0) } }) }
    static func byId(_ id: String) -> ReaderModel { readers.first { $0.id == id } ?? .e2b }
}

enum ModelStoreError: Error { case badStatus(Int), size(String), hash(String) }

actor ModelStore {
    let root: URL
    init(root: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("models")) {
        self.root = root
    }
    func dir(_ m: ReaderModel) -> URL { root.appendingPathComponent(m.id) }
    /// Written next to a model's files: the pinned revision they came from (as Android's `.revision`).
    static let revisionMarker = ".revision"
    /// XNNPACK's packed-weight cache, built on the first load. Keyed by buffer index, not content.
    static let weightCache = "weights.xnnpack_cache"
    private func sizesMatch(_ m: ReaderModel) -> Bool {
        m.files.allSatisfy { (try? FileManager.default.attributesOfItem(atPath: dir(m).appendingPathComponent($0.name).path)[.size] as? Int64) == $0.size }
    }
    private func marked(_ m: ReaderModel) -> Bool {
        (try? String(contentsOf: dir(m).appendingPathComponent(Self.revisionMarker), encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines) == m.rev
    }
    /// Every file at its pinned size AND from the pinned revision: a re-pinned fine-tune of the same
    /// architecture has the same sizes, so the size alone would keep the old weights.
    func isComplete(_ m: ReaderModel) -> Bool {
        guard sizesMatch(m) else { return false }
        if marked(m) { return true }
        // Files from before the marker existed: they can only come from the revision pinned then, which
        // is this one until the first re-pin ships, so stamp them once instead of re-downloading GBs.
        let stamp = dir(m).appendingPathComponent(Self.revisionMarker)
        if !FileManager.default.fileExists(atPath: stamp.path), (try? m.rev.write(to: stamp, atomically: true, encoding: .utf8)) != nil { return true }
        return false
    }

    /// Downloads what is missing; `progress` gets (bytes done, bytes total) over the whole model.
    func download(_ m: ReaderModel, progress: @escaping @Sendable (Int64, Int64) -> Void) async throws {
        let d = dir(m), fm = FileManager.default
        try fm.createDirectory(at: d, withIntermediateDirectories: true)
        let stamp = d.appendingPathComponent(Self.revisionMarker)
        if fm.fileExists(atPath: stamp.path), !marked(m) {
            // Files from another revision: none is trusted (same sizes are likely), and the packed-weight
            // cache must be rebuilt from the new weights. An unstamped folder is a first download in
            // progress, kept so it resumes.
            for f in m.files {
                let p = d.appendingPathComponent(f.name)
                try? fm.removeItem(at: p); try? fm.removeItem(at: p.appendingPathExtension("part"))
            }
            try? fm.removeItem(at: d.appendingPathComponent(Self.weightCache))
            try? fm.removeItem(at: stamp)
        }
        let total = m.files.reduce(0) { $0 + $1.size }; var done: Int64 = 0
        for f in m.files {
            let base = done
            try await fetch(m, f) { got in progress(base + got, total) }
            done += f.size; progress(done, total)
        }
        try m.rev.write(to: stamp, atomically: true, encoding: .utf8)
    }

    /// The GPU graph's path when it is fully there.
    func gpuGraph(_ m: ReaderModel) -> String? {
        guard let g = m.gpu else { return nil }
        let p = dir(m).appendingPathComponent(g.name).path
        return (try? FileManager.default.attributesOfItem(atPath: p)[.size] as? Int64) == g.size ? p : nil
    }

    /// Downloads the GPU graph (sha256-checked) next to the CPU files; nil when the model has none.
    func downloadGpu(_ m: ReaderModel, progress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> String? {
        guard let g = m.gpu else { return nil }
        try FileManager.default.createDirectory(at: dir(m), withIntermediateDirectories: true)
        try await fetch(m, g) { progress($0, g.size) }
        return gpuGraph(m)
    }

    /// One file, resumed from its `.part`, checked, then moved into place; `progress` gets its bytes so far.
    private func fetch(_ m: ReaderModel, _ f: ModelFile, progress: @escaping @Sendable (Int64) -> Void) async throws {
        let dest = dir(m).appendingPathComponent(f.name), part = dest.appendingPathExtension("part")
        if let s = try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64, s == f.size { return }
        if reuse(m, f, dest) { progress(f.size); return }
        let have = (try? FileManager.default.attributesOfItem(atPath: part.path)[.size] as? Int64) ?? 0
        var req = URLRequest(url: m.url(f))
        if have > 0 { req.setValue("bytes=\(have)-", forHTTPHeaderField: "Range") }
        do { try await Chunked.fetch(req, to: part, resumeFrom: have, onProgress: progress) }
        catch ModelStoreError.badStatus(416) {
            // The .part is already whole (or past it): a kill during the sha check left it. Start over
            // instead of failing every retry on the same range.
            try? FileManager.default.removeItem(at: part)
            try await Chunked.fetch(URLRequest(url: m.url(f)), to: part, resumeFrom: 0, onProgress: progress)
        }
        guard (try FileManager.default.attributesOfItem(atPath: part.path)[.size] as? Int64) == f.size else {
            try? FileManager.default.removeItem(at: part); throw ModelStoreError.size(f.name)
        }
        if let want = f.sha256, try Self.sha256(part) != want { try? FileManager.default.removeItem(at: part); throw ModelStoreError.hash(f.name) }
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: part, to: dest)
    }

    /// The same file (size and sha256) in another installed model's folder (E4B mobile-v2 shares mobile-v1's
    /// embedders and tokenizer): hard-linked (else copied), checked, kept; false when there is none.
    private func reuse(_ m: ReaderModel, _ f: ModelFile, _ dest: URL) -> Bool {
        guard let want = f.sha256 else { return false }
        let fm = FileManager.default
        for o in ReaderModel.readers where o.id != m.id && isComplete(o) {
            guard let src = o.files.first(where: { $0.sha256 == want && $0.size == f.size }) else { continue }
            let from = dir(o).appendingPathComponent(src.name), tmp = dest.appendingPathExtension("copy")
            try? fm.removeItem(at: tmp)
            do { try fm.linkItem(at: from, to: tmp) } catch { do { try fm.copyItem(at: from, to: tmp) } catch { continue } }
            guard (try? Self.sha256(tmp)) == want else { try? fm.removeItem(at: tmp); continue }
            try? fm.removeItem(at: dest)
            if (try? fm.moveItem(at: tmp, to: dest)) != nil { StatusLog.add("reused \(f.name) from \(o.id)"); return true }
        }
        return false
    }

    static func sha256(_ url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url); defer { try? h.close() }
        var hasher = SHA256()
        while let d = try h.read(upToCount: 1 << 20), !d.isEmpty { hasher.update(data: d) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Streams a response to `file` in network-sized chunks (iterating `URLSession.bytes` is per-byte and slow).
/// Resumes with `Range` when `resumeFrom > 0` and the server answers 206; a 200 restarts the file.
final class Chunked: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private var handle: FileHandle?, got: Int64 = 0, cont: CheckedContinuation<Void, Error>?
    /// The outcome when the task ended before the continuation was handed over (cancelled before it started).
    private var early: Result<Void, Error>?
    private let lock = NSLock()
    private let file: URL, resumeFrom: Int64, onProgress: (Int64) -> Void
    private var lastReport = Date.distantPast
    private static let slow = Dev.env["VOX_DLSLOW"] != nil
    private init(file: URL, resumeFrom: Int64, onProgress: @escaping (Int64) -> Void) { self.file = file; self.resumeFrom = resumeFrom; self.onProgress = onProgress }

    static func fetch(_ req: URLRequest, to file: URL, resumeFrom: Int64, onProgress: @escaping (Int64) -> Void) async throws {
        let d = Chunked(file: file, resumeFrom: resumeFrom, onProgress: onProgress)
        let session = URLSession(configuration: .default, delegate: d, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var r = req
        if resumeFrom > 0 { r.setValue("bytes=\(resumeFrom)-", forHTTPHeaderField: "Range") }
        // Created before the cancellation handler: a task already cancelled (Cancel tapped during the podcast's HEAD
        // request) runs onCancel at once, and a dataTask asked of the invalidated session then threw an Objective-C
        // exception that aborted the app (iPhone 17e, 2026-10-16). Cancelled before resume(), it just completes with
        // NSURLErrorCancelled.
        let task = session.dataTask(with: r)
        // Cancelling the calling task stops the transfer; the partial file stays for a later resume.
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                d.start(c)
                task.resume()
            }
        } onCancel: { task.cancel(); session.invalidateAndCancel() }
    }

    func urlSession(_ s: URLSession, dataTask: URLSessionDataTask, didReceive resp: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 || code == 206 else { finish(ModelStoreError.badStatus(code)); completionHandler(.cancel); return }
        do {
            if code == 200 || !FileManager.default.fileExists(atPath: file.path) { FileManager.default.createFile(atPath: file.path, contents: nil); got = 0 }
            else { got = resumeFrom }
            handle = try FileHandle(forWritingTo: file)
            if code == 206 { try handle?.seekToEnd() }
            completionHandler(.allow)
        } catch { finish(error); completionHandler(.cancel) }
    }
    func urlSession(_ s: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do { try handle?.write(contentsOf: data) } catch { finish(error); dataTask.cancel(); return }
        if Self.slow { usleep(useconds_t(min(data.count * 6, 900_000))) }   // DEV VOX_DLSLOW: a slow network, so a download outlasts the background grace
        got += Int64(data.count)
        if Date().timeIntervalSince(lastReport) > 0.5 { lastReport = Date(); onProgress(got) }
    }
    func urlSession(_ s: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { onProgress(got); finish(error) }
    private func start(_ c: CheckedContinuation<Void, Error>) {
        lock.lock()
        if let r = early { early = nil; lock.unlock(); c.resume(with: r) } else { cont = c; lock.unlock() }
    }
    private func finish(_ error: Error?) {
        try? handle?.close(); handle = nil
        let r: Result<Void, Error> = error.map { .failure($0) } ?? .success(())
        lock.lock()
        guard let c = cont else { if early == nil { early = r }; lock.unlock(); return }
        cont = nil; lock.unlock()
        c.resume(with: r)
    }
}

/// Downloads (podcast episodes, models) that survive iOS suspending the app. A transfer runs on a plain foreground
/// session: with the screen locked or another app in front, iOS suspends the app (after the background grace asked
/// for here) and the connection dies, which used to end as "download failed". Now a transient failure (network
/// error, suspension, a server 5xx) PAUSES the download: it waits, keeps its `.part`, and resumes with a Range
/// request as soon as the app is back in front and online (or the user taps Resume). Only the user's Cancel or a
/// permanent error (404, too large, checksum, disk full) ends it.
@MainActor final class DownloadResumer: ObservableObject {
    static let shared = DownloadResumer()
    /// Downloads waiting to resume.
    @Published private(set) var paused = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private let monitor = NWPathMonitor()
    private var online = true
    private init() {
        monitor.pathUpdateHandler = { path in
            let up = path.status == .satisfied
            Task { @MainActor in let r = DownloadResumer.shared, was = r.online; r.online = up; if up && !was { r.kick() } }
        }
        monitor.start(queue: DispatchQueue(label: "downloads.net"))
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in DownloadResumer.shared.kick() }
        }
    }
    /// Every paused download tries again now (app back in front, network back, Resume, or a Cancel to observe).
    func kick() { let w = waiters; waiters = []; w.forEach { $0.resume() } }

    private func wait(after sec: Double) async {
        paused += 1; defer { paused -= 1 }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            waiters.append(c)
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(sec * 1e9))
                if UIApplication.shared.applicationState == .active && self.online { self.kick() }
            }
        }
    }

    /// Runs `attempt` until it succeeds, pausing on transient failures (`onPause(true)`, then `false` when it tries
    /// again). Throws only for a cancel of the calling task or a permanent error.
    func run(_ name: String, onPause: @escaping @MainActor (Bool) -> Void, _ attempt: @escaping @Sendable () async throws -> Void) async throws {
        var n = 0
        while true {
            let grace = BackgroundGrace(name)
            do { try await attempt(); grace.end(); onPause(false); return }
            catch {
                grace.end()
                if Task.isCancelled { onPause(false); throw CancellationError() }
                guard Self.transient(error) else { onPause(false); throw error }
                n += 1
                StatusLog.add("download \(name) paused (\(n)): \(error)")
                onPause(true)
                await wait(after: min(60, 2 * pow(2, Double(min(n, 5) - 1))))
                if Task.isCancelled { onPause(false); throw CancellationError() }
                StatusLog.add("download \(name) resumes")
            }
        }
    }

    /// A failure that waiting can fix: the network, a suspension, a busy server.
    nonisolated static func transient(_ e: Error) -> Bool {
        if e is CancellationError { return false }
        if let s = e as? ModelStoreError { if case .badStatus(let c) = s { return c >= 500 || c == 408 || c == 429 }; return false }
        let n = e as NSError
        if n.domain == NSURLErrorDomain {
            return ![NSURLErrorUnsupportedURL, NSURLErrorBadURL, NSURLErrorDataLengthExceedsMaximum, NSURLErrorFileDoesNotExist,
                     NSURLErrorNoPermissionsToReadFile, NSURLErrorCannotCreateFile, NSURLErrorCannotWriteToFile].contains(n.code)
        }
        if n.domain == NSPOSIXErrorDomain { return n.code != Int(ENOSPC) }
        return false
    }
}

/// The few seconds iOS grants a backgrounded app to finish what it was doing: asked for around each transfer, so a
/// short download completes and a long one gets its `.part` written up to the suspension.
@MainActor final class BackgroundGrace {
    private var id = UIBackgroundTaskIdentifier.invalid
    init(_ name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in MainActor.assumeIsolated { self?.end() } }   // called on the main thread
    }
    func end() { if id != .invalid { UIApplication.shared.endBackgroundTask(id); id = .invalid } }
}
