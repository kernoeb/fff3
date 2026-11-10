import SwiftUI
import Fff3Core

/// Colours for both appearances, kept in code so the app needs no asset catalog.
struct Theme {
    let background: Color
    let surface: Color
    let text: Color
    let dim: Color
    let rule: Color
    /// How much of `background` to wash over the blur. Dark needs more: light
    /// text over a bright wallpaper is the case that breaks first.
    let glassWash: Double
    let danger: Color
    let ok: Color
    let warn: Color

    static func of(_ scheme: ColorScheme) -> Theme { scheme == .dark ? .dark : .light }

    static let dark = Theme(
        background: Color(hex: 0x0F1115),
        surface: Color(hex: 0x171A21),
        text: Color(hex: 0xE6E8EC),
        dim: Color(hex: 0x8A909B),
        rule: Color(hex: 0x262A35),
        glassWash: 0.66,
        danger: Color(hex: 0xF0595B),
        ok: Color(hex: 0x4CC38A),
        warn: Color(hex: 0xF2A93B)
    )

    static let light = Theme(
        background: Color(hex: 0xFBFBFD),
        surface: Color(hex: 0xFFFFFF),
        text: Color(hex: 0x14161A),
        dim: Color(hex: 0x6B7280),
        rule: Color(hex: 0xE3E6EC),
        glassWash: 0.5,
        danger: Color(hex: 0xD93A3C),
        ok: Color(hex: 0x1F9D63),
        warn: Color(hex: 0xC77A0A)
    )

    /// The strip's colour as an accent that stays readable: very dark or very pale colours are
    /// lifted towards a mid tone, so white text and thin strokes keep their contrast.
    func accent(_ rgb: Rgb) -> Color {
        let (hue, saturation) = accentHsb(rgb)
        if saturation == 0 { return text.opacity(0.85) }
        return Color(hue: Double(hue), saturation: Double(saturation), brightness: 0.95)
    }

    /// Text on an accent fill: dark on yellow, green or cyan, white on the rest.
    func onAccent(_ rgb: Rgb) -> Color {
        let (hue, saturation) = accentHsb(rgb)
        if saturation == 0 { return background }
        let c = Rgb.fromHsv(hue * 360, saturation: saturation, value: 0.95)
        let luminance = 0.2126 * Double(c.red) + 0.7152 * Double(c.green) + 0.0722 * Double(c.blue)
        return luminance > 150 ? Color(hex: 0x111317) : .white
    }

    private func accentHsb(_ rgb: Rgb) -> (Float, Float) {
        let hsv = rgb.hsv
        if hsv.saturation < 0.12 { return (0, 0) }
        return (hsv.hue / 360, min(max(hsv.saturation, 0.45), 0.85))
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }

    init(_ rgb: Rgb) { self.init(hex: UInt32(rgb.packed)) }
}
