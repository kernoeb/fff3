package com.kernoeb.fff3.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedFamilyTest {

    @Test
    fun detectsFamiliesInOrder() {
        assertEquals(LedFamily.DOM, LedFamily.detect("ELK-BLEDOM"))
        assertEquals(LedFamily.DOM, LedFamily.detect("ELK-BLEDOM   "))
        assertEquals(LedFamily.DOM, LedFamily.detect("ELK-HR01"))
        assertEquals(LedFamily.DOM, LedFamily.detect("CLK-BLEDDM"))
        assertEquals(LedFamily.DOB, LedFamily.detect("ELK-BLEDOB"))
        assertEquals(LedFamily.DOM, LedFamily.detect("ELK-BLEXYZ"))
        assertEquals(LedFamily.MELK, LedFamily.detect("MELK-OA10"))
        assertEquals(LedFamily.MELK_SCENE, LedFamily.detect("MELK-OC21"))
        assertEquals(LedFamily.MELK_SCENE, LedFamily.detect("MELK-OD"))
        assertEquals(LedFamily.MELK_SCENE, LedFamily.detect("MELK-OF"))
    }

    @Test
    fun unsupportedNamesGiveNull() {
        assertNull(LedFamily.detect(null))
        assertNull(LedFamily.detect(""))
        assertNull(LedFamily.detect("LEDDMX-00"))
        assertNull(LedFamily.detect("Triones:A1"))
        assertNull(LedFamily.detect("elk-bledom"))
    }

    @Test
    fun namePatternAcceptsWhatDetectAccepts() {
        val regex = Regex(LedFamily.NAME_PATTERN)
        for (name in listOf("ELK-BLEDOM", "ELK-BLEDOB", "ELK-HR", "CLK-BLEDDM", "MELK-OA", "MELK-OC", "xELK-BLEDOMx")) {
            assertTrue(name, regex.matches(name))
            assertTrue(name, LedFamily.detect(name) != null)
        }
        assertFalse(regex.matches("LEDDMX-00"))
    }

    @Test
    fun capabilitiesFilterCommands() {
        assertTrue(LedFamily.DOM.supports(Command.MicEnabled(true)))
        assertFalse(LedFamily.DOB.supports(Command.MicEnabled(true)))
        assertFalse(LedFamily.DOM.supports(Command.Scene(1)))
        assertFalse(LedFamily.MELK.supports(Command.Scene(1)))
        assertTrue(LedFamily.MELK_SCENE.supports(Command.Scene(1)))
        assertFalse(LedFamily.MELK_SCENE.supports(Command.Scene(200)))
        assertTrue(LedFamily.MELK.supports(Command.PixelCount(100)))
        assertFalse(LedFamily.DOM.supports(Command.PixelCount(100)))
        assertTrue(LedFamily.MELK.supports(Command.Effect(212)))
        assertFalse(LedFamily.MELK.supports(Command.Effect(213)))
    }

    @Test
    fun cataloguesMatchTheReport() {
        assertEquals(29, DomCatalog.effects.size)
        assertEquals((0..28).toList(), DomCatalog.effects.map { it.id }.sorted())
        assertEquals(213, MelkCatalog.effects.size)
        assertEquals(213, MelkCatalog.effects.map { it.id }.toSet().size)
        assertEquals(33, MelkCatalog.scenes.size)
        assertEquals(4, DomCatalog.micModes.size)
        assertEquals(8, MelkCatalog.micModes.size)
    }
}
