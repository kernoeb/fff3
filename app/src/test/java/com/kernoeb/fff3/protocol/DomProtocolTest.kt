package com.kernoeb.fff3.protocol

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected bytes come from the protocol notes, kept outside this repo, written the same way. */
class DomProtocolTest {

    @Test
    fun power() {
        assertEquals("7e 04 04 01 00 01 ff 00 ef", frames(DomProtocol, Command.Power(true)))
        assertEquals("7e 04 04 00 00 00 ff 00 ef", frames(DomProtocol, Command.Power(false)))
    }

    @Test
    fun brightnessIsClampedTo0To100() {
        assertEquals("7e 04 01 32 ff ff ff 00 ef", frames(DomProtocol, Command.Brightness(50)))
        assertEquals("7e 04 01 64 ff ff ff 00 ef", frames(DomProtocol, Command.Brightness(100)))
        assertEquals("7e 04 01 64 ff ff ff 00 ef", frames(DomProtocol, Command.Brightness(250)))
        assertEquals("7e 04 01 00 ff ff ff 00 ef", frames(DomProtocol, Command.Brightness(-4)))
    }

    @Test
    fun color() {
        assertEquals("7e 07 05 03 ff 80 00 10 ef", frames(DomProtocol, Command.Color(Rgb(0xFF8000))))
        assertEquals("7e 07 05 03 12 34 56 10 ef", frames(DomProtocol, Command.Color(Rgb.of(0x12, 0x34, 0x56))))
    }

    @Test
    fun effectIsSentAs0x80PlusIndex() {
        assertEquals("7e 05 03 80 03 ff ff 00 ef", frames(DomProtocol, Command.Effect(0)))
        assertEquals("7e 05 03 9c 03 ff ff 00 ef", frames(DomProtocol, Command.Effect(28)))
    }

    @Test
    fun effectWithSpeedSendsTwoFrames() {
        assertEquals(
            "7e 05 03 8a 03 ff ff 00 ef | 7e 04 02 4b ff ff ff 00 ef",
            frames(DomProtocol, Command.Effect(10, speed = 75)),
        )
    }

    @Test
    fun effectOutsideCatalogueHasNoFrame() {
        assertTrue(DomProtocol.encode(Command.Effect(29)).isEmpty())
        assertTrue(DomProtocol.encode(Command.Effect(-1, speed = 50)).isEmpty())
    }

    @Test
    fun speed() {
        assertEquals("7e 04 02 0a ff ff ff 00 ef", frames(DomProtocol, Command.Speed(10)))
    }

    @Test
    fun mic() {
        assertEquals("7e 04 07 01 ff ff ff 00 ef", frames(DomProtocol, Command.MicEnabled(true)))
        assertEquals("7e 04 07 00 ff ff ff 00 ef", frames(DomProtocol, Command.MicEnabled(false)))
        assertEquals("7e 04 06 1e ff ff ff 00 ef", frames(DomProtocol, Command.MicSensitivity(30)))
        assertEquals("7e 05 03 80 04 ff ff 00 ef", frames(DomProtocol, Command.MicMode(0)))
        assertEquals("7e 05 03 83 04 ff ff 00 ef", frames(DomProtocol, Command.MicMode(3)))
        assertEquals("7e 05 03 83 04 ff ff 00 ef", frames(DomProtocol, Command.MicMode(9)))
    }

    @Test
    fun wireOrderUses1Red2Green3Blue() {
        assertEquals("7e 06 81 01 02 03 ff 00 ef", frames(DomProtocol, Command.WireOrder(ChannelOrder.RGB)))
        assertEquals("7e 06 81 02 01 03 ff 00 ef", frames(DomProtocol, Command.WireOrder(ChannelOrder.GRB)))
        assertEquals("7e 06 81 03 02 01 ff 00 ef", frames(DomProtocol, Command.WireOrder(ChannelOrder.BGR)))
    }

    @Test
    fun clockSyncUsesSundayAsZero() {
        assertEquals("7e 07 83 14 1e 05 00 ff ef", frames(DomProtocol, Command.ClockSync(20, 30, 5, DayOfWeek.SUNDAY)))
        assertEquals("7e 07 83 08 00 00 01 ff ef", frames(DomProtocol, Command.ClockSync(8, 0, 0, DayOfWeek.MONDAY)))
        assertEquals("7e 07 83 17 3b 3b 06 ff ef", frames(DomProtocol, Command.ClockSync(23, 59, 59, DayOfWeek.SATURDAY)))
    }

    @Test
    fun scheduleSetsBit7WhileEnabled() {
        val weekdays = DayOfWeek.entries.take(5).toSet()
        assertEquals(
            "7e 08 82 07 00 00 00 9f ef",
            frames(DomProtocol, Command.Schedule(ScheduleAction.TurnOn, 7, 0, weekdays)),
        )
        assertEquals(
            "7e 08 82 17 1e 00 01 c0 ef",
            frames(DomProtocol, Command.Schedule(ScheduleAction.TurnOff, 23, 30, setOf(DayOfWeek.SUNDAY))),
        )
        assertEquals(
            "7e 08 82 17 1e 00 01 40 ef",
            frames(DomProtocol, Command.Schedule(ScheduleAction.TurnOff, 23, 30, setOf(DayOfWeek.SUNDAY), enabled = false)),
        )
    }

    @Test
    fun noFrameForMelkOnlyCommands() {
        assertTrue(DomProtocol.encode(Command.Scene(1)).isEmpty())
        assertTrue(DomProtocol.encode(Command.PixelCount(100)).isEmpty())
    }

    @Test
    fun everyFrameIsNineBytesWithMarkers() {
        val commands = listOf(
            Command.Power(true), Command.Color(Rgb.White), Command.Brightness(1), Command.Effect(5, 3),
            Command.Speed(1), Command.MicEnabled(true), Command.MicMode(1), Command.MicSensitivity(1),
            Command.WireOrder(ChannelOrder.BRG), Command.ClockSync(1, 2, 3, DayOfWeek.FRIDAY),
            Command.Schedule(ScheduleAction.TurnOn, 1, 2, emptySet()),
        )
        for (frame in commands.flatMap(DomProtocol::encode)) {
            assertEquals(9, frame.size)
            assertEquals(0x7e.toByte(), frame.first())
            assertEquals(0xef.toByte(), frame.last())
        }
    }
}

/** Frames as spaced hex bytes, frames separated by ` | `. */
internal fun frames(protocol: LedProtocol, command: Command): String =
    protocol.encode(command).joinToString(" | ") { frame ->
        frame.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    }
