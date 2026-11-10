package com.kernoeb.fff3.protocol

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected bytes come from the protocol notes, kept outside this repo. */
class MelkProtocolTest {

    @Test
    fun inheritsDomFrames() {
        assertEquals("7e 04 04 01 00 01 ff 00 ef", frames(MelkProtocol, Command.Power(true)))
        assertEquals("7e 04 01 32 ff ff ff 00 ef", frames(MelkProtocol, Command.Brightness(50)))
        assertEquals("7e 07 05 03 00 ff 00 10 ef", frames(MelkProtocol, Command.Color(Rgb(0x00FF00))))
        assertEquals("7e 04 07 01 ff ff ff 00 ef", frames(MelkProtocol, Command.MicEnabled(true)))
        assertEquals("7e 04 06 50 ff ff ff 00 ef", frames(MelkProtocol, Command.MicSensitivity(80)))
        assertEquals("7e 07 83 0c 00 00 03 ff ef", frames(MelkProtocol, Command.ClockSync(12, 0, 0, DayOfWeek.WEDNESDAY)))
    }

    @Test
    fun speed() {
        assertEquals("7e 07 02 32 ff ff ff 00 ef", frames(MelkProtocol, Command.Speed(50)))
    }

    @Test
    fun patternSendsTheIdAsIs() {
        assertEquals("7e 07 03 00 06 ff ff 00 ef", frames(MelkProtocol, Command.Effect(0)))
        assertEquals("7e 07 03 d4 06 ff ff 00 ef", frames(MelkProtocol, Command.Effect(212)))
        assertEquals(
            "7e 07 03 c1 06 ff ff 00 ef | 7e 07 02 64 ff ff ff 00 ef",
            frames(MelkProtocol, Command.Effect(193, speed = 100)),
        )
    }

    @Test
    fun micModeHas8Modes() {
        assertEquals("7e 05 03 80 04 ff ff 00 ef", frames(MelkProtocol, Command.MicMode(0)))
        assertEquals("7e 05 03 87 04 ff ff 00 ef", frames(MelkProtocol, Command.MicMode(7)))
        assertEquals("7e 05 03 87 04 ff ff 00 ef", frames(MelkProtocol, Command.MicMode(12)))
    }

    @Test
    fun wireOrderMatchesTheStripTable() {
        // The strip's order index 0..5 is RGB, RBG, GRB, GBR, BRG, BGR.
        val expected = listOf(
            ChannelOrder.RGB to "7e 07 81 01 02 03 ff 00 ef",
            ChannelOrder.RBG to "7e 07 81 01 03 02 ff 00 ef",
            ChannelOrder.GRB to "7e 07 81 02 01 03 ff 00 ef",
            ChannelOrder.GBR to "7e 07 81 02 03 01 ff 00 ef",
            ChannelOrder.BRG to "7e 07 81 03 01 02 ff 00 ef",
            ChannelOrder.BGR to "7e 07 81 03 02 01 ff 00 ef",
        )
        for ((order, hex) in expected) assertEquals(hex, frames(MelkProtocol, Command.WireOrder(order)))
    }

    @Test
    fun scene() {
        assertEquals("7e 07 31 01 07 ff ff 00 ef", frames(MelkProtocol, Command.Scene(1)))
        assertEquals("7e 07 31 9c 07 ff ff 00 ef", frames(MelkProtocol, Command.Scene(156)))
    }

    @Test
    fun pixelCountIsLittleEndianAndClamped() {
        assertEquals("7e 07 21 2c 01 00 ff 00 ef", frames(MelkProtocol, Command.PixelCount(300)))
        assertEquals("7e 07 21 e8 03 00 ff 00 ef", frames(MelkProtocol, Command.PixelCount(5000)))
        assertEquals("7e 07 21 0a 00 00 ff 00 ef", frames(MelkProtocol, Command.PixelCount(1)))
    }
}
