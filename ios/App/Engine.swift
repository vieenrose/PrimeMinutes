import Foundation

/// Swift face of the C bridge (nemo_c.h). Same wire format as nemo_jni.cpp.
final class NemoEngine: @unchecked Sendable {
    private let h: OpaquePointer
    /// The LiteRT CPU backend: the X-ASR and Sortformer graphs are the .tflite files; the host parts read xasr_host.bin / diar_host.bin (no ggml).
    init?(xasr: String, diar: String, xasrTflite: String, diarTflite: String, threads: Int = 4, settle: Double = 5) {
        // The pre-encode matrix ships in the app bundle (Resources/pre_encode_512x1024.f32).
        let pre = Bundle.main.path(forResource: "pre_encode_512x1024", ofType: "f32")
        // DEV-only override (VOX_NEMO_BACKEND=ggml|litert_cpu) for the backend comparison; the shipped path is litert_host.
        let backend = Dev.env["VOX_NEMO_BACKEND"] ?? "litert_host"
        // litert_host: the ggml-free host parts read their own packed weights (xasr_host.bin / diar_host.bin) in the model dir.
        let dir = (xasr as NSString).deletingLastPathComponent
        let (xa, di) = backend == "litert_host" ? ("\(dir)/xasr_host.bin", "\(dir)/diar_host.bin") : (xasr, diar)
        guard let p = nemo_create_ex(xa, di, Int32(threads), settle, backend, xasrTflite, diarTflite, pre) else { return nil }
        h = p
    }
    private var closed = false
    /// Frees the models now (the reader needs their memory on 3 GB devices).
    func close() { if !closed { closed = true; nemo_free(h) } }
    deinit { close() }

    func push(_ pcm: [Float]) -> Bool { pcm.withUnsafeBufferPointer { nemo_push(h, $0.baseAddress, Int32($0.count)) == 1 } }
    var fedSeconds: Double { nemo_fed_seconds(h) }

    func live() -> (frozen: [Utterance], tail: [Utterance]) {
        guard let c = nemo_live(h) else { return ([], []) }
        defer { nemo_string_free(c) }
        let parts = String(cString: c).split(separator: "\u{1d}", maxSplits: 1, omittingEmptySubsequences: false)
        return (LongUtteranceSplitter.split(Self.decode(parts.first.map(String.init) ?? "")), LongUtteranceSplitter.split(Self.decode(parts.count > 1 ? String(parts[1]) : "")))
    }
    func finish() -> [Utterance]? {
        guard let c = nemo_finish_timed(h) else { return nil }
        defer { nemo_string_free(c) }
        return LongUtteranceSplitter.split(Self.decode(String(cString: c)))
    }
    static func decode(_ s: String) -> [Utterance] { SegmentCodec.decode(s) }
}

/// 16 kHz mono 16-bit PCM WAV → floats.
func loadWav(_ path: String) -> [Float]? {
    guard let d = FileManager.default.contents(atPath: path), d.count > 44 else { return nil }
    var i = 12
    while i + 8 <= d.count {
        let id = String(decoding: d[i..<i+4], as: UTF8.self)
        let n = Int(d[i+4]) | Int(d[i+5]) << 8 | Int(d[i+6]) << 16 | Int(d[i+7]) << 24
        if id == "data" {
            let end = min(d.count, i + 8 + n)
            return stride(from: i + 8, to: end - 1, by: 2).map { Float(Int16(bitPattern: UInt16(d[$0]) | UInt16(d[$0+1]) << 8)) / 32768 }
        }
        i += 8 + n + (n & 1)
    }
    return nil
}
