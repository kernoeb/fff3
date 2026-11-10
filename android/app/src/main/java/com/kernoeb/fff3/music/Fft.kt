package com.kernoeb.fff3.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An in-place radix-2 FFT of a fixed [size], a power of 2.
 * Tables are built once, and [magnitudes] allocates nothing, so it can run on the audio thread.
 */
class Fft(val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "Size must be a power of 2: $size" }
    }

    private val cosTable = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    /** Transforms [real] and [imag] in place. Both must hold [size] values. */
    fun transform(real: FloatArray, imag: FloatArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = real[b] * wr - imag[b] * wi
                    val xi = real[b] * wi + imag[b] * wr
                    real[b] = real[a] - xr
                    imag[b] = imag[a] - xi
                    real[a] += xr
                    imag[a] += xi
                }
                start += half * 2
            }
            half *= 2
        }
    }

    /**
     * Writes the magnitude of bins 0..size/2 of the real signal [input] into [out].
     * [out] must hold size / 2 + 1 values. Magnitudes are divided by size / 2, so a full-scale sine
     * windowed by nothing gives about 1 in its bin.
     */
    fun magnitudes(input: FloatArray, out: FloatArray) {
        input.copyInto(re, endIndex = size)
        im.fill(0f)
        transform(re, im)
        val scale = 2f / size
        for (k in 0..size / 2) out[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
    }
}
