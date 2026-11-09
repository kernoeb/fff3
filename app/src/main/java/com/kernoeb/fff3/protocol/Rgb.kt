package com.kernoeb.fff3.protocol

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** A colour as 3 bytes, without alpha. [packed] is `0xRRGGBB`. */
@JvmInline
value class Rgb(val packed: Int) {

    init {
        require(packed in 0..0xFFFFFF) { "Not a 24-bit colour: $packed" }
    }

    val red: Int get() = (packed shr 16) and 0xFF
    val green: Int get() = (packed shr 8) and 0xFF
    val blue: Int get() = packed and 0xFF

    fun toHex(): String = "#%06X".format(packed)

    override fun toString(): String = toHex()

    companion object {
        val White = Rgb(0xFFFFFF)
        val Black = Rgb(0x000000)

        fun of(red: Int, green: Int, blue: Int): Rgb = Rgb(
            (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255),
        )

        /** [hue] in degrees (any value, wrapped), [saturation] and [value] in 0..1. */
        fun fromHsv(hue: Float, saturation: Float = 1f, value: Float = 1f): Rgb {
            val h = ((hue % 360f) + 360f) % 360f / 60f
            val s = saturation.coerceIn(0f, 1f)
            val v = value.coerceIn(0f, 1f)
            val sector = h.toInt()
            val f = h - sector
            val p = v * (1 - s)
            val q = v * (1 - s * f)
            val t = v * (1 - s * (1 - f))
            val (r, g, b) = when (sector) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            return of((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
        }

        /**
         * Approximates a white of the given colour temperature with the RGB LEDs.
         * The strips have no white channel, so this is the only way to show warm or cool white.
         * Uses Tanner Helland's fit of the black-body curve, good from 1,000 K to 40,000 K.
         */
        fun fromKelvin(kelvin: Int): Rgb {
            val t = kelvin.coerceIn(1000, 40000) / 100.0
            val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
            val green = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661
            else 288.1221695283 * (t - 60).pow(-0.0755148492)
            val blue = when {
                t >= 66 -> 255.0
                t <= 19 -> 0.0
                else -> 138.5177312231 * ln(t - 10) - 305.0447927307
            }
            return of(red.roundToInt(), green.roundToInt(), blue.roundToInt())
        }
    }
}
