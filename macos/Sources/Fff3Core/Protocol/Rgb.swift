import Foundation

/// A colour as 3 bytes, without alpha. `packed` is `0xRRGGBB`.
public struct Rgb: Hashable, Sendable, Codable, CustomStringConvertible {
    public let packed: Int

    public init(_ packed: Int) {
        precondition((0...0xFFFFFF).contains(packed), "Not a 24-bit colour: \(packed)")
        self.packed = packed
    }

    public var red: Int { (packed >> 16) & 0xFF }
    public var green: Int { (packed >> 8) & 0xFF }
    public var blue: Int { packed & 0xFF }

    public func toHex() -> String { String(format: "#%06X", packed) }
    public var description: String { toHex() }

    public static let white = Rgb(0xFFFFFF)
    public static let black = Rgb(0x000000)

    public static func of(_ red: Int, _ green: Int, _ blue: Int) -> Rgb {
        Rgb((red.clamped(0, 255) << 16) | (green.clamped(0, 255) << 8) | blue.clamped(0, 255))
    }

    /// `hue` in degrees (any value, wrapped), `saturation` and `value` in 0...1.
    public static func fromHsv(_ hue: Float, saturation: Float = 1, value: Float = 1) -> Rgb {
        let h = (hue.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 60
        let s = saturation.clamped(0, 1)
        let v = value.clamped(0, 1)
        let sector = JMath.toInt(h)
        let f = h - Float(sector)
        let p = v * (1 - s)
        let q = v * (1 - s * f)
        let t = v * (1 - s * (1 - f))
        let (r, g, b): (Float, Float, Float) = switch sector {
        case 0: (v, t, p)
        case 1: (q, v, p)
        case 2: (p, v, t)
        case 3: (p, q, v)
        case 4: (t, p, v)
        default: (v, p, q)
        }
        return of(JMath.roundToInt(r * 255), JMath.roundToInt(g * 255), JMath.roundToInt(b * 255))
    }

    /// Approximates a white of the given colour temperature with the RGB LEDs, which have no white
    /// channel. Uses Tanner Helland's fit of the black-body curve, good from 1,000 K to 40,000 K.
    public static func fromKelvin(_ kelvin: Int) -> Rgb {
        let t = Double(kelvin.clamped(1000, 40000)) / 100.0
        let red = t <= 66 ? 255.0 : 329.698727446 * pow(t - 60, -0.1332047592)
        let green = t <= 66 ? 99.4708025861 * log(t) - 161.1195681661
            : 288.1221695283 * pow(t - 60, -0.0755148492)
        let blue: Double = if t >= 66 { 255.0 } else if t <= 19 { 0.0 } else { 138.5177312231 * log(t - 10) - 305.0447927307 }
        return of(JMath.roundToInt(red), JMath.roundToInt(green), JMath.roundToInt(blue))
    }

    /// Hue in degrees, saturation and value 0...1, like `android.graphics.Color.RGBToHSV`.
    public var hsv: (hue: Float, saturation: Float, value: Float) {
        let r = Float(red) / 255, g = Float(green) / 255, b = Float(blue) / 255
        let maxC = max(r, g, b), minC = min(r, g, b)
        let delta = maxC - minC
        var hue: Float = 0
        if delta > 0 {
            if maxC == r { hue = (g - b) / delta } else if maxC == g { hue = 2 + (b - r) / delta } else { hue = 4 + (r - g) / delta }
            hue *= 60
            if hue < 0 { hue += 360 }
        }
        return (hue, maxC == 0 ? 0 : delta / maxC, maxC)
    }
}

extension Comparable {
    /// Like Kotlin's `coerceIn`.
    public func clamped(_ low: Self, _ high: Self) -> Self { self < low ? low : (self > high ? high : self) }
}
