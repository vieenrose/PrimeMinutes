import SwiftUI

/// A citation tapped (summary pill, 筆記 核對, in-meeting Ask): the cited second and its turn.
struct CiteTarget: Equatable {
    let sec: Double
    let turn: Int
}

/// The citation transcript preview: a small panel just above the compact player with the lines around the cited turn
/// (CiteWindow), the cited one marked; while playing, the mark follows the playhead. Closes on ✕, a swipe down,
/// 「看完整逐字稿 →」 (the 逐字稿 segment at that line) or by itself a few seconds after the cited passage; the player
/// keeps playing. At most ~30 % of the screen, scrolls inside when the lines are long.
struct CitePreviewPanel: View {
    @ObservedObject var player: Player
    let cite: CiteTarget
    let lines: [Utterance]
    let tint: (Int) -> Color
    let name: (Int) -> String
    let onFull: (UUID) -> Void
    let onClose: () -> Void
    @State private var drag: CGFloat = 0

    private var window: Range<Int> { CiteWindow.window(around: cite.turn, count: lines.count) }
    private var starts: [Double] { lines.map(\.start) }
    private var marked: Int { CiteWindow.playing(player.time, engaged: player.engaged && player.available, starts: starts, window: window) ?? cite.turn }

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(Vox.ink3.opacity(0.5)).frame(width: 36, height: 4).padding(.top, 6).accessibilityHidden(true)
            HStack {
                Button { if lines.indices.contains(cite.turn) { onFull(lines[cite.turn].id) } } label: {
                    Text(L("cite_preview_full")).font(Vox.fontGroup).foregroundStyle(Vox.accent).frame(minHeight: 36)
                }.buttonStyle(.plain)
                Spacer()
                Button { onClose() } label: { Image(systemName: "xmark").font(.footnote.weight(.semibold)).foregroundStyle(Vox.ink2).tapTarget() }
                    .buttonStyle(.plain).accessibilityLabel(L("cite_preview_close"))
            }.padding(.horizontal, Vox.gutter)
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 4) { ForEach(Array(window), id: \.self) { row($0) } }
                        .padding(.horizontal, Vox.gutter).padding(.bottom, 8)
                }
                .scrollBounceBehavior(.basedOnSize)
                .onAppear { proxy.scrollTo(marked, anchor: .center) }
                .onChange(of: marked) { _, m in withAnimation { proxy.scrollTo(m, anchor: .center) } }
                .onChange(of: cite) { _, _ in proxy.scrollTo(marked, anchor: .center) }
            }
        }
        .frame(maxWidth: .infinity)
        .frame(maxHeight: UIScreen.main.bounds.height * 0.3, alignment: .top)
        .fixedSize(horizontal: false, vertical: true)
        .background(Vox.raised)
        .overlay(alignment: .top) { Vox.hairline.frame(height: 1) }
        .offset(y: max(0, drag))
        .gesture(DragGesture(minimumDistance: 12)
            .onChanged { drag = $0.translation.height }
            .onEnded { v in if v.translation.height > 50 { onClose() }; withAnimation { drag = 0 } })
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("cite_preview"))
        .accessibilityAction(.escape) { onClose() }
        .onChange(of: player.time) { _, t in
            guard lines.indices.contains(cite.turn), let last = window.last else { return }
            if CiteWindow.expired(t, playing: player.playing, citedEnd: lines[cite.turn].end, windowEnd: lines[last].end) { onClose() }
        }
    }

    private func row(_ i: Int) -> some View {
        let l = lines[i]
        return VStack(alignment: .leading, spacing: TranscriptStyle.textSpacing) {
            HStack(spacing: TranscriptStyle.headerSpacing) {
                Text(name(l.speaker)).transcriptSpeaker(tint(l.speaker))
                Text(Export.mmss(l.start)).transcriptTime()
            }
            Text(l.text).transcriptText()
        }
        .padding(.vertical, 6).padding(.horizontal, Vox.insetTight).frame(maxWidth: .infinity, alignment: .leading)
        .background(i == marked ? Vox.accent.opacity(0.12) : Color.clear, in: RoundedRectangle(cornerRadius: Vox.radiusMD))
        .overlay(alignment: .leading) { if i == cite.turn { Capsule().fill(Vox.accent).frame(width: 3).padding(.vertical, 6) } }
        .id(i)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(i == marked ? .isSelected : [])
    }
}
