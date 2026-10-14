import os
import Foundation

#if VOX_REAL_READER   // needs LiteRT: CLiteRTLM (device) or the x86_64 libLiteRt.so built by native/litert_x86_sim (Intel simulator)

/// The Gemma-4 SentencePiece tokenizer (`Section1_SP_Tokenizer.spiece`). It parses the chat
/// template's special tokens itself (`<|turn>` = 105, `<turn|>` = 106); `<bos>` is not added.
final class SpTokenizer {
    private let h: OpaquePointer
    init(path: String) throws {
        var err = [CChar](repeating: 0, count: 512)
        guard let p = mfa_tok_load(path, &err, 512) else { throw ReaderError(description: String(cString: err)) }
        h = p
    }
    deinit { mfa_tok_free(h) }

    func encode(_ text: String) -> [Int] {
        var out: UnsafeMutablePointer<Int32>?
        let n = Int(mfa_tok_encode(h, text, &out))
        defer { mfa_ids_free(out) }
        return (0..<n).map { Int(out![$0]) }
    }
    /// A reply cut mid-character ends with U+FFFD, which a later call completes.
    func decode(_ ids: [Int]) -> String {
        let a = ids.map { Int32($0) }
        var n: Int32 = 0
        guard let p = mfa_tok_decode(h, a, Int32(a.count), &n) else { return "" }
        defer { free(p) }
        return String(decoding: UnsafeBufferPointer(start: UnsafeRawPointer(p).assumingMemoryBound(to: UInt8.self), count: Int(n)), as: UTF8.self)
    }
}

/// The forked LiteRT-LM CPU engine (cpp/mfa) running Google's Gemma-4 mobile graphs. Takes token
/// ids and reuses the longest prefix of each prompt already in its KV cache.
final class MfaEngine {
    private var h: OpaquePointer?
    static let bos = 2

    /// `dir` = the folder with the three `.tflite` files; `weightCache` is built on first load (CPU).
    /// `gpuGraph`: run on the GPU (ML Drift Metal) with this prefill/decode graph instead.
    init(dir: String, ctx: Int, threads: Int, weightCache: String, gpuGraph: String? = nil) throws {
        var err = [CChar](repeating: 0, count: 1024)
        guard let p = mfa_load(dir, gpuGraph ?? dir + "/prefill_decode_fused.tflite", Int32(ctx), Int32(threads),
                               gpuGraph == nil ? weightCache : "", gpuGraph == nil ? 0 : 1, &err, 1024)
        else { throw ReaderError(description: "mobile reader load failed: " + String(cString: err)) }
        h = p; onGpu = gpuGraph != nil
    }
    deinit { mfa_free(h) }

    var context: Int { h.map { Int(mfa_context($0)) } ?? 0 }
    /// Loaded on the GPU (ML Drift Metal) rather than the CPU.
    let onGpu: Bool
    /// The last `generate`: prefilled, reused, generated, prefill_s, decode_s.
    private(set) var lastStats = [Double](repeating: 0, count: 5)

    /// Teacher-forced agreement with `forced` (another backend's greedy reply to `ids`): how many
    /// steps pick the next forced token. Near `forced.count` computes right, near 0 does not.
    func agree(_ ids: [Int], forced: [Int]) throws -> Int {
        guard let h else { throw ReaderError(description: "engine closed") }
        var err = [CChar](repeating: 0, count: 512)
        let r = mfa_agree(h, ids.map { Int32($0) }, Int32(ids.count), forced.map { Int32($0) }, Int32(forced.count), &err, 512)
        if r < 0 { throw ReaderError(description: String(cString: err)) }
        return Int(r)
    }
    func cancel() { if let h { mfa_cancel(h) } }

