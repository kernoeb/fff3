package com.kernoeb.fff3.calibrate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The maths of the camera light delay calibration, free of Android so it can be tested. Times are in nanoseconds. */
object LightDelayMath {

    /** A colour seen by the camera, as 2 chroma values, so brightness changes count less. */
    data class Level(val u: Double, val v: Double)

    /** One camera frame: when the box rows were lit, and their mean chroma. */
    data class Frame(val time: Long, val u: Double, val v: Double)

    /** [delayMs] is the median of the [kept] flashes; [spreadMs] their standard deviation. */
    data class Result(val delayMs: Double, val spreadMs: Double, val kept: Int, val total: Int)

    /**
     * When the box rows saw the light: the middle of their exposure. [timestamp] is the start of
     * exposure of the first sensor row, and with a rolling shutter each lower row starts later.
     */
    fun frameTime(timestamp: Long, exposure: Long, skew: Long, sensorRow: Double): Long =
        timestamp + exposure / 2 + (skew * sensorRow.coerceIn(0.0, 1.0)).roundToLong()

    /**
     * Where [row] (0 top, 1 bottom) of an output image lies on the sensor. An output wider than
     * the sensor is a crop of its middle rows, so its rows span less of the readout.
     */
    fun sensorRow(row: Double, outWidth: Int, outHeight: Int, sensorWidth: Int, sensorHeight: Int): Double {
        val outAspect = outWidth.toDouble() / outHeight
        val sensorAspect = sensorWidth.toDouble() / sensorHeight
        val shown = if (outAspect > sensorAspect) sensorAspect / outAspect else 1.0
        return 0.5 + (row - 0.5) * shown
    }

    /**
     * Maps a point of the upright preview (0..1 each way, y down) to the image the camera gives,
     * which must be turned [rotation] degrees clockwise to be upright.
     */
    fun previewToImage(x: Double, y: Double, rotation: Int): Pair<Double, Double> = when (rotation) {
        90 -> y to 1 - x
        180 -> 1 - x to 1 - y
        270 -> 1 - y to x
        else -> x to y
    }

    /** How far [frame] has moved from [from] towards [to]: 0 at [from], 1 at [to]. */
    fun progress(frame: Frame, from: Level, to: Level): Double {
        val du = to.u - from.u
        val dv = to.v - from.v
        val length = du * du + dv * dv
        if (length == 0.0) return 0.0
        return ((frame.u - from.u) * du + (frame.v - from.v) * dv) / length
    }

    /** Distance between 2 levels, in the camera's chroma units. */
    fun separation(a: Level, b: Level): Double = sqrt((a.u - b.u) * (a.u - b.u) + (a.v - b.v) * (a.v - b.v))

    /**
     * The time [values] first goes from below 0.5 to 0.5 or more, interpolated between the 2 frames.
     * The later frame must come after [from]. Null if it never crosses.
     */
    fun crossingTime(times: LongArray, values: DoubleArray, from: Long): Long? {
        for (i in 1 until times.size) {
            if (times[i] <= from) continue
            val before = values[i - 1]
            val after = values[i]
            if (before < 0.5 && after >= 0.5) {
                val fraction = (0.5 - before) / (after - before)
                return times[i - 1] + ((times[i] - times[i - 1]) * fraction).roundToLong()
            }
        }
        return null
    }

    /**
     * Milliseconds from [sentAt] until the strip is half way from [from] to [to], using the frames
     * up to [until]. Null when the strip never got there, for example when the write was lost.
     */
    fun flashDelayMs(frames: List<Frame>, sentAt: Long, until: Long, from: Level, to: Level): Double? {
        val window = frames.filter { it.time in (sentAt - LOOK_BACK_NS) until until }.sortedBy { it.time }
        if (window.size < 2) return null
        val times = LongArray(window.size) { window[it].time }
        val values = DoubleArray(window.size) { progress(window[it], from, to) }
        val crossing = crossingTime(times, values, sentAt) ?: return null
        return (crossing - sentAt) / 1e6
    }

    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /**
     * Drops values more than 3 robust standard deviations from the median. [floor] stops a run of
     * equal values from rejecting everything else.
     */
    fun withoutOutliers(values: List<Double>, floor: Double): List<Double> {
        if (values.size < 3) return values
        val middle = median(values)
        val sigma = max(MAD_TO_SIGMA * median(values.map { abs(it - middle) }), floor)
        return values.filter { abs(it - middle) <= OUTLIER_SIGMAS * sigma }
    }

    /** Null when there is nothing to summarise. */
    fun summarize(delaysMs: List<Double>, floorMs: Double = 1.0): Result? {
        if (delaysMs.isEmpty()) return null
        val kept = withoutOutliers(delaysMs, floorMs)
        val mean = kept.average()
        val spread = sqrt(kept.sumOf { (it - mean) * (it - mean) } / kept.size)
        return Result(median(kept), spread, kept.size, delaysMs.size)
    }

    /** Frames this long before a send still count, as the frame before the change. */
    private const val LOOK_BACK_NS = 100_000_000L
    private const val MAD_TO_SIGMA = 1.4826
    private const val OUTLIER_SIGMAS = 3.0
}
