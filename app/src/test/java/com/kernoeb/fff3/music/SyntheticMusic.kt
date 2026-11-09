package com.kernoeb.fff3.music

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** The sound of a synthetic song, so 2 songs in a playlist differ like real ones do. */
internal class Timbre(
    val kickHz: Double,
    val snareHz: Double,
    val brightHats: Boolean,
    val hatLevel: Float,
    /** Bass root of each bar, cycling. */
    val bassRoots: DoubleArray,
    val pad: DoubleArray,
)

/**
 * Music-like test signals: kick on 1 and 3, snare on 2 and 4, swung 16th hi-hats, a syncopated
 * bass line, a pad, a snare fill every 8 bars, and optional breakdown and half-time bars.
 */
internal object SyntheticMusic {

    const val SAMPLE_RATE = 48_000

    val POP = Timbre(
        kickHz = 52.0, snareHz = 190.0, brightHats = true, hatLevel = 0.12f,
        bassRoots = doubleArrayOf(55.0, 55.0, 49.0, 61.7), pad = doubleArrayOf(220.0, 277.2, 329.6),
    )
    val BALLAD = Timbre(
        kickHz = 70.0, snareHz = 240.0, brightHats = false, hatLevel = 0.05f,
        bassRoots = doubleArrayOf(98.0, 87.3, 82.4, 73.4), pad = doubleArrayOf(392.0, 493.9, 587.3, 740.0),
    )

    fun song(
        bpm: Double,
        bars: Int,
        timbre: Timbre,
        breakdown: IntRange = IntRange.EMPTY,
        halfTime: IntRange = IntRange.EMPTY,
        swing: Double = 0.15,
        seed: Int = 1,
    ): FloatArray {
        val random = Random(seed)
        val step = 60.0 / bpm / 4
        val start = 0.1
        val out = FloatArray(((start + bars * 16 * step + 0.5) * SAMPLE_RATE).toInt())
        for (bar in 0 until bars) {
            val drums = bar !in breakdown
            val half = bar in halfTime
            val fill = drums && !half && bar % 8 == 7
            for (k in 0 until 16) {
                // Light swing: every second 16th comes a little late.
                val t = start + (bar * 16 + k) * step + if (k % 2 == 1) swing * step else 0.0
                if (drums) {
                    val kick = if (half) k == 0 else k == 0 || (k == 8 && !fill)
                    val snare = if (half) k == 8 else k == 4 || k == 12 || (fill && k >= 8)
                    if (kick) kick(out, t, timbre)
                    if (snare) snare(out, t, timbre, random, if (fill && k >= 8) 0.4f + 0.06f * (k - 8) else 0.8f)
                    val hat = if (half) k % 4 == 0 else true
                    if (hat) hat(out, t, timbre, random, if (k % 2 == 0) 1f else 0.6f)
                }
                if (k in BASS_STEPS) bass(out, t, timbre.bassRoots[bar % timbre.bassRoots.size], step * 2.5)
            }
        }
        val padLevel = 0.03f
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val bar = ((t - start) / (16 * step)).toInt()
            val level = if (bar in breakdown) padLevel * 2.5f else padLevel
            var sum = 0.0
            for (hz in timbre.pad) sum += sin(2 * PI * hz * t)
            out[i] += (sum * level).toFloat() + (random.nextFloat() * 2 - 1) * 0.001f
        }
        return out
    }

    fun withGap(first: FloatArray, gapSeconds: Double, second: FloatArray): FloatArray {
        val gap = (gapSeconds * SAMPLE_RATE).toInt()
        val out = FloatArray(first.size + gap + second.size)
        first.copyInto(out)
        second.copyInto(out, first.size + gap)
        return out
    }

    /** [second] starts [seconds] before [first] ends; the first fades out while the second fades in. */
    fun crossfade(first: FloatArray, second: FloatArray, seconds: Double): FloatArray {
        val overlap = (seconds * SAMPLE_RATE).toInt()
        val start = first.size - overlap
        val out = FloatArray(start + second.size)
        for (i in first.indices) {
            val fade = if (i < start) 1f else 1f - (i - start).toFloat() / overlap
            out[i] = first[i] * fade
        }
        for (i in second.indices) {
            val fade = if (i < overlap) i.toFloat() / overlap else 1f
            out[start + i] += second[i] * fade
        }
        return out
    }

    private val BASS_STEPS = setOf(0, 3, 6, 10, 13)

    private fun write(out: FloatArray, t: Double, seconds: Double, sample: (Double) -> Double) {
        val from = (t * SAMPLE_RATE).toInt()
        val count = (seconds * SAMPLE_RATE).toInt()
        for (i in 0 until count) {
            if (from + i >= out.size) return
            out[from + i] += sample(i.toDouble() / SAMPLE_RATE).toFloat()
        }
    }

    private fun kick(out: FloatArray, t: Double, timbre: Timbre) {
        var phase = 0.0
        write(out, t, 0.25) { time ->
            phase += 2 * PI * (timbre.kickHz + 90 * exp(-time * 40)) / SAMPLE_RATE
            0.8 * exp(-time * 12) * sin(phase)
        }
    }

    private fun snare(out: FloatArray, t: Double, timbre: Timbre, random: Random, level: Float) {
        write(out, t, 0.2) { time ->
            level * (0.5 * exp(-time * 25) * (random.nextDouble() * 2 - 1) + 0.3 * exp(-time * 30) * sin(2 * PI * timbre.snareHz * time))
        }
    }

    private fun hat(out: FloatArray, t: Double, timbre: Timbre, random: Random, accent: Float) {
        var previous = 0.0
        write(out, t, 0.05) { time ->
            val noise = random.nextDouble() * 2 - 1
            // The difference of 2 noise samples keeps only the highs: a bright hat.
            val value = if (timbre.brightHats) noise - previous else (noise + previous) / 2
            previous = noise
            timbre.hatLevel * accent * exp(-time * 90) * value
        }
    }

    private fun bass(out: FloatArray, t: Double, hz: Double, seconds: Double) {
        write(out, t, seconds) { time ->
            val wave = sin(2 * PI * hz * time) + 0.5 * sin(4 * PI * hz * time) + 0.33 * sin(6 * PI * hz * time)
            0.25 * exp(-time * 5) * wave * minOf(1.0, time * 200)
        }
    }
}
