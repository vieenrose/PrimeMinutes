import SwiftUI
import UIKit

/// The seam between two consecutive lines of different speakers: a hairline fading from A's colour to B's with a
/// small ⇕ grip. Tapping it opens `BoundaryEditorSheet`. Android ui/BoundaryEditor.kt SpeakerSeam.
struct SpeakerSeam: View {
    let colorA: Color, colorB: Color, labelA: String, labelB: String
    let onOpen: () -> Void
    @ScaledMetric(relativeTo: .caption2) private var glyph: CGFloat = 11   // the grip follows Dynamic Type
    var body: some View {
        Button(action: onOpen) {
            ZStack(alignment: .leading) {
                LinearGradient(colors: [colorA.opacity(0.55), colorB.opacity(0.55)], startPoint: .leading, endPoint: .trailing)
                    .frame(height: 1.5).padding(.leading, 36).padding(.trailing, 10)
                Image(systemName: "arrow.up.and.down").font(.system(size: glyph, weight: .semibold)).foregroundStyle(.secondary)
                    .frame(width: max(28, glyph * 2.5), height: max(20, glyph * 1.8))
                    .background(Color(.tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.secondary.opacity(0.35), lineWidth: 1))
                    .padding(.leading, 6)
            }.frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())   // the seam is a control: 44 pt of hit area
        }.buttonStyle(.plain)
        .accessibilityLabel(L("cd_boundary_handle", labelA, labelB))
    }
}

/// A speaker the rest of a split line can go to; `isNew` = an id no line uses yet.
struct SpeakerChoice: Identifiable, Equatable { let id: Int; let label: String; let color: Color; var isNew = false }

/// Who says the rest of a split line: one chip per candidate in the order given (neighbours first, then the others,
/// then a new speaker: BoundaryEdits.splitCandidates). The seam with the speaker directory (built separately): it may
/// replace this view with its own picker as long as it keeps the contract — show `choices`, report the pick in `selected`.
struct SpeakerPicker: View {
    let choices: [SpeakerChoice]
    @Binding var selected: Int?
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(choices) { c in
                    Button { selected = c.id } label: {
                        HStack(spacing: 5) { Circle().fill(c.color).frame(width: 9, height: 9); Text(c.isNew ? "+ \(c.label)" : c.label).font(.subheadline) }
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(selected == c.id ? Color.accentColor.opacity(0.2) : Color(.tertiarySystemGroupedBackground), in: Capsule())
                            .overlay(Capsule().stroke(selected == c.id ? Color.accentColor : Color.clear, lineWidth: 1))
                    }.buttonStyle(.plain).accessibilityAddTraits(selected == c.id ? .isSelected : [])
                }
            }
        }
    }
}

/// Wrapping layout for the token strip (one view per token, lines broken between tokens).
struct TokenFlow: Layout {
    var spacing: CGFloat = 0, lineSpacing: CGFloat = 14
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0, x + s.width > w { x = 0; y += line + lineSpacing; line = 0 }
            x += s.width + spacing; line = max(line, s.height)
        }
        return CGSize(width: w, height: y + line)
    }
    func placeSubviews(in b: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = b.minX, y = b.minY, line: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > b.minX, x + s.width > b.maxX { x = b.minX; y += line + lineSpacing; line = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing; line = max(line, s.height)
        }
    }
}

/// Moves the boundary between two speakers word by word (Han text: character by character). One token strip = the
/// end of A + the start of B; the marker sits between two tokens. Drag it, tap the word B starts with, or step with
/// ‹ ›. Every move replays the audio just after the boundary (debounced); Before / After replay 2 s up to / from it.
/// With `choices` (split mode) the sheet also asks who says the rest. Android ui/BoundaryEditor.kt BoundaryEditorSheet.
struct BoundaryEditorSheet: View {
    let tokens: [String], starts: [Double], end: Double, initialSplit: Int
    let colorA: Color, labelA: String, colorB: Color, labelB: String
    var choices: [SpeakerChoice]? = nil
    /// The token times come from a character-count spread (no engine times): show "≈" on the boundary time.
    var estimated: Bool = false
    let canPlay: Bool
    /// DEV (README animation): marker steps played one by one after the sheet opens, then Apply.
    var demo: [Int]? = nil
    let onPreview: (Double, Double) -> Void
    let onApply: (Int, Int?) -> Void
    let onCancel: () -> Void

    @State private var split = 1
    @State private var moves = 0
    @State private var pick: Int?
    @State private var frames: [Int: CGRect] = [:]
    private let window = 40
    private var lo: Int { max(0, initialSplit - window) }
    private var hi: Int { min(tokens.count, initialSplit + window) }
    private var chosen: SpeakerChoice? { choices?.first { $0.id == pick } }
    private var cB: Color { chosen?.color ?? colorB }
    private var lB: String { chosen?.label ?? labelB }
    private var t: Double { starts[min(split, starts.count - 1)] }

