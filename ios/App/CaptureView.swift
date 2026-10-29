import SwiftUI
import UIKit
import os

/// Live recording screen: a toolbar with the recording pill (pulsing dot + elapsed time) and a ⋯ menu, a warning
/// banner only when something needs attention (heat, memory, storage) or when the meters are pinned from the menu, a
/// Voice-Memos waveform, the live transcript filling the screen from the top, the AI-notes line/card, then the
/// round stop button with 下一場 beside it. No pause (a meeting is recorded whole).
struct CaptureView: View {
    @ObservedObject var m: Model
    private static let palette = speakerPalette
    @AppStorage("captureShowMeters") private var showMeters = false
    @StateObject private var health = CaptureHealth()
    @State private var agentOpen = false
    @State private var following = true

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let w = health.warning { banner(w) }
                else if showMeters { HwStatusLine(force: true).padding(.vertical, 6).frame(maxWidth: .infinity) }
                WaveformStrip(meter: m.capture.meter).frame(height: 40).padding(.horizontal, Vox.gutterWide).padding(.vertical, 8)
                transcript
                AgentStrip(agent: m.screen.agent)
                    .environment(\.speakerRefs, { [known = Set(m.screen.lines.map(\.speaker))] t in
                        SpeakerRefs.resolve(t, label: { L("speaker_n", $0 + 1) }, known: known.isEmpty ? nil : known) })
                    .onTapGesture { agentOpen = true }
                    .accessibilityAddTraits(.isButton)
                controls
            }
            .background(Color(.systemBackground))
            .toolbar {
                ToolbarItem(placement: .principal) { pill }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Toggle(L("menu_show_meters"), isOn: $showMeters)
                    } label: { Image(systemName: "ellipsis.circle").accessibilityLabel(L("cd_more")) }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $agentOpen) {
                NavigationStack { ScrollView { AgentPanel(agent: m.screen.agent).padding() } }.presentationDetents([.medium, .large])
            }
        }
        .onAppear { health.start() }.onDisappear { health.stop() }
        // VoiceOver hears start-up progress and errors (models loading, mic error) without hunting for them.
        .onChange(of: m.screen.status) { _, s in
            if m.capture.elapsed == 0, !s.isEmpty { AccessibilityNotification.Announcement(s).post() }
        }
        .onChange(of: health.warning) { _, w in if let w { AccessibilityNotification.Announcement(L(w.key)).post() } }
    }

    /// Recording dot + elapsed time; before the first audio (elapsed 0) the status (e.g. models loading) instead.
    private var pill: some View {
        let starting = m.capture.elapsed == 0 && !m.screen.status.isEmpty
        return HStack(spacing: 8) {
            RecDot()
            Text(starting ? m.screen.status : Export.mmss(Double(m.capture.elapsed)))
                .font(.headline.monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
                .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        }
        .padding(.horizontal, 14).padding(.vertical, 6)
        .background(Color(.secondarySystemFill), in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("a11y_rec_pill", Export.mmss(Double(m.capture.elapsed))))
        .accessibilityAddTraits(.updatesFrequently)
    }

    private func banner(_ w: CaptureHealth.Warning) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(L(w.key), systemImage: "exclamationmark.triangle.fill").font(.footnote.weight(.semibold))
            HwStatusLine(force: true)
        }
        .foregroundStyle(Vox.warning.color)
        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
        .background(Vox.warning.color.opacity(0.12), in: RoundedRectangle(cornerRadius: Vox.radiusMD))
        .padding(.horizontal, Vox.gutterWide).padding(.top, 4)
        .accessibilityElement(children: .combine)
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // Top-aligned: lines start under the waveform and push down until they fill, then the view follows.
                LazyVStack(alignment: .leading, spacing: 14) {
                    if m.screen.lines.isEmpty {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text(L("capture_listening_hint")).foregroundStyle(.secondary)
                        }
                        if m.capture.elapsed == 0, !m.screen.status.isEmpty {
                            Text(m.screen.status).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    ForEach(m.screen.lines) { l in
                        let c = Self.palette[max(0, l.speaker) % Self.palette.count]
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(L("speaker_n", max(0, l.speaker) + 1)).font(.subheadline.bold()).foregroundStyle(c)
                                Text(Export.mmss(l.start)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            }
                            Text(l.text)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .id(l.id)
                        .accessibilityElement(children: .combine)
                    }
                    Color.clear.frame(height: 1).id("end")
                        .onAppear { following = true }.onDisappear { following = false }
                }
                .padding(.horizontal, Vox.gutterWide).padding(.top, 4)
            }
            .overlay(alignment: .bottom) {
                if !following && !m.screen.lines.isEmpty {
                    Button { withAnimation { proxy.scrollTo("end", anchor: .bottom) } } label: {
                        Label(L("capture_jump_latest"), systemImage: "arrow.down").font(.footnote.weight(.semibold))
                    }
                    .buttonStyle(.borderedProminent).buttonBorderShape(.capsule).controlSize(.small).padding(.bottom, 8)
                }
            }
            .onChange(of: m.screen.lines.last?.text) { _, _ in
                if following { withAnimation { proxy.scrollTo("end", anchor: .bottom) } }
            }
        }
    }

    private var controls: some View {
        HStack {
            Button { Haptics.toggle(true); m.capture.nextTalk() } label: {
                Text(L("capture_next_talk")).font(.body).frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(L("cd_next_talk"))
            .frame(maxWidth: .infinity)
            Button { Haptics.toggle(false); m.capture.toggleRecord() } label: {
                VStack(spacing: 6) {
                    ZStack {
                        Circle().stroke(Color(.separator), lineWidth: 4).frame(width: 72, height: 72)
                        RoundedRectangle(cornerRadius: 6).fill(.red).frame(width: 30, height: 30)
                    }
                    Text(L("capture_stop")).font(.caption).foregroundStyle(.primary).lineLimit(2).multilineTextAlignment(.center)
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L("cd_capture_stop")).accessibilityAddTraits(.isButton)
            Color.clear.frame(maxWidth: .infinity, maxHeight: 1)   // balances 下一場
        }
        .padding(.horizontal, Vox.gutterWide).padding(.top, 10).padding(.bottom, Vox.inset)
    }
}

/// The red recording dot: pulses 1 → 0.25 over 1.2 s, static under Reduce Motion.
private struct RecDot: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dim = false
    var body: some View {
        Circle().fill(.red).frame(width: 10, height: 10).opacity(dim ? 0.25 : 1)
            .onAppear { if !reduceMotion { withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) { dim = true } } }
    }
}