    /// Prefill `ids` (starting with `<bos>`) and generate up to `maxNew` tokens (0 = prefill only);
    /// `onToken` returns false to stop.
    func generate(_ ids: [Int], maxNew: Int, temp: Float, topK: Int, topP: Float, seed: UInt32,
                  onToken: (Int) -> Bool = { _ in true }) throws -> [Int] {
        guard let h else { throw ReaderError(description: "engine closed") }
        let a = ids.map { Int32($0) }
        var err = [CChar](repeating: 0, count: 512)
        var out: UnsafeMutablePointer<Int32>?
        var stats = [Double](repeating: 0, count: 5)
        typealias CB = (Int) -> Bool
        let n: Int32 = withoutActuallyEscaping(onToken) { cb in
            var box = cb
            return withUnsafeMutablePointer(to: &box) { ptr in
                mfa_generate(h, a, Int32(a.count), Int32(maxNew), temp, Int32(topK), topP, seed,
                             { id, user in user!.assumingMemoryBound(to: CB.self).pointee(Int(id)) ? 1 : 0 },
                             UnsafeMutableRawPointer(ptr), &out, &stats, &err, 512)
            }
        }
        guard n >= 0 else { throw ReaderError(description: String(cString: err)) }
        lastStats = stats
        if stats[3] + stats[4] > 5 {   // prefilled, reused, generated, prefill_s, decode_s: compute-bound or paging?
            StatusLog.add("trace mfa prefilled \(Int(stats[0])) (reused \(Int(stats[1]))) in \(Int(stats[3])) s = \(Int(stats[0] / max(stats[3], 0.1))) tok/s; generated \(Int(stats[2])) in \(Int(stats[4])) s; \(os_proc_available_memory() / 1_048_576) MB free")
        }
        defer { mfa_ids_free(out) }
        return (0..<Int(n)).map { Int(out![$0]) }
    }
}

/// The meeting reader's session on the mobile engine (port of Android `MfaSession`). The engine
/// keeps no sessions: the "conversation" is this token list — `append` prefills it (only the new
/// tail is computed), `generateContinue` resends it with a decode budget and appends the reply,
/// `reset` just forgets it.
final class MfaSession: ReaderLlm {
    private let engine: MfaEngine, tok: SpTokenizer
    private let topK: Int, topP: Float, seed: UInt32
    private var seq: [Int] = []
    /// Sticky: a stopped reading stays stopped through its remaining windows until `resume()`.
    private let cancelledLock = NSLock()
    private var cancelled = false

    init(engine: MfaEngine, tok: SpTokenizer, topK: Int = 40, topP: Float = 0.95, seed: UInt32 = 0) {
        self.engine = engine; self.tok = tok; self.topK = topK; self.topP = topP; self.seed = seed
    }

    private var isCancelled: Bool { cancelledLock.lock(); defer { cancelledLock.unlock() }; return cancelled }
    func cancel() { cancelledLock.lock(); cancelled = true; cancelledLock.unlock(); engine.cancel() }
    func resume() { cancelledLock.lock(); cancelled = false; cancelledLock.unlock() }

    func tokenize(_ text: String, special: Bool) -> [Int] {
        // SentencePiece parses the template's pieces (<|turn>, <turn|>) but not <bos>: map it here.
        guard special, text.contains("<bos>") else { return tok.encode(text) }
        var out: [Int] = []
        for (i, part) in text.components(separatedBy: "<bos>").enumerated() {
            if i > 0 { out.append(MfaEngine.bos) }
            if !part.isEmpty { out += tok.encode(part) }
        }
        return out
    }

    func append(_ tokens: [Int]) -> Int {
        if isCancelled || engine.context == 0 { return -1 }
        let next = seq + tokens
        if next.count >= engine.context { return -1 }   // a refused append leaves the sequence as it was
        do { _ = try engine.generate(next, maxNew: 0, temp: 0, topK: 1, topP: 1, seed: seed) } catch { return -1 }
        if isCancelled { return -1 }
        seq = next
        return seq.count
    }

    func generateContinue(maxTokens: Int, stop: String, temp: Float, onToken: (String) -> Void) -> String {
        let room = engine.context - seq.count - 1
        if room <= 0 || isCancelled { return "" }
        var gen: [Int] = [], shown = "", stopped = false
        _ = try? engine.generate(seq, maxNew: min(maxTokens, room), temp: temp, topK: topK, topP: topP, seed: seed) { [tok] id in
            gen.append(id)
            let text = tok.decode(gen)
            // Stream only complete characters: a reply cut mid-character decodes to U+FFFD.
            var stable = text; while stable.hasSuffix("\u{FFFD}") { stable.removeLast() }
            if stable.utf16.count > shown.utf16.count && stable.hasPrefix(shown) {
                onToken(String(stable.dropFirst(shown.count))); shown = stable
            }
            if !stop.isEmpty && text.contains(stop) { stopped = true; return false }
            return true
        }
        // The generated ids themselves join the sequence (re-tokenizing the text could differ and
        // break the engine's prefix reuse); an end-of-turn id is the model's, not the text's.
        let kept = gen.filter { !Self.stopIds.contains($0) }
        var text = tok.decode(kept)
        if stopped, let r = text.range(of: stop) { text = String(text[..<r.upperBound]) }
        seq += kept
        return text
    }

