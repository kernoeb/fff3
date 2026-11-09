package com.kernoeb.fff3.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmConverterTest {

    private fun convert(converter: PcmConverter, input: FloatArray): FloatArray {
        val out = FloatArray(converter.maxOutput(input.size))
        return out.copyOf(converter.process(input, input.size, out))
    }

    @Test
    fun monoAtSameRateIsUnchanged() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f, 0.4f)
        assertArrayEquals(input, convert(PcmConverter(1, 48_000, 48_000), input), 1e-6f)
    }

    @Test
    fun stereoIsAveraged() {
        val input = floatArrayOf(1f, 0f, 0.5f, -0.5f, -1f, -0.5f)
        assertArrayEquals(floatArrayOf(0.5f, 0f, -0.75f), convert(PcmConverter(2, 48_000, 48_000), input), 1e-6f)
    }

    @Test
    fun frameSplitAcrossChunksIsKept() {
        val converter = PcmConverter(2, 48_000, 48_000)
        val first = convert(converter, floatArrayOf(1f, 0f, 0.4f))
        val second = convert(converter, floatArrayOf(0.2f, -1f, -1f))
        assertArrayEquals(floatArrayOf(0.5f, 0.3f, -1f), first + second, 1e-6f)
    }

    @Test
    fun downsamplingHalvesTheCount() {
        val input = FloatArray(960) { it.toFloat() }
        val out = convert(PcmConverter(1, 96_000, 48_000), input)
        assertEquals(480, out.size)
        assertEquals(2f, out[1], 1e-4f)
    }

    @Test
    fun resamplingKeepsCountAndPitchAcrossChunks() {
        val converter = PcmConverter(2, 44_100, 48_000)
        val seconds = 1
        val stereo = FloatArray(44_100 * 2 * seconds) { i -> sin(2 * PI * 1_000 * (i / 2) / 44_100).toFloat() }
        val out = mutableListOf<Float>()
        // Odd chunk sizes cut frames in half on purpose.
        var offset = 0
        while (offset < stereo.size) {
            val chunk = stereo.copyOfRange(offset, minOf(stereo.size, offset + 1_001))
            out += convert(converter, chunk).toList()
            offset += chunk.size
        }
        assertEquals(48_000.0, out.size.toDouble(), 2.0)
        // Each output sample should match the 1 kHz sine at the new rate.
        var worst = 0f
        for (n in 100 until out.size - 100) {
            val expected = sin(2 * PI * 1_000 * n / 48_000).toFloat()
            worst = maxOf(worst, abs(out[n] - expected))
        }
        assertTrue("worst error $worst", worst < 0.02f)
    }
}

class SilenceDetectorTest {

    @Test
    fun silentOnlyAfterHoldTime() {
        val detector = SilenceDetector(holdMillis = 5_000)
        assertFalse(detector.update(0f, 0))
        assertFalse(detector.update(0f, 4_999))
        assertTrue(detector.update(0f, 5_000))
        assertTrue(detector.silent)
    }

    @Test
    fun soundResetsTheWait() {
        val detector = SilenceDetector(holdMillis = 5_000)
        detector.update(0f, 0)
        assertTrue(detector.update(0f, 6_000))
        assertFalse(detector.update(0.3f, 6_100))
        assertFalse(detector.update(0f, 6_200))
        assertFalse(detector.update(0f, 11_100))
        assertTrue(detector.update(0f, 11_200))
    }

    @Test
    fun ditherIsStillSilence() {
        val detector = SilenceDetector(holdMillis = 1_000)
        detector.update(1f / 32_768, 0)
        assertTrue(detector.update(1f / 32_768, 1_000))
    }
}
