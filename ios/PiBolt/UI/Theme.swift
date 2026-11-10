import SwiftUI
import UIKit

/// The Pi-Bolt themes (android/DESIGN.md), following the system's appearance. Dark is "Pi-Bolt": a warm charcoal
/// canvas, surfaces a warm step above it, four warm-grey inks, hairlines as white alphas, the warm white ink as the only
/// accent, and green, amber and red for state only. Light is "Paper", the desktop's light mode: a white canvas, cards
/// stepping down to a soft grey, near-black ink and accent, black alphas for lines, status colours deep enough to read
/// as text on paper. No blue in either. The values are the Android palette's (ui/theme/Palette.kt, warm() and paper()).
enum Pi {
    static let canvas = Color(light: 0xFFFFFF, dark: 0x1C1B19)
    static let surface1 = Color(light: 0xF7F7F7, dark: 0x232220)
    static let surface2 = Color(light: 0xF0F0F0, dark: 0x2A2926)
    static let surface3 = Color(light: 0xE8E8E8, dark: 0x32312E)
    static let ink1 = Color(light: 0x171717, dark: 0xECEBE8)
    static let ink2 = Color(light: 0x525252, dark: 0xB3B1AB)
    static let ink3 = Color(light: 0x666666, dark: 0x9C9A94)
    static let ink4 = Color(light: 0x949494, dark: 0x5E5C57)
    static let line = Color(overlayLight: 0.08, dark: 0.07)
    static let lineStrong = Color(overlayLight: 0.14, dark: 0.12)
    static let accent = Color(light: 0x0A0A0A, dark: 0xECEBE8)
    static let onAccent = Color(light: 0xFFFFFF, dark: 0x1C1B19)
    static let success = Color(light: 0x11733F, dark: 0x4CC38A)
    static let warning = Color(light: 0x8A6410, dark: 0xD4A64A)
    static let danger = Color(light: 0xB83430, dark: 0xF4817D)
    static let scrim = Color(dynamic: UIColor { $0.userInterfaceStyle == .dark ? UIColor.black.withAlphaComponent(0.5) : UIColor.black.withAlphaComponent(0.24) })
    /// Glass buttons: a light ring that fades down, and a soft drop shadow (lighter on paper).
    static let glassRingTop = Color(overlayLight: 0.12, dark: 0.18)
    static let glassRingBottom = Color(overlayLight: 0.04, dark: 0.05)
    static let glassHighlight = Color(dynamic: UIColor { $0.userInterfaceStyle == .dark ? UIColor.white.withAlphaComponent(0.04) : UIColor.white.withAlphaComponent(0.6) })
    static let shadow = Color(dynamic: UIColor { UIColor.black.withAlphaComponent($0.userInterfaceStyle == .dark ? 0.35 : 0.10) })

    /// Brand bolt colours: only on the app mark.
    static let boltYellow = Color(hex: 0xF7C04A)
    static let boltYellowShade = Color(hex: 0xE9A42C)
    static let boltBlue = Color(hex: 0x4C96EA)
    static let boltBlueShade = Color(hex: 0x2A78D6)

    static let gutter: CGFloat = 20
    static let mono = Font.system(size: 13, design: .monospaced)
    static let monoSmall = Font.system(size: 12, design: .monospaced)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }

    init(dynamic color: UIColor) { self.init(uiColor: color) }

    /// One colour for the light appearance, another for the dark.
    init(light: UInt32, dark: UInt32) {
        self.init(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }

    /// Hairlines and overlays: black at `overlayLight` on paper, white at `dark` on charcoal.
    init(overlayLight: Double, dark: Double) {
        self.init(uiColor: UIColor {
            $0.userInterfaceStyle == .dark ? UIColor.white.withAlphaComponent(dark) : UIColor.black.withAlphaComponent(overlayLight)
        })
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

/// Soft spring press: scale 0.96, nothing bouncy.
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.9), value: configuration.isPressed)
    }
}

