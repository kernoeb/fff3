package com.kernoeb.fff3.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbTest {

    @Test
    fun channels() {
        val c = Rgb(0x123456)
        assertEquals(0x12, c.red)
        assertEquals(0x34, c.green)
        assertEquals(0x56, c.blue)
        assertEquals("#123456", c.toHex())
        assertEquals(c, Rgb.of(0x12, 0x34, 0x56))
        assertEquals(Rgb(0xFF00FF), Rgb.of(300, -2, 255))
    }

    @Test
    fun hsvPrimaries() {
        assertEquals(Rgb(0xFF0000), Rgb.fromHsv(0f))
        assertEquals(Rgb(0x00FF00), Rgb.fromHsv(120f))
        assertEquals(Rgb(0x0000FF), Rgb.fromHsv(240f))
        assertEquals(Rgb(0xFF0000), Rgb.fromHsv(360f))
        assertEquals(Rgb(0xFF0000), Rgb.fromHsv(-360f))
        assertEquals(Rgb.White, Rgb.fromHsv(42f, saturation = 0f))
        assertEquals(Rgb.Black, Rgb.fromHsv(42f, value = 0f))
    }

    @Test
    fun kelvinWarmIsRedderThanCool() {
        val warm = Rgb.fromKelvin(2700)
        val cool = Rgb.fromKelvin(6500)
        assertEquals(255, warm.red)
        assertTrue(warm.blue < cool.blue)
        assertTrue(cool.red > 240 && cool.green > 240 && cool.blue > 240)
    }
}
