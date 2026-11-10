import SwiftUI
import Fff3Core

/// A thin track with an accent fill and a round knob. Reports every drag step, so the strip
/// follows the finger; the write queue keeps only the newest value.
struct LineSlider: View {
    @Binding var value: Double
    var range: ClosedRange<Double> = 0...1
    var step: Double? = nil
    let accent: Color
    let theme: Theme
    var onEnd: () -> Void = {}

    var body: some View {
        GeometryReader { geo in
            let width = max(geo.size.width - 14, 1)
            let fraction = (value.clamped(range.lowerBound, range.upperBound) - range.lowerBound) / (range.upperBound - range.lowerBound)
            ZStack(alignment: .leading) {
                Capsule().fill(theme.rule).frame(height: 4).padding(.horizontal, 7)
                Capsule().fill(accent).frame(width: 7 + width * fraction, height: 4)
                Circle()
                    .fill(theme.text)
                    .frame(width: 14, height: 14)
                    .offset(x: width * fraction)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { drag in
                        let f = ((drag.location.x - 7) / width).clamped(0, 1)
                        var next = range.lowerBound + f * (range.upperBound - range.lowerBound)
                        if let step { next = (next / step).rounded() * step }
                        if next != value { value = next }
                    }
                    .onEnded { _ in onEnd() }
            )
        }
        .frame(height: 22)
    }
}

/// A row of choices with one selected: plain labels, the selected one on an accent wash, all in
/// one hairline capsule.
struct PillSwitch<Value: Hashable>: View {
    let options: [(Value, String)]
    @Binding var selection: Value
    let accent: Color
    let theme: Theme

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.0) { option in
                let selected = option.0 == selection
                Text(option.1)
                    .font(.system(size: 12, weight: selected ? .semibold : .regular))
                    .foregroundStyle(selected ? theme.text : theme.dim)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(selected ? accent.opacity(0.22) : .clear))
                    .contentShape(Capsule())
                    .onTapGesture { selection = option.0 }
            }
        }
        .padding(2)
        .overlay(Capsule().strokeBorder(theme.rule, lineWidth: 1))
    }
}

/// A quiet outlined button that washes on hover.
struct QuietButton: View {
    let title: String
    var systemImage: String? = nil
    let theme: Theme
    var accent: Color? = nil
    var prominent = false
    /// Label colour on a prominent fill.
    var onAccent: Color = .white
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 11, weight: .semibold)) }
                Text(title).font(.system(size: 12, weight: .medium))
            }
            .foregroundStyle(prominent ? onAccent : theme.text)
            .padding(.horizontal, 12)
            .frame(height: 28)
            .background(
                RoundedRectangle(cornerRadius: 7)
                    .fill(prominent ? (accent ?? theme.text).opacity(hover ? 0.95 : 0.8) : theme.text.opacity(hover ? 0.08 : 0.03))
            )
            .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(prominent ? .clear : theme.rule, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

/// Hue around the circle, saturation from the centre out. Drag anywhere on it.
struct ColorWheel: View {
    let color: Rgb?
    let theme: Theme
    let onPick: (Rgb) -> Void

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let radius = size / 2
            ZStack {
                Image(decorative: Self.disc, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .clipShape(Circle())
                Circle().strokeBorder(theme.rule, lineWidth: 1)
                if let color {
                    let hsv = color.hsv
                    let angle = Double(hsv.hue) * .pi / 180
                    let r = Double(hsv.saturation) * radius
                    Circle()
                        .strokeBorder(.white, lineWidth: 2.5)
                        .background(Circle().fill(Color(Rgb.fromHsv(hsv.hue, saturation: hsv.saturation))))
                        .frame(width: 18, height: 18)
                        .overlay(Circle().strokeBorder(.black.opacity(0.35), lineWidth: 0.5))
                        .offset(x: cos(angle) * r, y: sin(angle) * r)
                }
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                let dx = drag.location.x - radius
                let dy = drag.location.y - radius
                var hue = atan2(dy, dx) * 180 / .pi
                if hue < 0 { hue += 360 }
                let saturation = min(sqrt(dx * dx + dy * dy) / radius, 1)
                onPick(Rgb.fromHsv(Float(hue), saturation: Float(saturation)))
            })
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

extension ColorWheel {
    /// The wheel drawn once, pixel by pixel, with the same maths that makes the colour sent to the strip.
    static let disc: CGImage = {
        let size = 512
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        let radius = Double(size) / 2
        for y in 0..<size {
            for x in 0..<size {
                let dx = Double(x) + 0.5 - radius
                let dy = Double(y) + 0.5 - radius
                var hue = atan2(dy, dx) * 180 / .pi
                if hue < 0 { hue += 360 }
                let rgb = Rgb.fromHsv(Float(hue), saturation: Float(min(sqrt(dx * dx + dy * dy) / radius, 1)))
                let i = (y * size + x) * 4
                pixels[i] = UInt8(rgb.red)
                pixels[i + 1] = UInt8(rgb.green)
                pixels[i + 2] = UInt8(rgb.blue)
                pixels[i + 3] = 255
            }
        }
        let provider = CGDataProvider(data: Data(pixels) as CFData)!
        return CGImage(
            width: size, height: size, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: size * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )!
    }()
}

/// A coloured dot and plain text, for connection and Bluetooth status.
struct StatusDot: View {
    let color: Color
    var body: some View { Circle().fill(color).frame(width: 7, height: 7) }
}
