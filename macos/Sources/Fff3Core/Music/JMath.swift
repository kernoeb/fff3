import Foundation

/// Maths with the exact semantics of `kotlin.math` on the JVM, so the port gives the same numbers.
/// Kotlin's Float functions compute in Double and round once, and its rounding goes half up.
public enum JMath {
    @inline(__always) public static func ln(_ x: Float) -> Float { Float(Foundation.log(Double(x))) }
    @inline(__always) public static func exp(_ x: Float) -> Float { Float(Foundation.exp(Double(x))) }
    @inline(__always) public static func log10(_ x: Float) -> Float { Float(Foundation.log10(Double(x))) }
    @inline(__always) public static func sqrt(_ x: Float) -> Float { Float(Foundation.sqrt(Double(x))) }
    @inline(__always) public static func pow(_ x: Float, _ y: Float) -> Float { Float(Foundation.pow(Double(x), Double(y))) }
    @inline(__always) public static func floor(_ x: Float) -> Float { x.rounded(.down) }
    @inline(__always) public static func ceil(_ x: Float) -> Float { x.rounded(.up) }

    /// `Float.toInt()`: towards zero, NaN gives 0, out of range saturates.
    @inline(__always) public static func toInt(_ x: Float) -> Int {
        if x.isNaN { return 0 }
        if x >= 2_147_483_648 { return Int(Int32.max) }
        if x <= -2_147_483_648 { return Int(Int32.min) }
        return Int(x)
    }

    /// `Double.toLong()`.
    @inline(__always) public static func toLong(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        if x >= 9.2e18 { return Int.max }
        if x <= -9.2e18 { return Int.min }
        return Int(x)
    }

    /// `Double.toInt()`.
    @inline(__always) public static func toInt(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        if x >= 2_147_483_647 { return Int(Int32.max) }
        if x <= -2_147_483_648 { return Int(Int32.min) }
        return Int(x)
    }

    /// `Math.round(double)`: the nearest whole number, halves going up.
    @inline(__always) public static func roundToLong(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        let f = x.rounded(.down)
        return toLong(x - f >= 0.5 ? f + 1 : f)
    }

    @inline(__always) public static func roundToInt(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        let f = x.rounded(.down)
        return toInt(x - f >= 0.5 ? f + 1 : f)
    }

    /// `Math.round(float)`.
    @inline(__always) public static func roundToInt(_ x: Float) -> Int {
        if x.isNaN { return 0 }
        let f = x.rounded(.down)
        return toInt(x - f >= 0.5 ? f + 1 : f)
    }

    /// `Math.floorMod(long, long)`.
    @inline(__always) public static func floorMod(_ x: Int, _ y: Int) -> Int {
        let r = x % y
        return (r != 0 && (r < 0) != (y < 0)) ? r + y : r
    }
}
