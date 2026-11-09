package com.kernoeb.fff3.ui.control

import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.DomCatalog
import com.kernoeb.fff3.protocol.EffectInfo
import com.kernoeb.fff3.protocol.LedFamily
import com.kernoeb.fff3.protocol.MelkCatalog
import com.kernoeb.fff3.protocol.Rgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectPreviewTest {

    private fun preview(name: String, group: String = "") = EffectPreview.of(EffectInfo(0, name, group))

    @Test
    fun staticShowsItsColourForever() {
        val red = preview("Static red", "Static")
        assertEquals(EffectPreview.Motion.Static, red.motion)
        assertEquals(Rgb(0xFF0000), red.colorAt(0, 500))
        assertEquals(Rgb(0xFF0000), red.colorAt(12_345, 500))
    }

    @Test
    fun strobeAlternatesWithBlack() {
        val strobe = preview("Blue strobe flash")
        assertEquals(EffectPreview.Motion.Strobe, strobe.motion)
        assertEquals(Rgb(0x0000FF), strobe.colorAt(0, 1_000))
        assertEquals(Rgb.Black, strobe.colorAt(600, 1_000))
    }

    @Test
    fun letterCodesGiveTheirColours() {
        assertEquals(listOf(Rgb(0xFF0000), Rgb(0x00FF00), Rgb(0x0000FF)), preview("R-G-B jump").colors)
        assertEquals(3, preview("Y-C-P strobe").colors.size)
        assertEquals(7, preview("Seven colour cross fade").colors.size)
    }

    @Test
    fun jumpChangesColourEachPeriod() {
        val jump = preview("Three colour jumping change")
        assertEquals(EffectPreview.Motion.Jump, jump.motion)
        assertNotEquals(jump.colorAt(0, 400), jump.colorAt(400, 400))
        assertEquals(jump.colorAt(0, 400), jump.colorAt(1_200, 400))
    }

    @Test
    fun oneColourGradualBreathes() {
        val breathe = preview("Green gradual change")
        assertEquals(EffectPreview.Motion.Breathe, breathe.motion)
        assertEquals(Rgb.Black, breathe.colorAt(0, 1_000))
        assertEquals(Rgb(0x00FF00), breathe.colorAt(1_000, 1_000))
    }

    @Test
    fun marqueeTravelsAlongTheStrip() {
        val marquee = preview("Red marquee")
        assertTrue(marquee.isSpatial)
        assertNotEquals(marquee.colorAt(0, 500, 0f), marquee.colorAt(0, 500, 0.5f))
    }

    @Test
    fun everyCatalogueEffectHasAPreview() {
        for (effect in DomCatalog.effects + MelkCatalog.effects + MelkCatalog.scenes) {
            val preview = EffectPreview.of(effect)
            assertTrue(effect.name, preview.colors.isNotEmpty())
            preview.colorAt(1_234, 300, 0.7f)
        }
    }

    @Test
    fun fasterSpeedMeansShorterPeriod() {
        assertTrue(EffectPreview.periodFor(100f) < EffectPreview.periodFor(0f))
        assertTrue(EffectPreview.periodFor(100f) > 0)
    }

    @Test
    fun featuresOnlyListEffectsEveryMemberPlays() {
        assertEquals(DomCatalog.effects, ControlFeatures.of(listOf(LedFamily.DOM)).effects)
        assertTrue(ControlFeatures.of(listOf(LedFamily.DOM)).mic)
        assertTrue(!ControlFeatures.of(listOf(LedFamily.DOB)).mic)
        val mixed = ControlFeatures.of(listOf(LedFamily.DOM, LedFamily.MELK)).effects
        assertTrue(mixed.all { effect -> LedFamily.MELK.supports(Command.Effect(effect.id)) })
    }

    @Test
    fun favouriteCommandsFollowTheLastState() {
        assertNull(favouriteCommands(LedState()))
        assertEquals(
            listOf(Command.Power(true), Command.Color(Rgb(0x123456)), Command.Brightness(40)),
            favouriteCommands(LedState(color = Rgb(0x123456), brightness = 40)),
        )
        assertEquals(
            listOf(Command.Power(true), Command.Effect(8, 70)),
            favouriteCommands(LedState(effectId = 8, speed = 70)),
        )
    }
}
