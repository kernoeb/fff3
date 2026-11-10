package com.kernoeb.fff3.ui.add

import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.protocol.LedFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class AddLogicTest {

    private fun device(address: String, label: String?) = KnownDevice(address, "ELK-BLEDOM ", LedFamily.DOM, label)

    @Test
    fun firstStripIsStripOne() {
        assertEquals("Strip 1", suggestStripName(emptyList()))
    }

    @Test
    fun takesTheLowestFreeNumber() {
        val existing = listOf(device("A", "Strip 2"), device("B", "Desk"))
        assertEquals("Strip 1", suggestStripName(existing))
        assertEquals("Strip 3", suggestStripName(existing + device("C", "strip 1")))
    }

    @Test
    fun alreadyAddedIgnoresCase() {
        val existing = listOf(device("AA:BB:CC:DD:EE:01", "Desk"))
        assertEquals(AddOutcome.AlreadyAdded("Desk"), addOutcome("aa:bb:cc:dd:ee:01", existing))
        assertEquals(AddOutcome.New, addOutcome("AA:BB:CC:DD:EE:02", existing))
    }
}
