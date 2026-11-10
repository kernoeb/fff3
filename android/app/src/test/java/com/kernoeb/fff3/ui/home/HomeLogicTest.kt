package com.kernoeb.fff3.ui.home

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.DeviceGroup
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.LedFamily
import com.kernoeb.fff3.protocol.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    private val desk = KnownDevice("AA:00:00:00:00:01", "ELK-BLEDOM ", LedFamily.DOM, "Desk")
    private val bed = KnownDevice("AA:00:00:00:00:02", "ELK-BLEDOM ", LedFamily.DOM, "Bed")
    private val melk = KnownDevice("AA:00:00:00:00:03", "MELK-OA10", LedFamily.MELK)
    private val devices = listOf(desk, bed, melk)

    @Test
    fun allStripsGroupMatchesMembersInAnyOrder() {
        val partial = DeviceGroup("p", "Bedroom", listOf(bed.address))
        val all = DeviceGroup("a", "All", listOf(melk.address, desk.address, bed.address))
        val addresses = devices.map { it.address }
        assertEquals(all, findAllStripsGroup(addresses, listOf(partial, all)))
        assertNull(findAllStripsGroup(addresses, listOf(partial)))
        assertNull(findAllStripsGroup(emptyList(), listOf(DeviceGroup("e", "Empty", emptyList()))))
    }

    @Test
    fun withoutAGroupOfEveryStripTheBuiltInEntryShows() {
        val whole = wholeSetOf(listOf(desk.address, bed.address), emptyList())
        assertTrue(whole.groups.isEmpty())
        assertTrue(whole.showBuiltIn)
        assertNull(whole.standIn)
        assertFalse(wholeSetOf(listOf(desk.address), emptyList()).showBuiltIn)
        assertEquals(ControlTarget.Device(desk.address), wholeSetOf(listOf(desk.address), emptyList()).standIn)
    }

    @Test
    fun aGroupOfEveryStripHidesTheBuiltInEntry() {
        val all = DeviceGroup("a", "All", listOf(bed.address, desk.address))
        val whole = wholeSetOf(listOf(desk.address, bed.address), listOf(all))
        assertEquals(listOf(all), whole.groups)
        assertEquals(setOf("a"), whole.groupIds)
        assertFalse(whole.showBuiltIn)
        assertEquals(ControlTarget.Group("a"), whole.standIn)
    }

    @Test
    fun aPartialGroupKeepsTheBuiltInEntry() {
        val bedroom = DeviceGroup("p", "Bedroom", listOf(bed.address))
        val whole = wholeSetOf(listOf(desk.address, bed.address), listOf(bedroom))
        assertTrue(whole.groups.isEmpty())
        assertTrue(whole.showBuiltIn)
    }

    @Test
    fun everyGroupOfEveryStripShowsAndTheFirstStandsIn() {
        val first = DeviceGroup("a", "All", listOf(desk.address, bed.address))
        val second = DeviceGroup("b", "House", listOf(bed.address, desk.address))
        val whole = wholeSetOf(listOf(desk.address, bed.address), listOf(first, second))
        assertEquals(listOf(first, second), whole.groups)
        assertFalse(whole.showBuiltIn)
        assertEquals(ControlTarget.Group("a"), whole.standIn)
    }

    @Test
    fun aStripAddedAfterTheGroupBringsTheBuiltInEntryBack() {
        val all = DeviceGroup("a", "All", listOf(desk.address, bed.address))
        val whole = wholeSetOf(listOf(desk.address, bed.address, melk.address), listOf(all))
        assertTrue(whole.groups.isEmpty())
        assertTrue(whole.showBuiltIn)
        assertNull(whole.standIn)
    }

    @Test
    fun groupTargetSkipsMissingMembers() {
        val group = DeviceGroup("g", "Room", listOf(bed.address, "GONE"))
        val target = HomeTarget.One(ControlTarget.Group("g"))
        assertEquals(listOf(bed.address), addressesOf(target, devices, listOf(group)))
        val missing = HomeTarget.One(ControlTarget.Group("missing"))
        assertTrue(addressesOf(missing, devices, listOf(group)).isEmpty())
    }

    @Test
    fun colourFavouriteGoesToEveryStripButEffectsOnlyToTheirFamily() {
        val colour = Favourite("c", "Red", listOf(Command.Color(Rgb(0xFF0000))))
        val effect = Favourite("e", "Jump", listOf(Command.Effect(1)), family = LedFamily.DOM)
        assertEquals(devices.map { it.address }, favouriteRecipients(colour, HomeTarget.AllStrips, devices, emptyList()))
        assertEquals(
            listOf(desk.address, bed.address),
            favouriteRecipients(effect, HomeTarget.AllStrips, devices, emptyList()),
        )
        val onMelk = HomeTarget.One(ControlTarget.Device(melk.address))
        assertTrue(favouriteRecipients(effect, onMelk, devices, emptyList()).isEmpty())
    }

    @Test
    fun defaultTargetPrefersAllGroup() {
        assertNull(defaultHomeTarget(emptyList(), emptyList()))
        assertEquals(HomeTarget.One(ControlTarget.Device(desk.address)), defaultHomeTarget(listOf(desk), emptyList()))
        assertEquals(HomeTarget.AllStrips, defaultHomeTarget(listOf(desk, bed), emptyList()))
        val all = DeviceGroup("a", "All", listOf(desk.address, bed.address))
        assertEquals(HomeTarget.One(ControlTarget.Group("a")), defaultHomeTarget(listOf(desk, bed), listOf(all)))
    }

    @Test
    fun homeTargetRoundTrips() {
        val targets = listOf(
            HomeTarget.AllStrips,
            HomeTarget.One(ControlTarget.Device(desk.address)),
            HomeTarget.One(ControlTarget.Group("0d1f6c2e")),
        )
        for (target in targets) assertEquals(target, HomeTarget.decode(target.encode()))
        assertNull(HomeTarget.decode("nonsense"))
        assertNull(HomeTarget.decode(null))
    }

    @Test
    fun summaryShowsLitColoursAndRoundedBrightness() {
        val red = Rgb(0xFF0000)
        val blue = Rgb(0x0000FF)
        val summary = summarise(
            listOf(
                LedState(power = true, color = red, brightness = 50),
                LedState(power = false, color = blue, brightness = 75),
            ),
            connected = listOf(true, false),
        )
        assertEquals(2, summary.count)
        assertEquals(1, summary.onCount)
        assertEquals(1, summary.connectedCount)
        assertEquals(listOf(red), summary.colours)
        assertEquals(63, summary.brightness)
        assertNull(summary.power)
        assertFalse(summary.unknown)
    }

    @Test
    fun oneUnknownMemberMakesTheGroupUnknown() {
        val red = Rgb(0xFF0000)
        val summary = summarise(listOf(LedState(power = true, color = red, brightness = 50), LedState()), listOf(true, true))
        assertTrue(summary.unknown)
        assertNull(summary.power)
        assertTrue(summary.colours.isEmpty())
        assertNull(summary.brightness)
    }

    @Test
    fun summaryOfStripsThatAreAllOffKeepsTheirColours() {
        val red = Rgb(0xFF0000)
        val summary = summarise(listOf(LedState(power = false, color = red)), listOf(false))
        assertEquals(listOf(red), summary.colours)
        assertEquals(false, summary.power)
        assertNull(summary.brightness)
        assertEquals(true, summarise(listOf(LedState(power = true)), listOf(true)).power)
        assertNull(summarise(emptyList(), emptyList()).power)
    }

    @Test
    fun stripWithNoPowerOrLookIsUnknown() {
        assertTrue(LedState().unknown)
        assertTrue(LedState(power = true).unknown)
        assertFalse(LedState(power = false, color = Rgb(1)).unknown)
        assertFalse(LedState(power = true, effectId = 3).unknown)
    }

    @Test
    fun previewColourIsTheLastColourCommand() {
        val fav = Favourite("f", "Mix", listOf(Command.Color(Rgb(1)), Command.Brightness(40), Command.Color(Rgb(2))))
        assertEquals(Rgb(2), fav.previewColour())
        assertNull(Favourite("e", "Fx", listOf(Command.Effect(3))).previewColour())
    }

    @Test
    fun movedReordersAndIgnoresBadIndexes() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
        assertEquals(listOf("d", "a", "b", "c"), list.moved(3, 0))
        assertEquals(list, list.moved(1, 1))
        assertEquals(list, list.moved(-1, 2))
        assertEquals(list, list.moved(0, 4))
    }
}
