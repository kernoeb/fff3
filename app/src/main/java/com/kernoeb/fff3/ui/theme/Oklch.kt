package com.kernoeb.fff3.ui.theme

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OKLCH colour maths, enough to build a tonal palette from one seed colour.
 * Material's own colour utilities are not public in Compose, and OKLCH is far smaller than HCT.
 * Works on `0xAARRGGBB` ints so it has no Compose dependency.
 */
internal object Oklch {

    /** [l] lightness 0..1, [c] chroma (0 to about 0.37), [h] hue in degrees. */
    data class Lch(val l: Double, val c: Double, val h: Double)

    fun fromArgb(argb: Int): Lch {
        val r = toLinear((argb shr 16) and 0xFF)
        val g = toLinear((argb shr 8) and 0xFF)
        val b = toLinear(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val hue = Math.toDegrees(atan2(okB, okA)).let { if (it < 0) it + 360 else it }
        return Lch(okL, hypot(okA, okB), hue)
    }

    /** The closest sRGB colour: chroma is reduced until the colour fits, lightness and hue are kept. */
    fun toArgb(l: Double, c: Double, h: Double): Int {
        val lightness = l.coerceIn(0.0, 1.0)
        if (inGamut(lightness, c, h)) return pack(lightness, c, h)
        var low = 0.0
        var high = c
        repeat(20) {
            val mid = (low + high) / 2
            if (inGamut(lightness, mid, h)) low = mid else high = mid
        }
        return pack(lightness, low, h)
    }

    private fun linearRgb(l: Double, c: Double, h: Double): DoubleArray {
        val rad = Math.toRadians(h)
        val a = c * cos(rad)
        val b = c * sin(rad)
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
            -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
            -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
        )
    }

    private fun inGamut(l: Double, c: Double, h: Double): Boolean =
        linearRgb(l, c, h).all { it in -1e-4..1.0001 }

    private fun pack(l: Double, c: Double, h: Double): Int {
        val (r, g, b) = linearRgb(l, c, h).map(::toSrgbByte)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun toLinear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgbByte(linear: Double): Int {
        val v = linear.coerceIn(0.0, 1.0)
        val srgb = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
        return (srgb * 255).roundToInt().coerceIn(0, 255)
    }
}
