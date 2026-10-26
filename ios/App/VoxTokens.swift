import SwiftUI

/// Design tokens: the app's shared color, radius, spacing and type vocabulary (HIG).
///
/// Why code, not an asset catalog: `ios/App/Resources/Assets.xcassets` holds only the app
/// icon; every color in the app is a system/semantic color or a `Color(.uiKit)` bridge, all of
/// which adapt to dark mode by construction. High-contrast differences come from the system's
/// `accessibilityContrast` environment, read at the use site (a static token cannot see it).
///
/// Rules for new UI: reach for `Vox.*` first. A literal (`Color.red`, `cornerRadius: 10`,
/// `.font(.caption)`) is fine only where the call site documents why it differs.
enum Vox {
    // MARK: - Status colors (dark-mode adapting system hues; raw RGB literals did not)

    /// A failed / destructive / recording state.
    static let danger = Color.red
    /// An in-flight state: downloading, indexing, listening.
    static let info = Color.blue
    /// A waiting state: queued, paused, "verify this".
    static let warning = Color.orange
    /// A finished / healthy state.
    static let success = Color.green
    /// A search hit in the transcript (the yellow wash behind the matched line).
    static let highlight = Color.yellow

    // MARK: - Surfaces

    /// Brand navy (the app icon's field): fallback only, while the icon asset is missing.
    /// A fixed RGB, deliberately NOT dark-mode adapting — it is the logo color itself.
    static let brand = Color(red: 0x20 / 255, green: 0x37 / 255, blue: 0x60 / 255)
    /// Grouped-screen background (`Color(.systemGroupedBackground)`).
    static let page = Color(.systemGroupedBackground)
    /// Card background (`Color(.secondarySystemGroupedBackground)`).
    static let card = Color(.secondarySystemGroupedBackground)

    // MARK: - Entity tints

    /// Speaker identity colors, in slot order (slot = speaker % count): the live capture,
    /// the library rows, the session transcript and the talk-time bar share them, so a
    /// speaker keeps its color from recording to playback. System hues, never raw RGB:
    /// they adapt to dark mode.
    static let speaker: [Color] = [.blue, .orange, .green, .purple, .pink, .teal, .red, .indigo]

    // MARK: - Corner radius scale

    /// A hairline rounding: the speaker bar beside a live line.
    static let radiusXS: CGFloat = 2
    /// A chip rounding: timestamp pills, selection glyphs, icon tiles.
    static let radiusSM: CGFloat = 8
    /// A row rounding: status glyphs, search bars, banner rows, transcript lines.
    static let radiusMD: CGFloat = 12
    /// A card rounding: meeting cards, summary cards.
    static let radiusLG: CGFloat = 14
    /// A button rounding: record/stop/next-talk buttons, export overlays.
    static let radiusXL: CGFloat = 16

    // MARK: - Spacing scale (gutter rhythm: 16 pt page gutter, 12/10 pt card inset)

    /// Page gutter: screen edges, headers, card lists.
    static let gutter: CGFloat = 16
    /// Card inset: padding inside a card or search bar.
    static let inset: CGFloat = 12
    /// Tight inset: padding inside a transcript line or banner row.
    static let insetTight: CGFloat = 10
    /// Wide gutter: capture screen and player bar breathing room.
    static let gutterWide: CGFloat = 20

    // MARK: - Type roles (Dynamic Type text styles; never fixed `.system(size:)` except the capture timer)

    /// Screen and sheet titles ("Prime Minutes", "Add audio", export sheet title).
    static func title(_ s: String) -> Text { Text(s).font(.title2.bold()) }
    /// Card and row titles (meeting title, job title, empty-state headline).
    static func head(_ s: String) -> Text { Text(s).font(.headline) }
    /// Secondary headings (export groups, AI notes header, action items header).
    static func subhead(_ s: String) -> Text { Text(s).font(.subheadline.weight(.semibold)) }
    /// Body copy (add-source rows).
    static func body(_ s: String) -> Text { Text(s).font(.body) }
    /// Hints and status lines (toasts, empty subtitles, run status, job status).
    static func hint(_ s: String) -> Text { Text(s).font(.subheadline).foregroundStyle(.secondary) }
    /// Meta text: timestamps, counts, dates, search snippets, download progress.
    static func meta(_ s: String) -> Text { Text(s).font(.caption).foregroundStyle(.secondary) }
    /// Fine print: disclaimers, indexing footnotes, storage rows, license lines.
    static func fine(_ s: String) -> Text { Text(s).font(.footnote).foregroundStyle(.secondary) }
    /// Micro labels: search result headers, tag chips, license rows, pipeline credit.
    static func micro(_ s: String) -> Text { Text(s).font(.caption2).foregroundStyle(.secondary) }
    /// Transcript lines and summary prose: the reading size, full contrast.
    static func transcript(_ s: String) -> Text { Text(s) }
}

// MARK: - Card backgrounds (one shape vocabulary for all cards and rows)

extension View {
    /// Card surface: secondary grouped background with the card rounding.
    func voxCard(_ radius: CGFloat = Vox.radiusLG) -> some View {
        background(Vox.card, in: RoundedRectangle(cornerRadius: radius))
    }
    /// Row surface: secondary grouped background with the row rounding.
    func voxRow(_ radius: CGFloat = Vox.radiusMD) -> some View {
        background(Vox.card, in: RoundedRectangle(cornerRadius: radius))
    }
    /// Status tint wash: a translucent status color behind a glyph or pill.
    func voxTint(_ c: Color, _ radius: CGFloat = Vox.radiusMD) -> some View {
        background(c.opacity(0.14), in: RoundedRectangle(cornerRadius: radius))
    }
    /// Chip wash: a translucent status color behind a capsule pill.
    func voxChip(_ c: Color) -> some View {
        background(c.opacity(0.14), in: Capsule())
    }
    /// Selected wash: the accent tint behind a selected transcript line.
    func voxSelected() -> some View {
        background(Color.accentColor.opacity(0.18), in: RoundedRectangle(cornerRadius: Vox.radiusMD))
    }
}

#if DEBUG
/// Token inventory for review: every `Vox.*` value in one place, so drift is visible.
struct VoxTokensPreview: View {
    var body: some View {
        List {
            Section("Status") {
                HStack { Circle().fill(Vox.danger); Circle().fill(Vox.info); Circle().fill(Vox.warning); Circle().fill(Vox.success); Circle().fill(Vox.highlight) }
            }
            Section("Speakers") { HStack { ForEach(Vox.speaker, id: \.self) { Circle().fill($0) } } }
            Section("Type") {
                Vox.title("Title"); Vox.head("Head"); Vox.subhead("Subhead"); Vox.body("Body")
                Vox.hint("Hint"); Vox.meta("Meta"); Vox.fine("Fine"); Vox.micro("Micro"); Vox.transcript("Transcript")
            }
        }
    }
}
#Preview { VoxTokensPreview() }
#endif