    var seqLength: Int { seq.count }
    var context: Int { engine.context }
    /// The last generate: prefilled, reused, generated, prefill_s, decode_s.
    var lastStats: [Double] { engine.lastStats }
    func reset() { seq.removeAll() }

    private static let stopIds: Set<Int> = [1, 50, 106]
}

#endif

/// Dev hook (VOX_MFABENCH): prefill / decode throughput of the mobile engine on synthetic ids, the
/// same protocol as the Android mfa_bench (warm-up 130, prefill 1024 with 129 reused, decode 64).
enum MfaBench {
    static func run(dir: String?) -> String {
#if VOX_REAL_READER
        guard let dir else { return "mfabench: no reader model" }
        do {
            // GPU (VOX_MFA_BACKEND=1): graph pushed to Documents/mfa-gpu.tflite; agreement with the CPU's greedy reply first.
            let gpu = ProcessInfo.processInfo.environment["VOX_MFA_BACKEND"] == "1" ? NSHomeDirectory() + "/Documents/mfa-gpu.tflite" : nil
            var agree = ""
            if gpu != nil { freopen(NSHomeDirectory() + "/Documents/mfa_stderr.txt", "w", stderr); setvbuf(stderr, nil, _IONBF, 0) }
            if let gpu {
                let tok = try SpTokenizer(path: dir + "/Section1_SP_Tokenizer.spiece")
                let p = [MfaEngine.bos] + Array(tok.encode("會議討論新辦公室的搬遷時程，搬家公司報價有三種，發言者傾向中間價位，並要求合約註明損壞賠償條款。下一步請整理成筆記，列出決議與待辦事項，並標註負責人與期限。").prefix(80))
                let ref = try autoreleasepool { try MfaEngine(dir: dir, ctx: 4096, threads: Prefs.effectiveThreads, weightCache: dir + "/weights.xnnpack_cache").generate(p, maxNew: 32, temp: 0, topK: 1, topP: 1, seed: 0) }
                let t = Date()
                let g = try MfaEngine(dir: dir, ctx: 4096, threads: Prefs.effectiveThreads, weightCache: "", gpuGraph: gpu)
                let gl = Date().timeIntervalSince(t)
                let out = try g.generate(p, maxNew: 32, temp: 0, topK: 1, topP: 1, seed: 0)
                agree = String(format: " agree %d/%d first_load %.1fs ref %@ gpu %@ ", try g.agree(p, forced: ref), ref.count, gl,
                               tok.decode(ref), tok.decode(out))
            }
            let t0 = Date()
            let e = try MfaEngine(dir: dir, ctx: 4096, threads: Prefs.effectiveThreads, weightCache: dir + "/weights.xnnpack_cache", gpuGraph: gpu)
            let load = Date().timeIntervalSince(t0)
            var ids = [MfaEngine.bos]; var x: UInt32 = 12345
            while ids.count < 1024 { x = x &* 1103515245 &+ 12345; ids.append(1000 + Int((x >> 8) % 30000)) }
            var warm = Array(ids.prefix(130)); warm[129] = 7
            _ = try e.generate(warm, maxNew: 2, temp: 0, topK: 1, topP: 1, seed: 1)
            var t = Date(); _ = try e.generate(ids, maxNew: 0, temp: 0, topK: 1, topP: 1, seed: 1)
            let pf = Date().timeIntervalSince(t)
            var n = 0; t = Date()
            let out = try e.generate(ids, maxNew: 64, temp: 0, topK: 1, topP: 1, seed: 1) { _ in n += 1; return true }
            var h: UInt32 = 2166136261; for v in out { h = (h ^ UInt32(truncatingIfNeeded: v)) &* 16777619 }
            var vi = task_vm_info_data_t(); var cnt = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
            _ = withUnsafeMutablePointer(to: &vi) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(cnt)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &cnt) } }
            var ru = rusage(); getrusage(RUSAGE_SELF, &ru)
            let dc = Date().timeIntervalSince(t)
            return String(format: "mfabench threads=%d load %.2fs prefill 895 tok %.2fs = %.1f tok/s decode %d tok %.2fs = %.2f tok/s hash %08x peak_footprint %d MB maxrss %d MB backend=%@",
                          Prefs.effectiveThreads, load, pf, 895 / pf, n, dc, Double(n) / dc, h,
                          Int(vi.ledger_phys_footprint_peak >> 20), Int(ru.ru_maxrss >> 20), ProcessInfo.processInfo.environment["VOX_MFA_BACKEND"] ?? "0") + agree
        } catch { return "mfabench FAIL \(error)" }
#else
        return "mfabench: no real reader in this build"
#endif
    }
}
