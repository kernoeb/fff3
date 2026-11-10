// Draws the fff3 app icon and writes an .iconset.
//
//   swift tools/make-icon.swift AppIcon.iconset
//
// A dark tile with a short LED strip across it: 5 lights from violet to amber, each with a
// soft glow. Everything is laid out on a 1024 grid and scaled, so it stays sharp at 16 px.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// macOS icons sit inside their canvas: an 824 pt body on a 1024 pt grid.
let grid: CGFloat = 1024
let bodyInset: CGFloat = 100
let bodyRadius: CGFloat = 185
let centre = grid / 2

let groundTop = CGColor(red: 0.098, green: 0.106, blue: 0.137, alpha: 1)
let groundBottom = CGColor(red: 0.039, green: 0.043, blue: 0.059, alpha: 1)
let lights: [CGColor] = [
    CGColor(red: 0.541, green: 0.486, blue: 1.000, alpha: 1),  // #8A7CFF
    CGColor(red: 0.776, green: 0.416, blue: 0.957, alpha: 1),
    CGColor(red: 0.957, green: 0.384, blue: 0.659, alpha: 1),
    CGColor(red: 0.988, green: 0.490, blue: 0.369, alpha: 1),
    CGColor(red: 0.996, green: 0.698, blue: 0.282, alpha: 1),  // amber
]

func render(_ pixels: Int) -> CGImage {
    let context = CGContext(
        data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    )!
    context.interpolationQuality = .high
    context.scaleBy(x: CGFloat(pixels) / grid, y: CGFloat(pixels) / grid)

    let body = CGRect(x: bodyInset, y: bodyInset, width: grid - bodyInset * 2, height: grid - bodyInset * 2)
    let tile = CGPath(roundedRect: body, cornerWidth: bodyRadius, cornerHeight: bodyRadius, transform: nil)
    context.saveGState()
    context.addPath(tile)
    context.clip()
    let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [groundTop, groundBottom] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(gradient, start: CGPoint(x: 0, y: body.maxY), end: CGPoint(x: 0, y: body.minY), options: [])

    // The strip itself: a dark band the lights sit on.
    let band = CGRect(x: 190, y: centre - 70, width: grid - 380, height: 140)
    context.addPath(CGPath(roundedRect: band, cornerWidth: 70, cornerHeight: 70, transform: nil))
    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.06))
    context.fillPath()

    let step = (band.width - 140) / CGFloat(lights.count - 1)
    for (index, color) in lights.enumerated() {
        let x = band.minX + 70 + CGFloat(index) * step
        let point = CGPoint(x: x, y: centre)
        // Glow first, then the bright core on top.
        let glow = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [color.copy(alpha: 0.55)!, color.copy(alpha: 0)!] as CFArray, locations: [0, 1])!
        context.drawRadialGradient(glow, startCenter: point, startRadius: 0, endCenter: point, endRadius: 150, options: [])
        context.setFillColor(color)
        context.fillEllipse(in: CGRect(x: x - 44, y: centre - 44, width: 88, height: 88))
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.55))
        context.fillEllipse(in: CGRect(x: x - 16, y: centre - 16, width: 32, height: 32))
    }
    context.restoreGState()

    // A hairline of light along the edge, the way macOS icons catch it.
    context.addPath(tile)
    context.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.09))
    context.setLineWidth(3)
    context.strokePath()
    return context.makeImage()!
}

func write(_ image: CGImage, to url: URL) {
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        fatalError("cannot write \(url.path)")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("cannot finalize \(url.path)") }
}

let output = URL(filePath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.iconset")
try? FileManager.default.removeItem(at: output)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)

// The set iconutil expects: each point size at 1x and 2x.
for points in [16, 32, 128, 256, 512] {
    write(render(points), to: output.appending(path: "icon_\(points)x\(points).png"))
    write(render(points * 2), to: output.appending(path: "icon_\(points)x\(points)@2x.png"))
}
print("wrote \(output.path)")
