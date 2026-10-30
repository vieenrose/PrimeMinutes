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

    /// A failed / destructive / recording state. Red is reserved for record / live and destruction (P2 palette).
    static let danger = Tint(Vox.dyn(0xE5484D, 0xFF6B6F),
        hcLight: Color(red: 0xA8 / 255, green: 0x00 / 255, blue: 0x00 / 255),
        hcDark: Color(red: 0xFF / 255, green: 0x70 / 255, blue: 0x70 / 255))
    /// An in-flight state: downloading, indexing, listening (the accent: one action colour).
    static let info = Tint(Vox.accent,
        hcLight: Color(red: 0x1E / 255, green: 0x3F / 255, blue: 0xA8 / 255),
        hcDark: Color(red: 0x9D / 255, green: 0xBA / 255, blue: 0xFF / 255))
    /// A waiting state: queued, paused, "verify this".
    static let warning = Tint(Vox.dyn(0xB7791F, 0xE0A94F),
        hcLight: Color(red: 0x7A / 255, green: 0x3A / 255, blue: 0x00 / 255),
        hcDark: Color(red: 0xFF / 255, green: 0xB3 / 255, blue: 0x40 / 255))
    /// A finished / healthy state.
    static let success = Tint(Vox.dyn(0x2E9156, 0x5CC28E),
        hcLight: Color(red: 0x0A / 255, green: 0x6B / 255, blue: 0x00 / 255),
        hcDark: Color(red: 0x4A / 255, green: 0xDE / 255, blue: 0x80 / 255))
    /// A search hit in the transcript (the yellow wash behind the matched line).
    /// No text is ever set in it, so it keeps the single system hue in both modes.
    static let highlight = Color.yellow

    // MARK: - P2 cool-slate palette (unified redesign, light / dark)

    /// A light / dark pair resolved by the trait collection (the code form of an asset-catalog colour set).
    static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { t in t.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })
    }
    /// Screen background (lists, meeting, capture).
    static let bg = dyn(0xF8FAFC, 0x10151C)
    /// Raised surface: sheets, grouped blocks, the queue bar.
    static let raised = dyn(0xFFFFFF, 0x19202A)
    /// Fill: fields, segmented control, chips off.
    static let fill = dyn(0xEEF1F5, 0x1F2733)
    /// Text: ink (body), ink2 (meta), ink3 (hints, group headers).
    static let ink = dyn(0x16202E, 0xE8EDF3)
    static let ink2 = dyn(0x5A6573, 0xA3ADBA)
    static let ink3 = dyn(0x8A94A1, 0x75808F)
    /// 1 px separators between rows (lists are not wrapped in cards).
    static let hairline = dyn(0xE3E7EC, 0x263040)
    /// The only action colour (the brand blue, desaturated) and its 12 % wash.
    static let accent = dyn(0x2F5BD3, 0x7DA2FF)
    static let accentTint = dyn(0xE8EEFB, 0x1C2A47)
    /// Record / live only.
    static let record = dyn(0xE5484D, 0xFF6B6F)
    static let recordTint = dyn(0xFDECEC, 0x3A1F22)

    // MARK: - Surfaces

    /// Brand navy (the app icon's field): fallback only, while the icon asset is missing.
    /// A fixed RGB, deliberately NOT dark-mode adapting — it is the logo color itself.
    static let brand = Color(red: 0x20 / 255, green: 0x37 / 255, blue: 0x60 / 255)
    /// Grouped-screen background: the P2 page colour.
    static let page = bg
    /// Card / grouped-block background: the raised surface.
    static let card = raised

    // MARK: - Entity tints

    /// Speaker identity colors, in slot order (slot = speaker % count): the live capture,
    /// the library rows, the session transcript and the talk-time bar share them, so a
    /// speaker keeps its color from recording to playback. Same lightness and chroma; a
    /// lighter step in dark mode. A chip is a 12 % wash with the solid colour as text.
    static let speaker: [Color] = [dyn(0x2F6FDE, 0x6E9CF0), dyn(0xD9622B, 0xF08A5A), dyn(0x2E9156, 0x5CC28E), dyn(0x8A5CD0, 0xB08CF0),
                                   dyn(0xB7791F, 0xE0A94F), dyn(0xB03A8A, 0xE070BC), dyn(0x1F8A8A, 0x55C2C2), dyn(0x4F5BD5, 0x8C95F0)]
    /// Note-type tints: decision green, action blue, proposal violet, open amber, number magenta.
    static let noteDecision = dyn(0x2E9156, 0x5CC28E)
    static let noteAction = dyn(0x2F6FDE, 0x6E9CF0)
    static let noteProposal = dyn(0x8A5CD0, 0xB08CF0)
    static let noteOpen = dyn(0xB7791F, 0xE0A94F)
    static let noteNumber = dyn(0xB03A8A, 0xE070BC)

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
    /// Speaker / note-type tag rounding.
    static let radiusTag: CGFloat = 6
    /// Primary button rounding (iOS: 14 pt, not a pill).
    static let radiusButton: CGFloat = 14
    /// Search field / text field rounding.
    static let radiusField: CGFloat = 12
    /// Grouped block: queue bar, settings group.
    static let radiusGroup: CGFloat = 14
    /// The record FAB's side.
    static let fab: CGFloat = 72

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
    /// Redesign type scale (Dynamic Type: each role scales from its text style).
    /// Large title 30/700, meeting title 21/700, row title 15.5/600, meta 12.5, group header 12.5/600 tracked .6.
    static let fontLargeTitle = Font.system(.largeTitle, weight: .bold)
    static let fontMeetingTitle = Font.system(.title2, weight: .bold)
    static let fontRowTitle = Font.system(.subheadline, weight: .semibold).leading(.tight)
    static let fontBody = Font.system(.body)
    static let fontMeta = Font.system(.footnote)
    static let fontGroup = Font.system(.footnote, weight: .semibold)
    static let fontPill = Font.system(.caption2, weight: .semibold)
    /// A row title in the redesign: 2 lines, ink.
    static func rowTitle(_ s: String) -> some View { Text(s).font(fontRowTitle).foregroundStyle(Vox.ink) }
    /// A grouped-list header: ink3, tracked.
    static func group(_ s: String) -> some View { Text(s).font(fontGroup).tracking(0.6).foregroundStyle(Vox.ink3).textCase(nil) }
}

