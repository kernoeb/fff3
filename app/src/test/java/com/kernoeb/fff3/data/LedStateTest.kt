package com.kernoeb.fff3.data

import com.kernoeb.fff3.protocol.ChannelOrder
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedStateTest {

    @Test
    fun colourEndsAnEffect() {
        val state = LedState().after(Command.Effect(4, speed = 60)).after(Command.Color(Rgb(0x00FF00)))
        assertEquals(Rgb(0x00FF00), state.color)
        assertNull(state.effectId)
        assertEquals(60, state.speed)
    }

    @Test
    fun effectKeepsSpeedWhenNoneGiven() {
        val state = LedState(speed = 30).after(Command.Effect(2))
        assertEquals(2, state.effectId)
        assertEquals(30, state.speed)
    }

    @Test
    fun valuesAreClamped() {
        assertEquals(100, LedState().after(Command.Brightness(300)).brightness)
        assertEquals(0, LedState().after(Command.Speed(-5)).speed)
    }

    @Test
    fun settingsCommandsDoNotChangeState() {
        val state = LedState(power = true, brightness = 10)
        assertEquals(state, state.after(Command.WireOrder(ChannelOrder.BGR)).after(Command.PixelCount(50)))
    }
}
