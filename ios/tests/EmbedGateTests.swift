import Foundation

// A query issued during a catch-up gets the embedder within one chunk (Android EmbedGateTest).
@main struct EmbedGateTests {
    static func check(_ c: Bool, _ m: String) { if !c { print("FAIL: \(m)"); exit(1) } }

    static func main() {
        let chunk = 0.04, chunks = 100
        let gate = EmbedGate(); let started = DispatchSemaphore(value: 0), done = DispatchSemaphore(value: 0)
        let ol = NSLock(); var order: [String] = []
        Thread.detachNewThread {
            gate.with(foreground: false) {
                started.signal()
                for i in 0..<chunks { gate.checkpoint(); Thread.sleep(forTimeInterval: chunk); ol.lock(); order.append("c\(i)"); ol.unlock() }
            }
            done.signal()
        }
        started.wait(); Thread.sleep(forTimeInterval: 0.3)
        let t0 = Date()
        gate.with(foreground: true) { Thread.sleep(forTimeInterval: chunk); ol.lock(); order.append("q"); ol.unlock() }
        let waited = Date().timeIntervalSince(t0) - chunk
        done.wait()
        check(waited < 3 * chunk, "query waited \(Int(waited * 1000)) ms")
        check(order.count == chunks + 1, "build completes every chunk")
        check((order.firstIndex(of: "q") ?? chunks) < chunks / 2, "query did not wait for the meeting")
        check(gate.handovers == 1, "one handover")

        let g2 = EmbedGate(); var n = 0
        g2.with(foreground: false) { for _ in 0..<10 { if g2.checkpoint() { n += 1 } } }
        check(n == 0 && g2.handovers == 0, "no query, no handover")
        print("embed gate tests: OK (query waited \(Int(waited * 1000)) ms during a \(Int(Double(chunks) * chunk * 1000)) ms build)")
    }
}
