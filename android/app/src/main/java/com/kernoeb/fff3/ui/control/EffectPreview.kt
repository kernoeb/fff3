package com.kernoeb.fff3.ui.control

import com.kernoeb.fff3.protocol.EffectInfo
import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A rough animation of a built-in effect, for the small swatch next to its name.
 * The strips cannot tell us what an effect looks like, so this reads the effect's name and group.
 */
class EffectPreview private constructor(val colors: List<Rgb>, val motion: Motion) {

    enum class Motion {
        /** One colour, no change. */
        Static,
        /** Hard switches from one colour to the next. */
        Jump,
        /** Flashes on and off, one colour per flash. */
        Strobe,
        /** Smooth blend from one colour to the next. */
        Fade,
        /** One colour that breathes in and out. */
        Breathe,
        /** Colours that travel along the strip. */
        Travel,
    }

    /** True when the swatch should show several LEDs along the strip, not one colour. */
    val isSpatial: Boolean get() = motion == Motion.Travel

    /**
     * The colour at [timeMillis], at [position] 0..1 along the strip.
     * [periodMillis] is how long one colour lasts: shorter means faster.
     */
    fun colorAt(timeMillis: Long, periodMillis: Long, position: Float = 0f): Rgb {
        val period = periodMillis.coerceAtLeast(1)
        val phase = timeMillis.toDouble() / period
        val count = colors.size
        return when (motion) {
            Motion.Static -> colors[0]
            Motion.Jump -> colors[Math.floorMod(floor(phase).toLong(), count.toLong()).toInt()]
            Motion.Strobe -> {
                val step = floor(phase * 2).toLong()
                if (step % 2 == 1L) Rgb.Black else colors[Math.floorMod(step / 2, count.toLong()).toInt()]
            }
            Motion.Fade -> {
                val index = floor(phase).toLong()
                val from = colors[Math.floorMod(index, count.toLong()).toInt()]
                val to = colors[Math.floorMod(index + 1, count.toLong()).toInt()]
                blend(from, to, (phase - index).toFloat())
            }
            Motion.Breathe -> {
                val level = ((1 - cos(phase * PI)) / 2).toFloat()
                blend(Rgb.Black, colors[0], level)
            }
            Motion.Travel -> {
                val shifted = phase + position * count
                colors[Math.floorMod(floor(shifted).toLong(), count.toLong()).toInt()]
            }
        }
    }

    companion object {
        private val Red = Rgb(0xFF0000)
        private val Green = Rgb(0x00FF00)
        private val Blue = Rgb(0x0000FF)
        private val Yellow = Rgb(0xFFFF00)
        private val Cyan = Rgb(0x00FFFF)
        private val Purple = Rgb(0xFF00FF)
        private val White = Rgb.White
        private val Seven = listOf(Red, Green, Blue, Yellow, Cyan, Purple, White)
        private val Three = listOf(Red, Green, Blue)

        private val words = mapOf(
            "red" to Red, "green" to Green, "blue" to Blue, "yellow" to Yellow,
            "cyan" to Cyan, "purple" to Purple, "white" to White,
        )
        private val letters = mapOf(
            'R' to Red, 'G' to Green, 'B' to Blue, 'Y' to Yellow, 'C' to Cyan, 'P' to Purple, 'W' to White,
        )
        private val letterCode = Regex("""\b([RGBYCPW](?:-[RGBYCPW])+)\b""")

        fun of(effect: EffectInfo): EffectPreview {
            val name = effect.name
            val lower = name.lowercase()
            val colors = colorsIn(name, lower)
            val motion = when {
                effect.group == "Static" || lower.startsWith("static") -> Motion.Static
                "strobe" in lower || "flash" in lower || "warning" in lower -> Motion.Strobe
                "jump" in lower || "energy" in lower -> Motion.Jump
                "marquee" in lower || "race" in lower || "wave" in lower || "magic" in lower ||
                    "meteor" in lower || "time machine" in lower -> Motion.Travel
                colors.size == 1 && ("gradual" in lower || "fade" in lower) -> Motion.Breathe
                else -> Motion.Fade
            }
            // A travelling single colour needs a dark gap to look like it moves.
            val shown = if (motion == Motion.Travel && colors.size == 1) colors + Rgb.Black else colors
            return EffectPreview(shown, motion)
        }

        private fun colorsIn(name: String, lower: String): List<Rgb> {
            letterCode.find(name)?.let { match ->
                return match.value.split('-').mapNotNull { letters[it.single()] }
            }
            if ("seven" in lower || "7-colour" in lower) return Seven
            if ("three" in lower) return Three
            val named = words.entries
                .mapNotNull { (word, rgb) -> lower.indexOf(word).takeIf { it >= 0 }?.let { it to rgb } }
                .sortedBy { it.first }
                .map { it.second }
            return when {
                named.isNotEmpty() -> named
                "valentine" in lower -> listOf(Red, Purple, White)
                "hallow" in lower -> listOf(Rgb(0xFF6A00), Purple)
                "firework" in lower -> listOf(Red, Yellow, Blue, White)
                else -> Seven
            }
        }

        /** Speed 0..100 to the time one colour lasts in the preview. */
        fun periodFor(speedPercent: Float): Long =
            (1_600 - speedPercent.coerceIn(0f, 100f) * 13.5f).roundToInt().toLong()

        fun blend(from: Rgb, to: Rgb, fraction: Float): Rgb {
            val t = fraction.coerceIn(0f, 1f)
            fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
            return Rgb.of(mix(from.red, to.red), mix(from.green, to.green), mix(from.blue, to.blue))
        }
    }
}