/// A glass circular icon button (44pt): a vertical gradient lighter at the top, a 1pt light ring and an inner highlight.
struct GlassCircle: View {
    var systemName: String
    var size: CGFloat = 44
    var iconSize: CGFloat = 17

    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [Pi.surface3, Pi.surface1], startPoint: .top, endPoint: .bottom))
            Circle().strokeBorder(LinearGradient(colors: [Pi.glassRingTop, Pi.glassRingBottom], startPoint: .top, endPoint: .bottom), lineWidth: 1)
            Circle().inset(by: 1.5).stroke(Pi.glassHighlight, lineWidth: 1).blur(radius: 0.5)
            Image(systemName: systemName).font(.system(size: iconSize, weight: .medium)).foregroundStyle(Pi.ink1)
        }
        .frame(width: size, height: size)
        .shadow(color: Pi.shadow, radius: 6, y: 3)
    }
}

struct GlassButton: View {
    var systemName: String
    var label: String
    var action: () -> Void

    var body: some View {
        Button(action: action) { GlassCircle(systemName: systemName) }
            .buttonStyle(PressScale())
            .frame(minWidth: 48, minHeight: 48)
            .contentShape(Rectangle())
            .accessibilityLabel(label)
    }
}

/// The Pi-Bolt logo's bolt (docs/images/logo.svg), drawn from its four polygons.
struct BrandMark: View {
    var size: CGFloat = 44

    var body: some View {
        Canvas { context, canvasSize in
            // logo.svg: an 800-unit square; the bolt spans x 220...600 and y 100...700.
            let scale = min(canvasSize.width, canvasSize.height) / 600
            let ox = canvasSize.width / 2 - 410 * scale
            let oy = canvasSize.height / 2 - 400 * scale
            func polygon(_ points: [(CGFloat, CGFloat)], _ color: Color) {
                var path = Path()
                path.move(to: CGPoint(x: ox + points[0].0 * scale, y: oy + points[0].1 * scale))
                for p in points.dropFirst() { path.addLine(to: CGPoint(x: ox + p.0 * scale, y: oy + p.1 * scale)) }
                path.closeSubpath()
                context.fill(path, with: .color(color))
            }
            polygon([(540, 100), (220, 460), (390, 460)], Pi.boltYellow)
            polygon([(540, 100), (390, 460), (430, 320)], Pi.boltYellowShade)
            polygon([(430, 320), (390, 460), (300, 700)], Pi.boltBlue)
            polygon([(430, 320), (300, 700), (600, 320)], Pi.boltBlueShade)
        }
        .frame(width: size, height: size)
        .accessibilityLabel("Pi-Bolt")
    }
}

/// A rounded surface a step above the canvas, with a hairline.
struct Card<Content: View>: View {
    var radius: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        content
            .background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(Pi.surface1))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Pi.line, lineWidth: 1))
    }
}

/// A short-lived message at the bottom of a screen.
struct Toast: Equatable, Identifiable {
    enum Kind { case info, warning, error }
    let id = UUID()
    var text: String
    var kind: Kind = .info
}

struct ToastView: View {
    var toast: Toast

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(toast.text).font(.system(size: 15)).foregroundStyle(Pi.ink1).lineLimit(3)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Capsule().fill(Pi.surface3))
        .overlay(Capsule().strokeBorder(Pi.lineStrong, lineWidth: 1))
        .shadow(color: Pi.shadow, radius: 12, y: 4)
        .padding(.horizontal, Pi.gutter)
    }

    private var color: Color {
        switch toast.kind {
        case .info: Pi.ink3
        case .warning: Pi.warning
        case .error: Pi.danger
        }
    }
}

/// "5m", "2h", "3d" for a time in the past.
func relativeShort(_ date: Date, now: Date = Date()) -> String {
    let seconds = max(0, Int(now.timeIntervalSince(date)))
    if seconds < 60 { return "now" }
    if seconds < 3600 { return "\(seconds / 60)m" }
    if seconds < 86400 { return "\(seconds / 3600)h" }
    return "\(seconds / 86400)d"
}
