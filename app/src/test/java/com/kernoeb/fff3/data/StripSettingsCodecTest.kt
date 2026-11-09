package com.kernoeb.fff3.data

import com.kernoeb.fff3.protocol.ChannelOrder
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.DomProtocol
import com.kernoeb.fff3.protocol.ScheduleAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StripSettingsCodecTest {

    private fun roundTrip(value: Map<String, StripSettings>): Map<String, StripSettings> {
        val bytes = ByteArrayOutputStream().also { StripSettingsCodec.write(value, it) }.toByteArray()
        return StripSettingsCodec.read(ByteArrayInputStream(bytes))
    }

    @Test
    fun everyFieldRoundTrips() {
        val value = mapOf(
            "BE:FF:20:00:00:01" to StripSettings(
                wireOrder = ChannelOrder.GBR,
                turnOn = Command.Schedule(ScheduleAction.TurnOn, 7, 5, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
                turnOff = Command.Schedule(ScheduleAction.TurnOff, 23, 59, setOf(DayOfWeek.SUNDAY), enabled = false),
            ),
            "BE:FF:20:00:00:02" to StripSettings(),
            "BE:FF:20:00:00:03" to StripSettings(turnOff = Command.Schedule(ScheduleAction.TurnOff, 0, 0, emptySet())),
        )
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun everyWeekdayRoundTrips() {
        for (day in DayOfWeek.entries) {
            val schedule = Command.Schedule(ScheduleAction.TurnOn, 1, 2, setOf(day))
            assertEquals(schedule, roundTrip(mapOf("A" to StripSettings(turnOn = schedule)))["A"]?.turnOn)
        }
        val all = Command.Schedule(ScheduleAction.TurnOn, 1, 2, DayOfWeek.entries.toSet())
        assertEquals(all, roundTrip(mapOf("A" to StripSettings(turnOn = all)))["A"]?.turnOn)
    }

    @Test(expected = IOException::class)
    fun unknownVersionFails() {
        StripSettingsCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 99)))
    }

    @Test
    fun withReplacesOnlyItsAction() {
        val on = Command.Schedule(ScheduleAction.TurnOn, 7, 0, emptySet())
        val off = Command.Schedule(ScheduleAction.TurnOff, 22, 0, emptySet())
        val settings = StripSettings().with(on).with(off)
        assertEquals(on, settings.schedule(ScheduleAction.TurnOn))
        assertEquals(off, settings.schedule(ScheduleAction.TurnOff))
        assertNull(StripSettings().schedule(ScheduleAction.TurnOn))
    }

    @Test
    fun scheduleFrameMatchesStoredDays() {
        // Monday + Wednesday + enabled bit: 0x01 | 0x04 | 0x80.
        val schedule = Command.Schedule(ScheduleAction.TurnOn, 6, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val frame = DomProtocol.encode(schedule).single().joinToString(" ") { "%02x".format(it) }
        assertEquals("7e 08 82 06 1e 00 00 85 ef", frame)
        assertEquals(schedule, roundTrip(mapOf("A" to StripSettings(turnOn = schedule)))["A"]?.turnOn)
    }

    @Test
    fun oldAppDataStillReadsWithHapticsOn() {
        val out = ByteArrayOutputStream()
        java.io.DataOutputStream(out).apply {
            writeInt(1); writeInt(0); writeInt(0); writeInt(0); writeBoolean(false)
        }
        val data = AppDataCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Settings(accentFollowsStrip = false, hapticsEnabled = true), data.settings)
    }
}
