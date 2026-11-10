package com.kernoeb.fff3.music

import com.kernoeb.fff3.data.BeatColors
import com.kernoeb.fff3.data.MusicStyle
import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

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
    private var lockMix = 0f
    private var seenEpoch = -1
    private var lastBeat = Long.MIN_VALUE
    private var bar = 0
    private var accent = 0f
    private var beatHue = baseHue
    private var tempoHue = Float.NaN
    private val energy = SongEnergy()

    /** 0..1, how intense this part of the song is compared with the last 2 minutes. */
    val songEnergy: Float get() = energy.value

    /** The colour of the last [render], for the visualiser. */
    var color: Rgb = Rgb.Black
        private set

    /** The colour of the last [render] at full brightness. */
    var fullColor: Rgb = Rgb.Black
        private set

    /** 0..1, the beat flash of the last [render], for the visualiser. */
    val beatFlash: Float get() = flash

    /** True when the last [render] started a beat of the beat lock clock. */
    var beatEdge = false
        private set

    /** Beat lock phase of the last [render], 0..1 from the beat, or -1 when not locked. */
    var beatPhase = -1f
        private set

    /** 0 to 3: the beat of the bar [beatPhase] counts from. */
    var beatInBar = 0
        private set

    /** Seconds from the last [render] to the next beat it will show, or infinity when not locked. */
    var untilBeat = Double.POSITIVE_INFINITY
        private set

    /**
     * [clock] and [lead] are only used by [MusicStyle.BeatLock]: the colour shows the clock
     * [lead] seconds after the newest audio, so it is visible when its beat is heard.
     */
    fun render(
        frame: MusicFrame,
        style: MusicStyle,
        dt: Float,
        clock: BeatTracker? = null,
        lead: Double = 0.0,
        beatColors: BeatColors = BeatColors.Bars,
    ): Rgb {
        beatEdge = false
        beatPhase = -1f
        untilBeat = Double.POSITIVE_INFINITY
        energy.update(frame.rms, frame.onset, dt)
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
            MusicStyle.BeatLock -> {
                val locked = clock != null && clock.state == BeatLockState.Locked
                lockMix += ((if (locked) 1f else 0f) - lockMix) * (1f - decay(dt, LOCK_FADE))
                var beatValue = 0f
                var beatSaturation = 1f
                flash = 0f
                if (clock != null && locked) {
                    val beats = clock.beatsAt(clock.time + lead)
                    val whole = floor(beats).toLong()
                    // A new clock starts mid-beat: show that beat as if it had fired, without a flash.
                    if (clock.epoch != seenEpoch) {
                        seenEpoch = clock.epoch
                        lastBeat = whole
                        startBeat(whole)
                    } else if (whole > lastBeat) {
                        lastBeat = whole
                        beatEdge = true
                        startBeat(whole)
                    }
                    // A phase correction can step back over a beat already shown: hold it, never flash twice.
                    val phase = if (whole < lastBeat) 0.0 else beats - whole
                    beatPhase = phase.toFloat()
                    untilBeat = (lastBeat + 1 - beats) * clock.period
                    // Calm parts glow longer and dimmer; a chorus or a drop gets short, full flashes.
                    val intensity = energy.value
                    val decayBeats = CALM_DECAY_BEATS + (DECAY_BEATS - CALM_DECAY_BEATS) * intensity
                    val pulse = exp(-phase / decayBeats).toFloat()
                    beatValue = (BEAT_FLOOR + (accent - BEAT_FLOOR) * pulse) * (CALM_PEAK + (1f - CALM_PEAK) * intensity)
                    if (beatInBar == 0) beatSaturation = 1f - DOWNBEAT_WHITE * intensity * pulse
                    flash = accent * pulse * lockMix
                } else {
                    beatHue += FALLBACK_DRIFT * dt
                }
                hue = when (beatColors) {
                    BeatColors.Bars -> beatHue
                    BeatColors.Strip -> baseHue
                    BeatColors.Tempo -> {
                        // While listening, keep the last song's colour rather than drift.
                        if (clock != null && locked) {
                            val target = tempoHue(clock.bpm)
                            tempoHue = if (tempoHue.isNaN()) target else approachAngle(tempoHue, target, dt, TEMPO_HUE_FADE)
                        }
                        if (tempoHue.isNaN()) baseHue else wrap(tempoHue)
                    }
                }
                targetHue = hue
                value = follow(value, frame.level, dt, attack = 0.05f, release = 0.4f)
                val fallback = FALLBACK_FLOOR + FALLBACK_RANGE * value
                v = fallback + (beatValue - fallback) * lockMix
                s = if (beatColors == BeatColors.Strip) {
                    baseSaturation * (1f + (beatSaturation - 1f) * lockMix)
                } else {
                    0.9f + (beatSaturation - 0.9f) * lockMix
                }
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

    /** Each beat steps the hue a little; each bar moves to the next palette colour, with a stronger flash. */
    private fun startBeat(beat: Long) {
        beatInBar = Math.floorMod(beat, 4L).toInt()
        if (beatInBar == 0) bar++
        beatHue = PALETTE[bar % PALETTE.size] + beatInBar * BEAT_HUE_STEP
        accent = if (beatInBar == 0) 1f else OFFBEAT_ACCENT
    }

    /**
     * One full turn of the colour wheel per doubling of tempo, so 64 and 128 BPM share a colour:
     * the beat clock may lock on either for the same song.
     */
    private fun tempoHue(bpm: Float): Float {
        var folded = bpm.coerceAtLeast(1f)
        while (folded < TEMPO_HUE_BASE) folded *= 2f
        while (folded >= TEMPO_HUE_BASE * 2f) folded /= 2f
        return (ln(folded / TEMPO_HUE_BASE) / ln(2f)) * 360f
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
        val PALETTE = floatArrayOf(285f, 330f, 15f, 200f, 165f, 245f)
        const val BEAT_HUE_STEP = 18f
        const val OFFBEAT_ACCENT = 0.62f
        const val DECAY_BEATS = 0.3
        const val BEAT_FLOOR = 0.12f
        const val CALM_PEAK = 0.4f
        const val CALM_DECAY_BEATS = 0.55
        const val DOWNBEAT_WHITE = 0.3f
        const val LOCK_FADE = 0.4f
        const val FALLBACK_DRIFT = 5f
        const val FALLBACK_FLOOR = 0.15f
        const val FALLBACK_RANGE = 0.45f
        const val TEMPO_HUE_BASE = 80f
        const val TEMPO_HUE_FADE = 1f
        const val GAMMA = 1.8f
        const val MIN_OUTPUT = 0.01f
    }
}
