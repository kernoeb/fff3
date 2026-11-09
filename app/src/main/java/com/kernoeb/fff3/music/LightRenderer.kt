package com.kernoeb.fff3.music

import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.exp
import kotlin.math.pow

/** How the lights follow the music. */
enum class MusicStyle {
    /** Each beat flashes a new colour, then fades. */
    Pulse,
    /** Colour follows the pitch of the sound: bass is red, bright sound is blue and violet. */
    Spectrum,
    /** One colour, brightness follows loudness. */
    Energy,
    /** Slow colour drift that breathes with the music. */
    Calm,
}

/**
 * Turns [MusicFrame]s into one colour for the strips, with smoothing so it never flickers.
 * Brightness is baked into the colour, so each tick is one coalesced colour frame on the link.
 */
class LightRenderer(
    /** Hue of the strip before music started, in degrees. [MusicStyle.Energy] keeps it. */
    private val baseHue: Float = 260f,
    private val baseSaturation: Float = 1f,
) {
    private var hue = baseHue
    private var targetHue = baseHue
    private var value = 0f
    private var flash = 0f
    private var slowLevel = 0f

    /** The colour of the last [render], for the visualiser. */
    var color: Rgb = Rgb.Black
        private set

    /** The colour of the last [render] at full brightness. */
    var fullColor: Rgb = Rgb.Black
        private set

    /** 0..1, the beat flash of the last [render], for the visualiser. */
    val beatFlash: Float get() = flash

    fun render(frame: MusicFrame, style: MusicStyle, dt: Float): Rgb {
        val beatPeriod = if (frame.bpm > 0f) 60f / frame.bpm else 0.5f
        if (frame.beat) flash = 1f else flash *= decay(dt, beatPeriod * 0.3f)

        val v: Float
        val s: Float
        when (style) {
            MusicStyle.Pulse -> {
                if (frame.beat) targetHue = hue + PULSE_HUE_STEP
                hue = approachAngle(hue, targetHue, dt, 0.05f)
                value = 0.12f + 0.88f * flash + 0.15f * frame.level * (1f - flash)
                v = value
                s = 1f - 0.15f * flash
            }
            MusicStyle.Spectrum -> {
                targetHue = frame.brightness * 280f
                hue = approachAngle(hue, targetHue, dt, 0.25f)
                value = follow(value, frame.level, dt, attack = 0.03f, release = 0.25f)
                v = 0.06f + 0.8f * value + 0.2f * flash
                s = 1f
            }
            MusicStyle.Energy -> {
                hue = baseHue
                value = follow(value, frame.level, dt, attack = 0.02f, release = 0.18f)
                v = 0.04f + 0.86f * value + 0.2f * flash
                s = baseSaturation
            }
            MusicStyle.Calm -> {
                slowLevel = follow(slowLevel, frame.level, dt, attack = 0.8f, release = 2.5f)
                if (frame.beat) targetHue += CALM_BEAT_NUDGE
                targetHue += (CALM_DRIFT + CALM_ENERGY_DRIFT * slowLevel) * dt
                hue = approachAngle(hue, targetHue, dt, 0.6f)
                v = 0.3f + 0.45f * slowLevel
                s = 0.85f
            }
        }
        // LEDs are linear while eyes are not: a power curve makes the fades look even.
        val out = v.coerceIn(0f, 1f).pow(GAMMA).coerceAtLeast(MIN_OUTPUT)
        hue = wrap(hue)
        targetHue = hue + shortest(hue, targetHue)
        color = Rgb.fromHsv(hue, s, out)
        fullColor = Rgb.fromHsv(hue, s, 1f)
        return color
    }

    private fun follow(current: Float, target: Float, dt: Float, attack: Float, release: Float): Float {
        val tau = if (target > current) attack else release
        return target + (current - target) * decay(dt, tau)
    }

    private fun approachAngle(current: Float, target: Float, dt: Float, tau: Float): Float =
        current + shortest(current, target) * (1f - decay(dt, tau))

    private fun decay(dt: Float, tau: Float) = exp(-dt / tau.coerceAtLeast(1e-3f))

    private fun shortest(from: Float, to: Float): Float {
        val d = wrap(to - from)
        return if (d > 180f) d - 360f else d
    }

    private fun wrap(angle: Float) = ((angle % 360f) + 360f) % 360f

    private companion object {
        const val PULSE_HUE_STEP = 47f
        const val CALM_DRIFT = 4f
        const val CALM_ENERGY_DRIFT = 18f
        const val CALM_BEAT_NUDGE = 6f
        const val GAMMA = 1.8f
        const val MIN_OUTPUT = 0.01f
    }
}
