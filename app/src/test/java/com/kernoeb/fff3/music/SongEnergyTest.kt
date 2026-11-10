package com.kernoeb.fff3.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongEnergyTest {

    private val dt = 512f / 48_000

    /** Plays [seconds] of sound at [rms] with attacks of [onset], and returns the last value. */
    private fun SongEnergy.play(seconds: Float, rms: Float, onset: Float): Float {
        var last = value
        repeat((seconds / dt).toInt()) { last = update(rms, onset, dt) }
        return last
    }

    /** A verse, a chorus 6 dB louder with twice the attacks, then the verse again. */
    private fun verseChorusVerse(scale: Float): List<Float> {
        val energy = SongEnergy()
        val verse = energy.play(20f, 0.05f * scale, 1f)
        val chorus = energy.play(15f, 0.1f * scale, 2f)
        val verseAgain = energy.play(10f, 0.05f * scale, 1f)
        return listOf(verse, chorus, verseAgain)
    }

    @Test
    fun chorusIsBrighterThanVerse() {
        val (verse, chorus, verseAgain) = verseChorusVerse(scale = 1f)
        assertTrue("chorus $chorus, verse $verse", chorus > 0.85f && chorus > verse + 0.1f)
        assertTrue("verse after chorus $verseAgain", verseAgain < 0.3f)
        assertTrue("verse $verse", verse in 0.6f..0.9f)
    }

    @Test
    fun sameResultAtAnyVolume() {
        val loud = verseChorusVerse(scale = 1f)
        val quiet = verseChorusVerse(scale = 0.1f)
        loud.zip(quiet).forEach { (a, b) -> assertEquals(a, b, 0.02f) }
    }

    @Test
    fun steadySongStaysBright() {
        val energy = SongEnergy()
        assertTrue(energy.play(20f, 0.08f, 1.5f) > 0.65f)
        assertTrue(energy.play(45f, 0.08f, 1.5f) > 0.95f)
    }

    /** A verse after a loud song reads low at once: the history spans songs. */
    @Test
    fun nextSongIsComparedWithTheLastOne() {
        val energy = SongEnergy()
        energy.play(60f, 0.1f, 2f)
        assertTrue(energy.play(15f, 0.05f, 1f) < 0.3f)
    }

    @Test
    fun silenceGoesDark() {
        val energy = SongEnergy()
        energy.play(20f, 0.08f, 1.5f)
        assertTrue(energy.play(3f, 0f, 0f) < 0.05f)
    }
}
