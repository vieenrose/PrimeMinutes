import Foundation

/// Process-wide: one `MfaEngine` computes at a time (Android `MfaCompute`). Two engines can be
/// loaded together (the job's reader and Ask's), and the native fused-attention op shares its
/// scratch buffers (it also locks itself); this lock also keeps two engines from splitting the CPU.
/// Outside `VOX_REAL_READER` so the host tests can check it.
enum MfaCompute {
    private static let lock = NSRecursiveLock()

    static func run<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock(); defer { lock.unlock() }
        return try body()
    }
}
