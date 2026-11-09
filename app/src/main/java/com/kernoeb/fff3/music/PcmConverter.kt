package com.kernoeb.fff3.music

/**
 * Turns interleaved PCM with any channel count and rate into mono samples at [outputRate].
 * Keeps its state between calls, so chunks may end in the middle of a frame.
 */
class PcmConverter(private val channels: Int, inputRate: Int, val outputRate: Int) {

    init {
        require(channels >= 1 && inputRate > 0 && outputRate > 0)
    }

    private val step = inputRate.toDouble() / outputRate
    private var sum = 0f
    private var channel = 0
    private var previous = 0f
    // Where the next output sample sits, in input frames: 0 is [previous], 1 is the next frame.
    private var position = 1.0

    /** The most samples [process] can write for [length] input values. */
    fun maxOutput(length: Int): Int = ((length / channels + 1) / step).toInt() + 2

    /** Converts the first [length] values of [input] and returns how many samples it wrote to [out]. */
    fun process(input: FloatArray, length: Int, out: FloatArray): Int {
        var written = 0
        for (i in 0 until length) {
            sum += input[i]
            if (++channel < channels) continue
            val current = sum / channels
            sum = 0f
            channel = 0
            // Linear interpolation is enough: the analyser only looks below 12 kHz.
            while (position <= 1.0) {
                out[written++] = previous + (current - previous) * position.toFloat()
                position += step
            }
            position -= 1.0
            previous = current
        }
        return written
    }
}

/**
 * Says when captured audio has been digital silence for [holdMillis].
 * Blocked apps give exact zeros, so the threshold sits near one step of 16-bit audio.
 */
class SilenceDetector(private val holdMillis: Long, private val threshold: Float = 1e-4f) {
    private var silentSince = -1L

    var silent = false
        private set

    /** [peak] is the loudest absolute sample of the latest block. Returns [silent]. */
    fun update(peak: Float, nowMillis: Long): Boolean {
        if (peak > threshold) {
            silentSince = -1L
            silent = false
        } else {
            if (silentSince < 0) silentSince = nowMillis
            silent = nowMillis - silentSince >= holdMillis
        }
        return silent
    }
}
