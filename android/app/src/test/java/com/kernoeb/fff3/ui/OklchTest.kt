package com.kernoeb.fff3.ui

import com.kernoeb.fff3.ui.theme.Oklch
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklchTest {

    @Test
    fun srgbRoundTrip() {
        for (argb in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF8C7BFF, 0xFF123456, 0xFFFFFFFF, 0xFF000000)) {
            val lch = Oklch.fromArgb(argb.toInt())
            val back = Oklch.toArgb(lch.l, lch.c, lch.h)
            for (shift in listOf(16, 8, 0)) {
                val a = (argb.toInt() shr shift) and 0xFF
                val b = (back shr shift) and 0xFF
                assertTrue("channel off for ${argb.toString(16)}: $a vs $b", abs(a - b) <= 1)
            }
        }
    }

    @Test
    fun knownValues() {
        val white = Oklch.fromArgb(0xFFFFFFFF.toInt())
        assertEquals(1.0, white.l, 1e-3)
        assertEquals(0.0, white.c, 1e-3)
        val red = Oklch.fromArgb(0xFFFF0000.toInt())
        assertEquals(0.628, red.l, 1e-3)
        assertEquals(0.258, red.c, 1e-3)
        assertEquals(29.2, red.h, 0.1)
    }

    @Test
    fun outOfGamutChromaIsReduced() {
        val argb = Oklch.toArgb(0.9, 0.4, 264.0)
        assertEquals(0xFF, (argb ushr 24) and 0xFF)
        val back = Oklch.fromArgb(argb)
        assertEquals(0.9, back.l, 0.01)
        assertTrue(back.c < 0.4)
    }
}
