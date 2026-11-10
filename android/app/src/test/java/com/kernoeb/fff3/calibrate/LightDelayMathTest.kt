package com.kernoeb.fff3.calibrate

import com.kernoeb.fff3.calibrate.LightDelayMath.Frame
import com.kernoeb.fff3.calibrate.LightDelayMath.Level
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightDelayMathTest {

    @Test
    fun crossingIsInterpolatedBetweenFrames() {
        val times = longArrayOf(0, 100, 200, 300)
        val values = doubleArrayOf(0.0, 0.2, 0.6, 1.0)
        // 0.5 is 3 quarters of the way from 0.2 to 0.6.
        assertEquals(175L, LightDelayMath.crossingTime(times, values, from = 0))
    }

    @Test
    fun crossingUsesTheFirstRiseAfterTheSend() {
        val times = longArrayOf(0, 10, 20, 30, 40, 50)
        val values = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.75, 1.0)
        // The rise at 10 ends before the send at 25, so the one between 30 and 40 counts.
        assertEquals(37L, LightDelayMath.crossingTime(times, values, from = 25))
        assertNull(LightDelayMath.crossingTime(times, doubleArrayOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.4), from = 0))
    }

    @Test
    fun frameTimeIsTheExposureMiddleOfTheBoxRows() {
        // Start 1000, 2000 exposure, 30000 readout: the middle row starts 15000 later.
        assertEquals(1000L + 1000 + 15000, LightDelayMath.frameTime(1000, 2000, 30000, 0.5))
        assertEquals(1000L + 1000, LightDelayMath.frameTime(1000, 2000, 30000, 0.0))
        assertEquals(1000L + 1000 + 30000, LightDelayMath.frameTime(1000, 2000, 30000, 1.0))
    }

    @Test
    fun aWideCropUsesOnlyTheMiddleSensorRows() {
        // 16:9 out of a 4:3 sensor shows the middle 75 % of the rows.
        assertEquals(0.125, LightDelayMath.sensorRow(0.0, 1920, 1080, 4000, 3000), 1e-9)
        assertEquals(0.875, LightDelayMath.sensorRow(1.0, 1920, 1080, 4000, 3000), 1e-9)
        assertEquals(0.3, LightDelayMath.sensorRow(0.3, 640, 480, 4000, 3000), 1e-9)
    }

    @Test
    fun previewPointsMapToTheTurnedImage() {
        // A sensor turned 90 degrees: the right edge of the upright preview is the image's top row.
        assertEquals(0.25 to 0.0, LightDelayMath.previewToImage(1.0, 0.25, 90))
        assertEquals(0.0 to 1.0, LightDelayMath.previewToImage(1.0, 1.0, 270))
        assertEquals(0.3 to 0.6, LightDelayMath.previewToImage(0.3, 0.6, 0))
        assertEquals(0.7 to 0.4, LightDelayMath.previewToImage(0.3, 0.6, 180))
    }

    @Test
    fun outliersAreDroppedAndTheMedianKept() {
        val delays = listOf(40.0, 42.0, 44.0, 41.0, 43.0, 45.0, 39.0, 180.0, -60.0)
        val result = LightDelayMath.summarize(delays)
        assertNotNull(result)
        result!!
        assertEquals(7, result.kept)
        assertEquals(9, result.total)
        assertEquals(42.0, result.delayMs, 1e-9)
        assertEquals(2.0, result.spreadMs, 1e-9)
        assertEquals(41.5, LightDelayMath.median(listOf(44.0, 39.0, 43.0, 40.0)), 1e-9)
    }

    @Test
    fun equalValuesDoNotRejectANearNeighbour() {
        val kept = LightDelayMath.withoutOutliers(listOf(40.0, 40.0, 40.0, 40.0, 41.0), floor = 1.0)
        assertEquals(5, kept.size)
    }

    @Test
    fun progressGoesFromZeroToOneAlongTheLevels() {
        val red = Level(-40.0, 90.0)
        val blue = Level(110.0, -30.0)
        assertEquals(0.0, LightDelayMath.progress(Frame(0, red.u, red.v), red, blue), 1e-9)
        assertEquals(1.0, LightDelayMath.progress(Frame(0, blue.u, blue.v), red, blue), 1e-9)
        assertEquals(0.5, LightDelayMath.progress(Frame(0, 35.0, 30.0), red, blue), 1e-9)
    }

    @Test
    fun simulatedRunRecoversTheDelay() {
        val result = simulate(delayMs = 45.0, fps = 60.0, seed = 7)
        assertTrue("got ${result.delayMs} ms", abs(result.delayMs - 45.0) <= 3.0)
        assertTrue(result.kept >= 20)
    }

    @Test
    fun simulatedRunRecoversOtherDelaysAndRates() {
        for (seed in 1..5) {
            val result = simulate(delayMs = 120.0, fps = 30.0, seed = seed)
            assertTrue("seed $seed got ${result.delayMs} ms", abs(result.delayMs - 120.0) <= 4.0)
        }
        val fast = simulate(delayMs = 12.0, fps = 240.0, seed = 3)
        assertTrue("got ${fast.delayMs} ms", abs(fast.delayMs - 12.0) <= 1.0)
    }

    /**
     * 24 flashes between 2 colours at random intervals, seen by a rolling shutter camera with a short
     * exposure and some noise. The strip shows each colour [delayMs] after its send.
     */
    private fun simulate(delayMs: Double, fps: Double, seed: Int): LightDelayMath.Result {
        val random = Random(seed)
        val red = Level(-40.0, 90.0)
        val blue = Level(110.0, -30.0)
        val period = (1e9 / fps).toLong()
        val exposure = 1_500_000L
        val skew = 20_000_000L
        val boxRow = 0.6
        val delay = (delayMs * 1e6).toLong()

        // Send times, and the colour each one shows. The strip starts red.
        val sends = mutableListOf<Long>()
        var t = 1_000_000_000L + random.nextLong(period)
        repeat(24) {
            sends += t
            t += 450_000_000L + random.nextLong(300_000_000L)
        }
        fun shownAt(time: Long): Double {
            // Fraction of blue at this instant: each send flips the colour.
            val flips = sends.count { it + delay <= time }
            return if (flips % 2 == 1) 1.0 else 0.0
        }

        val frames = mutableListOf<Frame>()
        var start = random.nextLong(period)
        while (start < t + 200_000_000L) {
            val rowStart = start + (skew * boxRow).toLong()
            // Average the scene over the exposure of the box rows.
            val steps = 30
            var blueShare = 0.0
            for (i in 0 until steps) blueShare += shownAt(rowStart + exposure * i / steps)
            blueShare /= steps
            val u = red.u + (blue.u - red.u) * blueShare + random.nextDouble(-4.0, 4.0)
            val v = red.v + (blue.v - red.v) * blueShare + random.nextDouble(-4.0, 4.0)
            frames += Frame(LightDelayMath.frameTime(start, exposure, skew, boxRow), u, v)
            start += period
        }

        val delays = sends.mapIndexedNotNull { index, sentAt ->
            val until = sends.getOrNull(index + 1) ?: Long.MAX_VALUE
            val toBlue = index % 2 == 0
            LightDelayMath.flashDelayMs(frames, sentAt, until, if (toBlue) red else blue, if (toBlue) blue else red)
        }
        return LightDelayMath.summarize(delays)!!
    }
}