    private func set(_ k: Int) {
        let c = min(max(k, max(1, lo)), min(tokens.count - 1, hi))
        if c != split { split = c; moves += 1; UISelectionFeedbackGenerator().selectionChanged() }
    }
    /// Gap nearest to a point of the strip: before the token under the finger, or after it past its middle.
    private func gap(at p: CGPoint) -> Int? {
        guard !frames.isEmpty else { return nil }
        if let (i, r) = frames.first(where: { $0.value.insetBy(dx: 0, dy: -7).contains(p) }) { return p.x < r.midX ? i : i + 1 }
        return frames.min { a, b in hypot(a.value.midX - p.x, a.value.midY - p.y) < hypot(b.value.midX - p.x, b.value.midY - p.y) }.map { p.x < $0.value.midX ? $0.key : $0.key + 1 }
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 6) {
                    Circle().fill(colorA).frame(width: 10, height: 10); Text(labelA).font(.subheadline.weight(.semibold)).foregroundStyle(colorA)
                    Text("→").foregroundStyle(.secondary)
                    Circle().fill(cB).frame(width: 10, height: 10); Text(lB).font(.subheadline.weight(.semibold)).foregroundStyle(cB)
                }
                if let choices { SpeakerPicker(choices: choices, selected: $pick) }
                ScrollView { strip.padding(12) }
                    .frame(maxHeight: 260)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
                let delta = split - initialSplit, dt = t - starts[initialSplit]
                Text(L("boundary_at", (estimated ? "≈" : "") + Self.precise(t)) + (delta != 0 ? String(format: "   %+d · %+.1f s", delta, dt) : ""))
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                Text(L("boundary_hint", lB)).font(.caption).foregroundStyle(.secondary)
                // One-handed control row: thumbs reach the bottom of the sheet.
                HStack(spacing: 8) {
                    Button { set(split - 1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                        .buttonStyle(.bordered).accessibilityLabel(L("cd_boundary_earlier"))
                    Button { onPreview(max(0, t - 2), t) } label: { Text("◀| " + L("boundary_before")).lineLimit(1).frame(maxWidth: .infinity, minHeight: 44) }
                        .buttonStyle(.bordered).disabled(!canPlay)
                    Button { onPreview(t, min(t + 2, end)) } label: { Text(L("boundary_after") + " |▶").lineLimit(1).frame(maxWidth: .infinity, minHeight: 44) }
                        .buttonStyle(.bordered).disabled(!canPlay)
                    Button { set(split + 1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                        .buttonStyle(.bordered).accessibilityLabel(L("cd_boundary_later"))
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.top, 4)
            .navigationTitle(L(choices != nil ? "boundary_split_title" : "boundary_title")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("cancel"), action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("boundary_apply")) { onApply(split, pick) }.bold().disabled(choices == nil && split == initialSplit)
                }
            }
        }
        .onAppear { split = min(max(initialSplit, 1), tokens.count - 1); pick = choices?.first?.id }
        .task {
            guard let demo, !demo.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            for d in demo { set(split + d); try? await Task.sleep(nanoseconds: 1_600_000_000) }
            try? await Task.sleep(nanoseconds: 800_000_000)
            onApply(split, pick)
        }
        // Auto-preview: hear B's first words once the marker settles.
        .task(id: moves) {
            guard moves > 0, canPlay else { return }
            try? await Task.sleep(nanoseconds: 450_000_000)
            if Task.isCancelled { return }
            onPreview(t, min(t + 1.8, end))
        }
    }

    private var shown: [String] { BoundaryEdits.displayTokens(tokens) }
    private static var spaceWidth: CGFloat { (" " as NSString).size(withAttributes: [.font: UIFont.preferredFont(forTextStyle: .body)]).width }

    private var strip: some View {
        TokenFlow {
            if lo > 0 { Text("… ").foregroundStyle(.secondary) }
            ForEach(lo..<hi, id: \.self) { i in
                // A token's trailing space as an explicit gap: Text drops trailing whitespace from its width,
                // which glued English words together ("two,right?"). Same spacing as the text (displayTokens).
                Text(String(shown[i].reversed().drop(while: { $0 == " " }).reversed()))
                    .font(.body)
                    .padding(.trailing, shown[i].hasSuffix(" ") ? Self.spaceWidth : 0)
                    .padding(.vertical, 3)
                    .background((i < split ? colorA : cB).opacity(0.22))
                    .overlay(alignment: .leading) {
                        if i == split {   // the marker: a bar with a knob, like a text-selection handle
                            ZStack(alignment: .bottom) {
                                Capsule().fill(Color.primary).frame(width: 3)
                                Circle().fill(Color.primary).frame(width: 11, height: 11).overlay(Circle().fill(cB).frame(width: 6, height: 6)).offset(y: 9)
                            }.padding(.vertical, -3).offset(x: -1.5).allowsHitTesting(false)
                        }
                    }
                    .background(GeometryReader { g in
                        Color.clear.onAppear { frames[i] = g.frame(in: .named("strip")) }
                            .onChange(of: g.frame(in: .named("strip"))) { _, r in frames[i] = r }
                    })
                    .onTapGesture { set(i) }
            }
            if hi < tokens.count { Text(" …").foregroundStyle(.secondary) }
        }
        .coordinateSpace(name: "strip")
        .gesture(DragGesture(minimumDistance: 6, coordinateSpace: .named("strip")).onChanged { v in if let g = gap(at: v.location) { set(g) } })
        // VoiceOver: one adjustable element (swipe up / down), plus named actions.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("cd_boundary_marker"))
        .accessibilityValue(L("boundary_state", lB, tokens.indices.contains(split) ? tokens[split].trimmingCharacters(in: .whitespaces) : ""))
        .accessibilityAdjustableAction { d in
            switch d { case .increment: set(split + 1); case .decrement: set(split - 1); default: break }
        }
        .accessibilityAction(named: L("cd_boundary_earlier")) { set(split - 1) }
        .accessibilityAction(named: L("cd_boundary_later")) { set(split + 1) }
    }

    static func precise(_ sec: Double) -> String {
        let ms = max(0, Int(sec * 1000))
        return String(format: "%d:%02d.%d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)
    }
}
