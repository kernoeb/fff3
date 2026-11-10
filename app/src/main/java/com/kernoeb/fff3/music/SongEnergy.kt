package com.kernoeb.fff3.music

import kotlin.math.exp
import kotlin.math.log10

/**
 * How intense this moment of the song is: 0 for the calmest part heard lately, 1 for the loudest.
 * It compares loudness and attack density over about 2 seconds with the last [HISTORY_SECONDS],
 * so a chorus or a drop reads high and a verse or a break reads low, whatever the volume.
 */
class SongEnergy {
    private var power = 0.0
    private var attacks = 0.0
    private val history = FloatArray(HISTORY_SECONDS)
    private val sorted = FloatArray(HISTORY_SECONDS)
    private var count = 0
    private var head = 0
    private var sinceSample = 0f
    private var low = 0f
    private var high = 0f

    /** The smoothed result of the last [update], 0..1. */
    var value = UNKNOWN
        private set

    /** [rms] and [onset] come straight from [MusicFrame], before any automatic gain. */
    fun update(rms: Float, onset: Float, dt: Float): Float {
        val keep = exp(-dt / WINDOW_S).toDouble()
        power = power * keep + rms.toDouble() * rms * (1 - keep)
        attacks = attacks * keep + onset * (1 - keep)

        val silent = rms < SILENCE_RMS
        val score = (10 * log10(power + 1e-12) + ATTACK_WEIGHT * 10 * log10(attacks + 1e-9)).toFloat()
        sinceSample += dt
        if (!silent && sinceSample >= 1f) {
            sinceSample = 0f
            history[head] = score
            head = (head + 1) % HISTORY_SECONDS
            if (count < HISTORY_SECONDS) count++
            history.copyInto(sorted, 0, 0, count)
            sorted.sort(0, count)
            low = sorted[(count * LOW_PERCENTILE).toInt()]
            high = sorted[((count - 1) * HIGH_PERCENTILE).toInt()]
        }

        // A song with little dynamics stays near full, rather than stretching small changes.
        val span = maxOf(high - low, MIN_RANGE_DB)
        val target = when {
            silent -> 0f
            count < MIN_HISTORY -> UNKNOWN
            else -> ((score - (high - span)) / span).coerceIn(0f, 1f)
        }
        value += (target - value) * (1f - exp(-dt / SMOOTHING_S))
        return value
    }

    private companion object {
        const val HISTORY_SECONDS = 40
        const val MIN_HISTORY = 6
        const val WINDOW_S = 1.5f
        const val SMOOTHING_S = 0.6f
        const val ATTACK_WEIGHT = 1f
        const val LOW_PERCENTILE = 0.15f
        const val HIGH_PERCENTILE = 0.9f
        const val MIN_RANGE_DB = 6f
        const val SILENCE_RMS = 1e-4f
        const val UNKNOWN = 0.8f
    }
}
