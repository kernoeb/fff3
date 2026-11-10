package com.kernoeb.fff3.music

import com.kernoeb.fff3.data.BeatColors
import com.kernoeb.fff3.data.MusicStyle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayCorrelationTest {

    private val frameRate = 93.75f
    private val frames = (6 * frameRate).toInt()
    private val minLag = (-0.05f * frameRate).toInt()
    private val maxLag = (0.6f * frameRate).toInt()
    private val scores = FloatArray(maxLag - minLag + 1)

    /** Onset envelope of random hits, like drums and notes, each decaying in about 30 ms. */
    private fun envelope(hits: List<Pair<Double, Float>>, shift: Double, noise: Float, scale: Float, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(frames) { i ->
            val t = i / frameRate.toDouble()
            var value = 0f
            for ((at, height) in hits) {
                val since = t - at - shift
                if (since >= 0) value += height * exp(-since / 0.03).toFloat()
            }
            value * scale + random.nextFloat() * noise
        }
    }

    private fun hits(seed: Int): List<Pair<Double, Float>> {
        val random = Random(seed)
        val hits = mutableListOf<Pair<Double, Float>>()
        var t = 0.0
        while (t < 6.0) {
            hits += t to (0.3f + 0.7f * random.nextFloat())
            t += 0.08 + random.nextDouble() * 0.35
        }
        return hits
    }

    @Test
    fun findsA200MsLag() {
        val hits = hits(3)
        val phone = envelope(hits, shift = 0.0, noise = 0.02f, scale = 1f, seed = 1)
        // The microphone hears the same music later, quieter and with room noise.
        val mic = envelope(hits, shift = 0.200, noise = 0.15f, scale = 0.4f, seed = 2)
        val lag = DelayCorrelation.findLag(phone, mic, frames, minLag, maxLag, scores)
        assertEquals(0.200, lag / frameRate.toDouble(), 0.006)
    }

    @Test
    fun refusesAPairWithNoClearPeak() {
        val phone = envelope(hits(3), shift = 0.0, noise = 0.02f, scale = 1f, seed = 1)
        // Earbuds: the microphone hears only the room, other sounds than the music.
        val mic = envelope(hits(9), shift = 0.0, noise = 0.3f, scale = 0.2f, seed = 2)
        val lag = DelayCorrelation.findLag(phone, mic, frames, minLag, maxLag, scores)
        assertTrue("lag $lag", lag.isNaN())
    }

    @Test
    fun leadSigns() {
        val base = LightTiming.lead(link = 0.030, capture = 0.020, output = 0.0, lightDelay = 0.0)
        assertEquals(0.050, base, 1e-9)
        // Sound out of a Bluetooth speaker comes later, so the lights must fire later: less lead.
        assertEquals(base - 0.200, LightTiming.lead(0.030, 0.020, output = 0.200, lightDelay = 0.0), 1e-9)
        // A positive light delay makes the lights later.
        assertEquals(base - 0.040, LightTiming.lead(0.030, 0.020, 0.0, lightDelay = 0.040), 1e-9)
        // A slower link or staler audio means firing earlier.
        assertEquals(base + 0.010, LightTiming.lead(0.040, 0.020, 0.0, 0.0), 1e-9)
        assertEquals(base + 0.010, LightTiming.lead(0.030, 0.030, 0.0, 0.0), 1e-9)
    }
}

class BeatLockRendererTest {

    private val dt = 512f / 48_000

    @Test
    fun flashesOnEveryBeatWithABiggerDownbeat() {
        val tracker = BeatTracker(93.75f)
        // 120 BPM from taps; beat 0 of the bar is at 10 s.
        listOf(10.0, 10.5, 11.0, 11.5).forEach(tracker::tap)
        val renderer = LightRenderer()
        val frame = MusicFrame(4).apply { level = 0.5f }
        val edges = mutableListOf<Double>()
        val peaks = mutableMapOf<Int, Int>()
        var time = 11.6
        while (time < 15.6) {
            tracker.process(0f, 0.1f)
            val color = renderer.render(frame, MusicStyle.BeatLock, dt, tracker, lead = 11.6 + (time - 11.6) - tracker.time)
            if (renderer.beatEdge) {
                edges += time
                peaks[renderer.beatInBar] = maxOf(peaks[renderer.beatInBar] ?: 0, maxOf(color.red, color.green, color.blue))
            }
            time += dt
        }
        assertEquals(8, edges.size)
        edges.zipWithNext { a, b -> assertEquals(0.5, b - a, dt * 1.5) }
        assertEquals(12.0, edges.first(), dt * 1.5)
        assertTrue("peaks $peaks", peaks.getValue(0) > peaks.getValue(1) + 40)
    }

    /** Hues in degrees at each beat over 4 seconds of a clock tapped at [bpm]. */
    private fun beatHues(bpm: Double, colors: BeatColors, renderer: LightRenderer = LightRenderer()): List<Int> {
        val tracker = BeatTracker(93.75f)
        val period = 60.0 / bpm
        List(4) { 10.0 + it * period }.forEach(tracker::tap)
        val frame = MusicFrame(4).apply { level = 0.5f }
        val hues = mutableListOf<Int>()
        val start = 10.0 + 3 * period + 0.1
        var time = start
        while (time < start + 4) {
            tracker.process(0f, 0.1f)
            renderer.render(frame, MusicStyle.BeatLock, dt, tracker, lead = time - tracker.time, beatColors = colors)
            if (renderer.beatEdge) {
                val c = renderer.fullColor
                hues += Math.round(java.awt.Color.RGBtoHSB(c.red, c.green, c.blue, null)[0] * 360f)
            }
            time += dt
        }
        return hues
    }

    @Test
    fun tempoColoursKeepOneColourPerTempo() {
        val at120 = beatHues(120.0, BeatColors.Tempo)
        assertTrue("hues $at120", at120.size >= 6 && at120.max() - at120.min() <= 3)
        val at60 = beatHues(60.0, BeatColors.Tempo)
        assertEquals(at120.first().toFloat(), at60.first().toFloat(), 3f)
        val at100 = beatHues(100.0, BeatColors.Tempo)
        assertTrue("120: ${at120.first()}, 100: ${at100.first()}", abs(at120.first() - at100.first()) > 40)
    }

    @Test
    fun barColoursChangeEachBar() {
        val hues = beatHues(120.0, BeatColors.Bars)
        assertNotEquals(hues.first(), hues[4])
    }

    @Test
    fun stripColoursKeepTheStripHue() {
        val hues = beatHues(120.0, BeatColors.Strip, LightRenderer(baseHue = 30f, baseSaturation = 1f))
        assertTrue("hues $hues", hues.isNotEmpty() && hues.all { it in 28..32 })
    }

    @Test
    fun staysLitWhileListening() {
        val renderer = LightRenderer()
        val frame = MusicFrame(4).apply { level = 0.6f }
        val tracker = BeatTracker(93.75f)
        repeat(200) { renderer.render(frame, MusicStyle.BeatLock, dt, tracker) }
        val brightest = renderer.color.let { maxOf(it.red, it.green, it.blue) }
        assertTrue("brightest $brightest", brightest > 40)
        assertTrue(!renderer.beatEdge)
    }
}
