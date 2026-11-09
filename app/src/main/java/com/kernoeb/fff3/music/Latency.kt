package com.kernoeb.fff3.music

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How far ahead the beat clock is read, so a light change shows when its beat is heard. */
object LightTiming {

    /** Used until the link has measured its own write time. */
    const val DEFAULT_LINK_S = 0.060

    /**
     * The camera-measured light delay of these strips, or null if none is measured. Music sends one
     * colour stream to all of them, so it can only follow their average.
     */
    fun measuredMs(delays: Map<String, Int>, addresses: List<String>): Int? =
        addresses.mapNotNull { delays[it] }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    /**
     * All values in seconds. [link] is the time from send until the strip shows the colour (measured
     * with the camera, or else only the write time), [capture] how stale the audio is when analysed,
     * [output] how long the phone takes to play captured sound (lights must wait for it), and
     * [lightDelay] the user's fine-tune (positive makes the lights later).
     */
    fun lead(link: Double, capture: Double, output: Double, lightDelay: Double): Double =
        link + capture - output - lightDelay
}

/**
 * Finds the delay between 2 onset envelopes of the same music, one frame per value: for example
 * captured phone audio and what the microphone heard from the speaker.
 */
object DelayCorrelation {

    /**
     * How many frames [late] lags behind [early], with a fraction, between [minLag] and [maxLag].
     * Returns NaN when there is no clear single peak, for example when the microphone heard
     * nothing of the music. Uses the first [count] values of each array.
     */
    fun findLag(early: FloatArray, late: FloatArray, count: Int, minLag: Int, maxLag: Int, scores: FloatArray): Float {
        val lags = maxLag - minLag + 1
        require(scores.size >= lags && count <= early.size && count <= late.size)
        var meanEarly = 0f
        var meanLate = 0f
        for (i in 0 until count) {
            meanEarly += early[i]
            meanLate += late[i]
        }
        meanEarly /= count
        meanLate /= count

        var best = -1
        for (index in 0 until lags) {
            val lag = minLag + index
            val from = maxOf(0, -lag)
            val to = minOf(count, count - lag)
            var sum = 0f
            var energyEarly = 0f
            var energyLate = 0f
            for (i in from until to) {
                val a = early[i] - meanEarly
                val b = late[i + lag] - meanLate
                sum += a * b
                energyEarly += a * a
                energyLate += b * b
            }
            val norm = sqrt(energyEarly * energyLate)
            scores[index] = if (to - from >= MIN_OVERLAP && norm > 1e-12f) sum / norm else 0f
            if (best < 0 || scores[index] > scores[best]) best = index
        }
        if (best < 0 || scores[best] < MIN_PEAK) return Float.NaN

        // Music repeats, so a second peak one beat away is common: refuse unless ours clearly wins.
        var second = 0f
        for (index in 0 until lags) {
            if (index in best - PEAK_WIDTH..best + PEAK_WIDTH) continue
            val left = if (index > 0) scores[index - 1] else Float.NEGATIVE_INFINITY
            val right = if (index < lags - 1) scores[index + 1] else Float.NEGATIVE_INFINITY
            if (scores[index] >= left && scores[index] >= right && scores[index] > second) second = scores[index]
        }
        if (scores[best] < second * PEAK_RATIO) return Float.NaN

        var shift = 0f
        if (best in 1 until lags - 1) {
            val a = scores[best - 1]
            val b = scores[best]
            val c = scores[best + 1]
            val curve = a - 2 * b + c
            if (curve < 0f) shift = (0.5f * (a - c) / curve).coerceIn(-0.5f, 0.5f)
        }
        return minLag + best + shift
    }

    private const val MIN_OVERLAP = 64
    private const val MIN_PEAK = 0.3f
    private const val PEAK_RATIO = 1.3f
    private const val PEAK_WIDTH = 3
}
