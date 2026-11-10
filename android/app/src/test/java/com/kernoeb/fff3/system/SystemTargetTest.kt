package com.kernoeb.fff3.system

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.AppData
import com.kernoeb.fff3.data.DeviceGroup
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.LedFamily
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTargetTest {

    private val a = KnownDevice("AA", "ELK-BLEDOM", LedFamily.DOM, label = "Desk")
    private val b = KnownDevice("BB", "ELK-BLEDOM", LedFamily.DOM)
    private val all = DeviceGroup("g-all", "All", listOf("BB", "AA"))
    private val desk = DeviceGroup("g-desk", "Desk only", listOf("AA", "GONE"))

    @Test
    fun `every strip resolves to the group that holds them all`() {
        val target = SystemTarget.resolve(AppData(devices = listOf(a, b), groups = listOf(desk, all)), chosen = null)
        assertEquals(listOf(ControlTarget.Group("g-all")), target.targets)
        assertEquals(listOf("AA", "BB"), target.addresses)
        assertEquals("All", target.name)
        assertEquals(Route.Control(ControlTarget.Group("g-all")), target.route)
    }

    @Test
    fun `without such a group every strip is sent to on its own and the route is Home`() {
        val target = SystemTarget.resolve(AppData(devices = listOf(a, b)), chosen = null)
        assertEquals(listOf(ControlTarget.Device("AA"), ControlTarget.Device("BB")), target.targets)
        assertEquals(Route.Home, target.route)
        assertNull(target.name)
    }

    @Test
    fun `a chosen group keeps only known members and its name`() {
        val target = SystemTarget.resolve(AppData(devices = listOf(a, b), groups = listOf(desk)), ControlTarget.Group("g-desk"))
        assertEquals(listOf("AA"), target.addresses)
        assertEquals("Desk only", target.name)
    }

    @Test
    fun `a chosen device that was removed falls back to every strip`() {
        val target = SystemTarget.resolve(AppData(devices = listOf(b)), ControlTarget.Device("AA"))
        assertEquals(listOf(ControlTarget.Device("BB")), target.targets)
        assertEquals(b.displayName, target.name)
    }

    @Test
    fun `no strips gives an empty target`() {
        assertTrue(SystemTarget.resolve(AppData(), chosen = null).isEmpty)
    }

    @Test
    fun `on when any member is on, colour from the first member that has one`() {
        val target = SystemTarget.resolve(AppData(devices = listOf(a, b)), chosen = null)
        assertFalse(target.isOn(emptyMap()))
        val states = mapOf("AA" to LedState(power = false), "BB" to LedState(power = true, color = Rgb(0x112233)))
        assertTrue(target.isOn(states))
        assertEquals(Rgb(0x112233), target.color(states))
    }

    @Test
    fun `a favourite applied from outside turns the strips on first`() {
        val colour = Favourite("f", "Red", listOf(Command.Color(Rgb(0xFF0000))))
        assertEquals(listOf(Command.Power(true), Command.Color(Rgb(0xFF0000))), colour.commandsFromOutside())
        assertEquals(Rgb(0xFF0000), colour.color)
        val off = Favourite("o", "Off", listOf(Command.Power(false)))
        assertEquals(off.commands, off.commandsFromOutside())
    }
}
