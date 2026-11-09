package com.kernoeb.fff3.data

import com.kernoeb.fff3.protocol.ChannelOrder
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.LedFamily
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.protocol.ScheduleAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {

    private val everyCommand = listOf(
        Command.Power(true),
        Command.Color(Rgb(0x12AB34)),
        Command.Brightness(42),
        Command.Effect(7, speed = 30),
        Command.Effect(8),
        Command.Speed(55),
        Command.MicEnabled(false),
        Command.MicMode(2),
        Command.MicSensitivity(70),
        Command.Scene(156),
        Command.WireOrder(ChannelOrder.GBR),
        Command.PixelCount(300),
        Command.ClockSync(23, 59, 1, DayOfWeek.SUNDAY),
        Command.Schedule(ScheduleAction.TurnOff, 22, 15, setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), enabled = false),
    )

    @Test
    fun appDataRoundTrip() {
        val data = AppData(
            devices = listOf(
                KnownDevice("BE:FF:20:00:00:01", "ELK-BLEDOM", LedFamily.DOM, label = "Desk", associationId = 3),
                KnownDevice("BE:FF:20:00:00:02", "MELK-OA10", LedFamily.MELK),
            ),
            groups = listOf(DeviceGroup("g1", "Living room", listOf("BE:FF:20:00:00:01", "BE:FF:20:00:00:02"))),
            favourites = listOf(
                Favourite("f1", "Everything", everyCommand, family = LedFamily.MELK_SCENE),
                Favourite("f2", "Warm", listOf(Command.Color(Rgb.fromKelvin(2700)))),
            ),
            settings = Settings(accentFollowsStrip = false),
        )
        val bytes = ByteArrayOutputStream().also { AppDataCodec.write(data, it) }.toByteArray()
        assertEquals(data, AppDataCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun emptyAppDataRoundTrip() {
        val bytes = ByteArrayOutputStream().also { AppDataCodec.write(AppData(), it) }.toByteArray()
        assertEquals(AppData(), AppDataCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun deviceStatesRoundTrip() {
        val states = mapOf(
            "A" to LedState(power = true, color = Rgb(0xFF0000), brightness = 80, effectId = null, speed = 20, micEnabled = false),
            "B" to LedState(),
            "C" to LedState(sceneId = 3, micEnabled = true),
        )
        val bytes = ByteArrayOutputStream().also { DeviceStateCodec.write(states, it) }.toByteArray()
        assertEquals(states, DeviceStateCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test(expected = IOException::class)
    fun unknownVersionIsRejected() {
        AppDataCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 99)))
    }
}