/// Voice-Memos style level strip: bars scroll right → left, one per 50 ms, redrawn at 20 Hz from `LevelMeter`.
/// Older bars fade; silence is a flat line. Hidden from VoiceOver (decoration).
struct WaveformStrip: View {
    let meter: LevelMeter
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        TimelineView(.periodic(from: .now, by: reduceMotion ? 0.25 : 0.05)) { _ in
            Canvas { ctx, size in
                let bars = meter.bars(), n = bars.count
                let step = size.width / CGFloat(n), w = max(1.5, step * 0.55)
                for (i, v) in bars.enumerated() {
                    let h = max(3, CGFloat(v) * size.height)
                    let r = CGRect(x: CGFloat(i) * step + (step - w) / 2, y: (size.height - h) / 2, width: w, height: h)
                    let old = i < n * 35 / 100
                    ctx.fill(Path(roundedRect: r, cornerRadius: w / 2), with: .color(.red.opacity(old ? 0.35 : 1)))
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// Watches what can spoil a recording: heat (thermal state ≥ serious), memory (< 300 MB of headroom before the app's jetsam limit; the
/// spec's "< 10 % of RAM" would fire all the time while E4B is resident) and
/// storage (< 500 MB). A warning stays up 30 s after recovery so it does not flicker.
@MainActor final class CaptureHealth: ObservableObject {
    enum Warning: Equatable {
        case heat, memory, storage
        var key: String { switch self { case .heat: return "capture_warn_heat"; case .memory: return "capture_warn_memory"; case .storage: return "capture_warn_storage" } }
    }
    @Published private(set) var warning: Warning?
    private var task: Task<Void, Never>?
    private var clearAt: Date?
    func start() {
        task?.cancel()
        task = Task { while !Task.isCancelled { check(); try? await Task.sleep(nanoseconds: 5_000_000_000) } }
    }
    func stop() { task?.cancel() }
    private func check() {
        let now = current()
        if let now { warning = now; clearAt = nil }
        else if warning != nil {
            if let t = clearAt { if Date() >= t { warning = nil; clearAt = nil } } else { clearAt = Date().addingTimeInterval(30) }
        }
    }
    private func current() -> Warning? {
        let t = ProcessInfo.processInfo.thermalState
        if t == .serious || t == .critical { return .heat }
        let avail = os_proc_available_memory()
        if avail > 0, avail < 300 * 1_048_576 { return .memory }
        let home = URL(fileURLWithPath: NSHomeDirectory())
        if let free = try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage,
           free > 0, free < 500_000_000 { return .storage }
        return nil
    }
}
