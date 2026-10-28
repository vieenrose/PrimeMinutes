import SwiftUI
import UIKit

/// Design tokens: the app's shared color, radius, spacing and type vocabulary (HIG).
///
/// Why code, not an asset catalog: `ios/App/Resources/Assets.xcassets` holds only the app
/// icon; every color in the app is a system/semantic color, a `Color(.uiKit)` bridge, or a
/// `Vox.Tint`-aware pair — all dark-mode adapting by construction. The status colors
/// are `Vox Tint`s, not plain `Color`s: they resolve the normal or the Increase-Contrast
/// variant from the environment (the code equivalent of an asset catalog's
/// high-contrast appearance). Call sites that only have a `Color` (tints, charts, capsule
/// strokes) use the normal variant; text and glyphs read `.resolve(in:)` so Increase
/// Contrast users get the 4.5:1 pair.
///
/// Rules for new UI: reach for `Vox.*` first. A literal (`Color.red`, `cornerRadius: 10`,
/// `.font(.caption)`) is fine only where the call site documents why it differs.
enum Vox {
    // MARK: - Status colors (dark-mode adapting; Increase-Contrast variants at 4.5:1 on card/page)

    /// An environment-aware status color: normal system hue, or its Increase-Contrast
    /// variant (same hue family, verified ≥ 4.5:1 on `Vox.card` and `Vox.page`, light and
    /// dark) when the user turns on Increase Contrast.
    struct Tint {
        /// Resolves in the given view environment (text, glyphs, capsule strokes):
        /// normal system hue, or the Increase-Contrast variant for the current
        /// interface style when the user turns on Increase Contrast.
        func resolve(in e: EnvironmentValues) -> Color {
            guard e.voxHighContrast else { return normal }
            return e.colorScheme == .dark ? hcDark : hcLight
        }
        /// The normal-appearance variant (tints, charts, capsule fills without environment).
        var color: Color { normal }
        private let normal: Color, hcLight: Color, hcDark: Color
        fileprivate init(_ normal: Color, hcLight: Color, hcDark: Color) {
            self.normal = normal; self.hcLight = hcLight; self.hcDark = hcDark
        }
    }

    /// A failed / destructive / recording state.
    static let danger = Tint(.red,
        hcLight: Color(red: 0xA8 / 255, green: 0x00 / 255, blue: 0x00 / 255),
        hcDark: Color(red: 0xFF / 255, green: 0x70 / 255, blue: 0x70 / 255))
    /// An in-flight state: downloading, indexing, listening.
    static let info = Tint(.blue,
        hcLight: Color(red: 0x00 / 255, green: 0x4E / 255, blue: 0xCC / 255),
        hcDark: Color(red: 0x66 / 255, green: 0xA3 / 255, blue: 0xFF / 255))
    /// A waiting state: queued, paused, "verify this".
    static let warning = Tint(.orange,
        hcLight: Color(red: 0x7A / 255, green: 0x3A / 255, blue: 0x00 / 255),
        hcDark: Color(red: 0xFF / 255, green: 0xB3 / 255, blue: 0x40 / 255))
    /// A finished / healthy state.
    static let success = Tint(.green,
        hcLight: Color(red: 0x0A / 255, green: 0x6B / 255, blue: 0x00 / 255),
        hcDark: Color(red: 0x4A / 255, green: 0xDE / 255, blue: 0x80 / 255))
    /// A search hit in the transcript (the yellow wash behind the matched line).
    /// No text is ever set in it, so it keeps the single system hue in both modes.
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
    static func micro(_ s: String) -> Text { Text(s).font(.caption).foregroundStyle(.secondary) }
    /// Transcript lines and summary prose: the reading size, full contrast.
    static func transcript(_ s: String) -> Text { Text(s) }
}

// MARK: - Card backgrounds (one shape vocabulary for all cards and rows)

