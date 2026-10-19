import Foundation

/// The speech recognizer's streaming variants (Android `AsrRegistry`): X-ASR zh-en punct int8 (sherpa-onnx 2026-06-05),
/// one checkpoint exported with four chunk sizes. Longer chunk = more accurate words, shown later. The engine reads the
/// chunk geometry from the graph; the token timestamp offset comes from here (model-agnostic engine).
struct AsrVariant: Equatable {
    let latencyMs: Int
    let tfliteFile: String, tfliteBytes: Int64, tfliteSha: String
    let tokenOffsetMs: Double
    var id: String { "xasr-punct-\(latencyMs)ms" }
}

enum AsrRegistry {
    static let repo = "https://huggingface.co/Luigi/primeminutes-nemo-litert/resolve"
    /// The Luigi/primeminutes-nemo-litert commit holding the four punct graphs and the shared host weights.
    static let rev = "7ac87f4bb4239e92cb8d37f64db6c544e8640a06"
    /// Decoder / joiner / tokens / fbank of the punct checkpoint: identical for the four chunk sizes.
    static let hostFile = "xasr_punct_host.bin", hostBytes: Int64 = 16_497_008
    static let hostSha = "2fdfc8893a3e4f4308fd2dda5221d9b185e4bd6b3652c72659afa99d4bc69a92"

    static let all: [AsrVariant] = [
        AsrVariant(latencyMs: 160, tfliteFile: "xasr_punct_160ms_q8.tflite", tfliteBytes: 152_257_712, tfliteSha: "2fe0e2c5a7a8601b50e7d9883ba178bb17aa5ba06940c6a0ff7dbe6b039cfc32", tokenOffsetMs: 300),
        AsrVariant(latencyMs: 480, tfliteFile: "xasr_punct_480ms_q8.tflite", tfliteBytes: 152_697_936, tfliteSha: "311b18d7bdf4741151af8c1ad240108fc5ef9f907c6c3a4da2edd07257f639b2", tokenOffsetMs: 300),
        AsrVariant(latencyMs: 960, tfliteFile: "xasr_punct_960ms_q8.tflite", tfliteBytes: 153_370_208, tfliteSha: "7ca126a3b832c385eda2f1b23d27154bad3992f24fb7cff304b318a81d9c0883", tokenOffsetMs: 300),
        AsrVariant(latencyMs: 1920, tfliteFile: "xasr_punct_1920ms_q8.tflite", tfliteBytes: 154_783_344,
                   tfliteSha: "cb739c2113e3347aba1761b6c190c9ae6a9e374e77c0519da61c81568db87e55", tokenOffsetMs: 300),
    ]
    static let defaultMs = 1920
    static var latencies: [Int] { all.map(\.latencyMs) }

    /// The variant for a stored setting; an unknown value (a removed option) reads as the default.
    static func byLatency(_ ms: Int) -> AsrVariant { all.first { $0.latencyMs == ms } ?? all.first { $0.latencyMs == defaultMs }! }

    /// What runs for the chosen `target`: the target once installed; until then the installed variant closest to it on
    /// the slider (ties: the more accurate one); nil when none is installed (the pre-variant model decides).
    static func runnable(_ target: Int, installed: (AsrVariant) -> Bool) -> AsrVariant? {
        let t = byLatency(target)
        if installed(t) { return t }
        let i = all.firstIndex(of: t)!
        return all.filter(installed).min { a, b in
            let da = abs(all.firstIndex(of: a)! - i), db = abs(all.firstIndex(of: b)! - i)
            return da != db ? da < db : a.latencyMs > b.latencyMs
        }
    }

    /// A load of `loaded` that succeeded makes the other variants (and the pre-variant model) removable only when it is
    /// the chosen `target`: a stand-in that loads keeps them (the target may still be downloading).
    static func reclaimsOthers(loaded: AsrVariant?, ok: Bool, target: Int) -> Bool {
        guard ok, let loaded else { return false }
        return loaded.latencyMs == byLatency(target).latencyMs
    }
}