extension UIColor {
    /// 0xRRGGBB.
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

/// A status pill: text on its 12 % tint, 22 pt high (never colour-only: it always carries text).
struct VoxPill: View {
    let text: String
    var color: Color = Vox.accent
    var spinning = false
    var body: some View {
        HStack(spacing: 4) {
            if spinning { ProgressView().controlSize(.mini).tint(color) }
            Text(text).font(Vox.fontPill).foregroundStyle(color).lineLimit(1)
        }
        .padding(.horizontal, 8).frame(minHeight: 22).background(color.opacity(0.12), in: Capsule())
    }
}

/// A speaker chip: 12 % tint fill, solid text, 6 pt rounding; the name always shows (never colour-only).
struct VoxSpeakerChip: View {
    let name: String
    let color: Color
    var body: some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6).accessibilityHidden(true)
            Text(name).font(Vox.fontPill).foregroundStyle(color).lineLimit(1)
        }
        .padding(.horizontal, 7).padding(.vertical, 3).background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: Vox.radiusTag, style: .continuous))
    }
}

extension View {
    /// The P2 page: hidden list/scroll background over `Vox.bg`.
    func voxPage() -> some View { scrollContentBackground(.hidden).background(Vox.bg) }
    /// A hairline-separated plain list row on the page colour.
    func voxHairlineRow() -> some View { listRowBackground(Vox.bg).listRowSeparatorTint(Vox.hairline) }
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

// MARK: - Transcript rows

/// One transcript typography for the live capture, the watch view of a running job and the finished meeting,
/// so the three cannot drift: speaker name, time, text, and the gap between rows (the capture screen's sizes,
/// all Dynamic Type text styles).
enum TranscriptStyle {
    /// Name/time row above the text, and the text under it.
    static let headerSpacing: CGFloat = 6, textSpacing: CGFloat = 2, lineSpacing: CGFloat = 2
    /// Visual gap between two rows' content; a list whose stack spacing is fixed pads each row to reach it.
    static let rowGap: CGFloat = 14, stackSpacing: CGFloat = 6
    static func rowPadding(stack: CGFloat = stackSpacing) -> CGFloat { max(0, (rowGap - stack) / 2) }
}

extension View {
    /// A transcript speaker name in its tint.
    func transcriptSpeaker(_ tint: Color) -> some View { font(.subheadline.bold()).foregroundStyle(tint) }
    /// A transcript line's start time.
    func transcriptTime() -> some View { font(.caption.monospacedDigit()).foregroundStyle(Vox.ink3) }
    /// A transcript line's text.
    func transcriptText() -> some View { font(.body).foregroundStyle(Vox.ink).lineSpacing(TranscriptStyle.lineSpacing) }
}
