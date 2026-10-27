import SwiftUI

/// Live recording screen (Android CaptureScreen): big timer, live transcript with a coloured bar per speaker, stop button.
struct CaptureView: View {
    @ObservedObject var m: Model
    private static let palette = speakerPalette

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Circle().fill(Vox.danger.color).frame(width: 10, height: 10)
                Text(L("status_recording")).font(.title3.weight(.bold)).voxFore(Vox.danger)
                Spacer()
                // The engines' load, on the header row (trailing, page gutter) rather than floating under the timer.
                HwStatusLine()
            }.padding(.horizontal, Vox.gutterWide).padding(.top, Vox.gutter)
            // The 76 pt display face is the point of this row (it is meant to be read from across a
            // table), so it stays fixed — but VoiceOver gets the elapsed time as words, not as digits.
            Text(Export.mmss(Double(m.capture.elapsed))).font(.system(size: 76, weight: .bold, design: .rounded).monospacedDigit())
                .accessibilityLabel(L("a11y_elapsed", Export.mmss(Double(m.capture.elapsed))))
                .padding(.vertical, 12)
            // The summarizing agent works alongside ASR + diarization: its status stays pinned on top (Android LivePanel).
            AgentStrip(agent: m.screen.agent).padding(.horizontal, Vox.gutterWide).padding(.bottom, 6)
                .environment(\.speakerRefs, { [known = Set(m.screen.lines.map(\.speaker))] t in
                    SpeakerRefs.resolve(t, label: { L("speaker_n", $0 + 1) }, known: known.isEmpty ? nil : known) })
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if m.screen.lines.isEmpty {
                            VStack(spacing: 8) {
                                // Decoration: the line below is the empty state's label, so the mic stays silent.
                                Image(systemName: "mic.fill").font(.largeTitle).foregroundStyle(.secondary).accessibilityHidden(true)
                                Text(L("capture_live_waiting")).foregroundStyle(.secondary)
                                // Before the first audio is fed (elapsed 0) the status says what is holding the
                                // start up, e.g. models loading; once recording runs, the timer says it all.
                                if m.capture.elapsed == 0, !m.screen.status.isEmpty {
                                    Text(m.screen.status).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                                }
                            }.frame(maxWidth: .infinity).padding(.top, 40)
                        }
                        ForEach(m.screen.lines) { l in
                            let c = Self.palette[max(0, l.speaker) % Self.palette.count]
                            HStack(alignment: .top, spacing: 10) {
                                RoundedRectangle(cornerRadius: Vox.radiusXS).fill(c).frame(width: 3)
                                VStack(alignment: .leading, spacing: 2) {
                                    HStack(spacing: 6) {
                                        Text(L("speaker_n", max(0, l.speaker) + 1)).font(.subheadline.bold()).foregroundStyle(c)
                                        Text(Export.mmss(l.start)).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Text(l.text)
                                }
                            }.id(l.id)
                        }
                    }.padding(.horizontal, Vox.gutterWide)
                }
                .onChange(of: m.screen.lines.count) { _, _ in if let id = m.screen.lines.last?.id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } } }
            }
            Button { Haptics.toggle(true); m.capture.nextTalk() } label: {
                Label(L("capture_next_talk"), systemImage: "forward.end.fill").font(.headline).accessibilityLabel(L("cd_next_talk"))
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(Vox.card, in: RoundedRectangle(cornerRadius: Vox.radiusXL))
            }.padding(.horizontal, Vox.gutterWide)
            Button { Haptics.toggle(false); m.capture.toggleRecord() } label: {
                Label(L("capture_stop"), systemImage: "stop.fill").font(.headline)
                    .frame(maxWidth: .infinity).padding(.vertical, 16)
                    .background(Vox.danger.color, in: RoundedRectangle(cornerRadius: Vox.radiusXL)).foregroundStyle(.white)
            }.padding(.horizontal, Vox.gutterWide).padding(.vertical, Vox.inset)
        }
        .background(Vox.page)
        // VoiceOver hears start-up progress and errors (models loading, mic error) without hunting for them.
        .onChange(of: m.screen.status) { _, s in
            if m.capture.elapsed == 0, !s.isEmpty { AccessibilityNotification.Announcement(s).post() }
        }
    }
}
