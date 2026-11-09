package com.kernoeb.fff3.music

import com.kernoeb.fff3.ble.BleTuning
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How far ahead the beat clock is read, so a light change shows when its beat is heard. */
object LightTiming {

    /** Used for a strip with no camera value until the link has measured its own write time. */
    const val DEFAULT_LINK_S = 0.060

    /** Used until the queue has measured its own wait: half a frame interval. */
    const val DEFAULT_QUEUE_WAIT_MS = (BleTuning.MIN_FRAME_INTERVAL_MS / 2).toInt()

    /**
     * Seconds from `controller.send` until the strips show the colour. [cameraMs] holds each target
     * strip's camera-measured delay, or null. A camera value starts at the Bluetooth write, so the
     * [queueWaitMs] before that write is added. A strip with no camera value uses [writeMs].
     * Negative times mean not measured yet.
     */
    fun linkSeconds(cameraMs: List<Int?>, queueWaitMs: Int, writeMs: Int): Double {
        val fallback = if (writeMs >= 0) writeMs / 1000.0 else DEFAULT_LINK_S
        val queue = if (queueWaitMs >= 0) queueWaitMs else DEFAULT_QUEUE_WAIT_MS
        val perStrip = cameraMs.map { if (it != null) (it + queue) / 1000.0 else fallback }
        // All strips get the same colour at the same time, so one lead serves them all. The median puts
        // 2 strips equally far off, and one odd strip in a bigger group cannot pull it.
        return median(perStrip) ?: fallback
    }

    /** What the music screen shows: null unless every target strip has a camera value. */
    fun measuredMs(delays: Map<String, Int>, addresses: List<String>): Int? =
        if (addresses.isEmpty() || addresses.any { it !in delays }) null
        else median(addresses.map { delays.getValue(it).toDouble() })?.roundToInt()

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * All values in seconds. [link] is the time from send until the strip shows the colour
     * ([linkSeconds]), [capture] how stale the audio is when analysed,
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
