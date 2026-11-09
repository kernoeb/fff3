package com.kernoeb.fff3.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    @Test
    fun sineLandsInItsBin() {
        val fft = Fft(1024)
        val input = FloatArray(1024) { sin(2 * PI * 64 * it / 1024).toFloat() * 0.5f }
        val out = FloatArray(513)
        fft.magnitudes(input, out)
        assertEquals(0.5f, out[64], 1e-3f)
        for (k in out.indices) if (k != 64) assertEquals("bin $k", 0f, out[k], 1e-3f)
    }

    @Test
    fun matchesNaiveDft() {
        val size = 64
        val random = Random(7)
        val input = FloatArray(size) { random.nextFloat() * 2 - 1 }
        val re = input.copyOf()
        val im = FloatArray(size)
        Fft(size).transform(re, im)
        for (k in 0 until size) {
            var sr = 0.0
            var si = 0.0
            for (n in 0 until size) {
                sr += input[n] * kotlin.math.cos(2 * PI * k * n / size)
                si -= input[n] * sin(2 * PI * k * n / size)
            }
            assertEquals(sr.toFloat(), re[k], 1e-3f)
            assertEquals(si.toFloat(), im[k], 1e-3f)
        }
    }
}

class BeatDetectionTest {

    private val sampleRate = 48_000

    /** Mono signal of [seconds] with a click every 60 / [bpm] seconds over faint noise. */
    private fun clickTrack(bpm: Double, seconds: Double, kick: Boolean = false): FloatArray {
        val random = Random(42)
        val samples = FloatArray((seconds * sampleRate).toInt()) { (random.nextFloat() * 2 - 1) * 0.002f }
        val period = 60.0 / bpm
        var t = 0.25
        while (t < seconds) {
            val start = (t * sampleRate).toInt()
            for (i in 0 until sampleRate / 10) {
                val index = start + i
                if (index >= samples.size) break
                val time = i.toDouble() / sampleRate
                samples[index] += if (kick) {
                    // A kick drum: a falling low sine with a fast decay.
                    (0.8 * exp(-time * 30) * sin(2 * PI * (50 + 100 * exp(-time * 40)) * time)).toFloat()
                } else {
                    (0.6 * exp(-time * 400) * (random.nextFloat() * 2 - 1)).toFloat()
                }
            }
            t += period
        }
        return samples
    }

    private class Result(val beatTimes: List<Double>, val bpm: Float)

    private fun analyse(samples: FloatArray): Result {
        val analyzer = MusicAnalyzer(sampleRate)
        val hop = FloatArray(analyzer.hopSize)
        val beats = mutableListOf<Double>()
        var offset = 0
        while (offset + hop.size <= samples.size) {
            samples.copyInto(hop, 0, offset, offset + hop.size)
            offset += hop.size
            if (analyzer.process(hop).beat) beats += offset.toDouble() / sampleRate
        }
        return Result(beats, analyzer.frame.bpm)
    }

    @Test
    fun clickTrackAt120BpmGives120Bpm() {
        val result = analyse(clickTrack(120.0, 12.0))
        assertEquals(120f, result.bpm, 2f)
    }

    @Test
    fun everyClickIsABeatAndNothingElse() {
        val result = analyse(clickTrack(120.0, 12.0))
        // The detector needs a moment of history before it fires; after that, one beat per click.
        val settled = result.beatTimes.filter { it > 2.0 }
        val expected = ((12.0 - 2.0) / 0.5).toInt()
        assertTrue("beats ${settled.size}, expected about $expected", abs(settled.size - expected) <= 1)
        settled.zipWithNext { a, b -> assertEquals(0.5, b - a, 0.03) }
    }

    @Test
    fun kickDrumAt100BpmGives100Bpm() {
        val result = analyse(clickTrack(100.0, 12.0, kick = true))
        assertEquals(100f, result.bpm, 2f)
        assertTrue(result.beatTimes.count { it > 2.0 } >= 15)
    }

    @Test
    fun fastTempoIsNotHalved() {
        val result = analyse(clickTrack(160.0, 12.0))
        assertEquals(160f, result.bpm, 3f)
    }

    @Test
    fun steadyToneHasNoBeats() {
        val samples = FloatArray(sampleRate * 6) { (0.3 * sin(2 * PI * 440 * it / sampleRate)).toFloat() }
        val result = analyse(samples)
        assertTrue("beats ${result.beatTimes}", result.beatTimes.count { it > 0.5 } == 0)
    }

    @Test
    fun silenceHasNoBeats() {
        val random = Random(1)
        val samples = FloatArray(sampleRate * 6) { (random.nextFloat() * 2 - 1) * 0.001f }
        val result = analyse(samples)
        assertTrue("beats ${result.beatTimes}", result.beatTimes.isEmpty())
    }
}

class LightRendererTest {

    @Test
    fun pulseFlashesOnBeatThenFades() {
        val renderer = LightRenderer()
        val frame = MusicFrame(4).apply { bpm = 120f }
        val dt = 512f / 48_000
        repeat(50) { renderer.render(frame, MusicStyle.Pulse, dt) }
        val quiet = renderer.color.brightest()
        frame.beat = true
        val flash = renderer.render(frame, MusicStyle.Pulse, dt).brightest()
        frame.beat = false
        repeat(40) { renderer.render(frame, MusicStyle.Pulse, dt) }
        val after = renderer.color.brightest()
        assertTrue("quiet $quiet flash $flash", flash > quiet + 150)
        assertTrue("after $after flash $flash", after < flash / 2)
    }

    @Test
    fun energyFollowsLevelWithoutJumps() {
        val renderer = LightRenderer()
        val frame = MusicFrame(4)
        val dt = 512f / 48_000
        var previous = 0
        frame.level = 1f
        repeat(100) {
            val now = renderer.render(frame, MusicStyle.Energy, dt).brightest()
            assertTrue(now >= previous)
            previous = now
        }
        assertTrue(previous > 180)
        frame.level = 0f
        val next = renderer.render(frame, MusicStyle.Energy, dt).brightest()
        assertTrue("a single frame must not drop to black: $previous to $next", next > previous * 0.7)
    }

    private fun com.kernoeb.fff3.protocol.Rgb.brightest() = maxOf(red, green, blue)
}