extension View {
    /// Card surface: secondary grouped background with the card rounding.
    func voxCard(_ radius: CGFloat = Vox.radiusLG) -> some View {
        background(Vox.card, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
    /// Row surface: secondary grouped background with the row rounding.
    func voxRow(_ radius: CGFloat = Vox.radiusMD) -> some View {
        background(Vox.card, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
    /// Status tint wash: a translucent status color behind a glyph or pill.
    /// Takes the normal `Color` (washes carry no text); text beside it resolves the tint.
    func voxTint(_ c: Color, _ radius: CGFloat = Vox.radiusMD) -> some View {
        background(c.opacity(0.14), in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
    /// Status tint wash from a `Vox.Tint` (normal variant; see `voxTint(_:)`).
    func voxTint(_ t: Vox.Tint, _ radius: CGFloat = Vox.radiusMD) -> some View {
        voxTint(t.color, radius)
    }
    /// Chip wash: a translucent status color behind a capsule pill.
    func voxChip(_ c: Color) -> some View {
        background(c.opacity(0.14), in: Capsule())
    }
    /// Chip wash from a `Vox.Tint` (normal variant; see `voxChip(_:)`).
    func voxChip(_ t: Vox.Tint) -> some View {
        voxChip(t.color)
    }
    /// Selected wash: the accent tint behind a selected transcript line.
    func voxSelected() -> some View {
        background(Color.accentColor.opacity(0.18), in: RoundedRectangle(cornerRadius: Vox.radiusMD, style: .continuous))
    }
    /// Environment-aware status foreground: normal system hue, or the Increase-Contrast
    /// variant (verified ≥ 4.5:1 on card and page, light and dark) when the user turns on
    /// Increase Contrast. Reads the environment itself, so call sites need no plumbing.
    func voxFore(_ t: Vox.Tint) -> some View {
        modifier(VoxFore(tint: t))
    }
}

extension Vox.Tint {
    /// Resolve without a view (previews, snapshot tests): the HC variant, light or dark.
    func resolve(highContrast: Bool, dark: Bool = false) -> Color {
        var e = EnvironmentValues()
        e.voxHighContrast = highContrast
        e.colorScheme = dark ? .dark : .light
        return resolve(in: e)
    }
}

/// Whether the user turned on Increase Contrast (Settings → Accessibility → Display & Text Size).
/// A custom key because this SDK's SwiftUI has no `accessibilityContrast` environment value;
/// the default reads `UIAccessibility.isDarkerSystemColorsEnabled`, which reflects the setting.
private struct VoxHighContrastKey: EnvironmentKey {
    static var defaultValue: Bool { UIAccessibility.isDarkerSystemColorsEnabled }
}

extension EnvironmentValues {
    var voxHighContrast: Bool {
        get { self[VoxHighContrastKey.self] }
        set { self[VoxHighContrastKey.self] = newValue }
    }
}

/// Resolves a `Vox.Tint` against the Increase-Contrast setting and interface style.
private struct VoxFore: ViewModifier {
    let tint: Vox.Tint
    @Environment(\.voxHighContrast) private var contrast
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        var e = EnvironmentValues()
        e.voxHighContrast = contrast
        e.colorScheme = scheme
        return content.foregroundStyle(tint.resolve(in: e))
    }
}

#if DEBUG
/// Token inventory for review: every `Vox.*` value in one place, so drift is visible.
struct VoxTokensPreview: View {
    var body: some View {
        List {
            Section("Status") {
                HStack { Circle().fill(Vox.danger.color); Circle().fill(Vox.info.color); Circle().fill(Vox.warning.color); Circle().fill(Vox.success.color); Circle().fill(Vox.highlight) }
            }
            Section("Increase Contrast") {
                HStack { Circle().fill(Vox.danger.resolve(highContrast: true)); Circle().fill(Vox.info.resolve(highContrast: true)); Circle().fill(Vox.warning.resolve(highContrast: true)); Circle().fill(Vox.success.resolve(highContrast: true)) }
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
