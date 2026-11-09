package com.kernoeb.fff3.ui.control

import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Hue in degrees 0..360, saturation and value in 0..1. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float)

/** The inverse of [Rgb.fromHsv]. Pure Kotlin, so it runs in JVM tests. Grey gives hue 0. */
fun Rgb.toHsv(): Hsv {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val hue = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Hsv(hue, if (maxC == 0f) 0f else delta / maxC, maxC)
}

/**
 * Maps between a point on the colour disc and a hue and saturation.
 * Hue 0 is at 3 o'clock and grows clockwise, like a sweep gradient on screen (y points down).
 * Saturation is the distance from the centre, 0 in the middle and 1 on the rim.
 */
object WheelGeometry {

    /** Hue and saturation for the point ([dx], [dy]) from the centre of a disc of [radius]. */
    fun hueSaturationAt(dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val hue = if (angle < 0f) angle + 360f else angle
        val saturation = if (radius <= 0f) 0f else (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        return hue to saturation
    }

    /** The offset from the centre for [hue] and [saturation]. Returns x and y. */
    fun offsetOf(hue: Float, saturation: Float, radius: Float): Pair<Float, Float> {
        val rad = Math.toRadians(hue.toDouble())
        val distance = saturation.coerceIn(0f, 1f) * radius
        return (cos(rad) * distance).toFloat() to (sin(rad) * distance).toFloat()
    }

    /** The shortest way to turn from [from] to [to], in degrees, between -180 and 180. */
    fun shortestHueDelta(from: Float, to: Float): Float {
        val delta = ((to - from) % 360f + 540f) % 360f - 180f
        return if (delta == -180f) 180f else delta
    }
}
