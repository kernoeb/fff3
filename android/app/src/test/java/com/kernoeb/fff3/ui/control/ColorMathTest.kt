package com.kernoeb.fff3.ui.control

import com.kernoeb.fff3.protocol.Rgb
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {

    @Test
    fun hsvRoundTripsThroughRgb() {
        for (hue in 0 until 360 step 7) {
            for (saturation in listOf(0.1f, 0.5f, 1f)) {
                val rgb = Rgb.fromHsv(hue.toFloat(), saturation)
                val hsv = rgb.toHsv()
                assertEquals(rgb, Rgb.fromHsv(hsv.hue, hsv.saturation, hsv.value))
            }
        }
    }

    @Test
    fun primariesHaveTheirHue() {
        assertEquals(0f, Rgb(0xFF0000).toHsv().hue, 0.01f)
        assertEquals(120f, Rgb(0x00FF00).toHsv().hue, 0.01f)
        assertEquals(240f, Rgb(0x0000FF).toHsv().hue, 0.01f)
        assertEquals(300f, Rgb(0xFF00FF).toHsv().hue, 0.01f)
    }

    @Test
    fun greyHasNoSaturation() {
        assertEquals(Hsv(0f, 0f, 1f), Rgb.White.toHsv())
        assertEquals(Hsv(0f, 0f, 0f), Rgb.Black.toHsv())
    }

    @Test
    fun wheelPointsMapBothWays() {
        val radius = 100f
        for (hue in listOf(0f, 45f, 90f, 179f, 270f, 359f)) {
            val (x, y) = WheelGeometry.offsetOf(hue, 0.6f, radius)
            val (backHue, backSaturation) = WheelGeometry.hueSaturationAt(x, y, radius)
            assertEquals(hue, backHue, 0.01f)
            assertEquals(0.6f, backSaturation, 0.001f)
        }
    }

    @Test
    fun screenDownIsHue90() {
        // y grows downwards on screen, so straight down is a quarter turn clockwise.
        assertEquals(90f, WheelGeometry.hueSaturationAt(0f, 50f, 100f).first, 0.01f)
    }

    @Test
    fun outsideTheRimClampsToFullSaturation() {
        assertEquals(1f, WheelGeometry.hueSaturationAt(300f, 0f, 100f).second)
        assertEquals(0f, WheelGeometry.hueSaturationAt(0f, 0f, 100f).second)
    }

    @Test
    fun shortestHueDeltaWrapsAround() {
        assertEquals(20f, WheelGeometry.shortestHueDelta(350f, 10f), 0.001f)
        assertEquals(-20f, WheelGeometry.shortestHueDelta(10f, 350f), 0.001f)
        assertEquals(90f, WheelGeometry.shortestHueDelta(0f, 90f), 0.001f)
        assertEquals(180f, abs(WheelGeometry.shortestHueDelta(0f, 180f)), 0.001f)
    }
}
