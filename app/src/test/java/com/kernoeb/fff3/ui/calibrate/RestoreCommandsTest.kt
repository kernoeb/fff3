package com.kernoeb.fff3.ui.calibrate

import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreCommandsTest {

    private val warmWhite = Command.Color(Rgb.fromKelvin(2700))

    @Test
    fun unknownStateComesBackAsWarmWhite() {
        assertEquals(listOf(Command.Brightness(100), warmWhite, Command.Power(true)), restoreCommands(null))
        assertEquals(listOf(Command.Brightness(30), warmWhite, Command.Power(true)), restoreCommands(LedState(brightness = 30)))
    }

    @Test
    fun knownStateComesBack() {
        val red = Rgb(0x00FF00)
        assertEquals(
            listOf(Command.Brightness(100), Command.Color(red), Command.Power(false)),
            restoreCommands(LedState(power = false, color = red)),
        )
        assertEquals(
            listOf(Command.Brightness(60), Command.Effect(3, 50)),
            restoreCommands(LedState(power = true, color = red, brightness = 60, effectId = 3, speed = 50)),
        )
    }
}
