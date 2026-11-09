package com.kernoeb.fff3.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What the analyser heard in one hop. Values in 0..1 are normalised by an automatic gain,
 * so quiet and loud rooms both use the full range. One instance is reused for every hop.
 */
class MusicFrame(bandCount: Int) {
    /** Overall loudness, 0..1. */
    var level = 0f
    var bass = 0f
    var mid = 0f
    var treble = 0f
    /** Where the sound sits, 0 (low, bass-heavy) to 1 (bright). */
    var brightness = 0f
    /** Onset strength (spectral flux) of this hop, not normalised. */
    var onset = 0f
    /** Loudness before the automatic gain, for silence detection. */
    var rms = 0f
    var beat = false
    /** Estimated tempo, or 0 while unknown. */
    var bpm = 0f
    /** Log-spaced spectrum bars for the visualiser, each 0..1. */
    val bands = FloatArray(bandCount)
}

/**
 * Turns microphone samples into [MusicFrame]s. Feed it [hopSize] samples at a time; each call
 * analyses the last [fftSize] samples. Allocates nothing after construction.
 */
class MusicAnalyzer(
    val sampleRate: Int,
    val fftSize: Int = 1024,
    val hopSize: Int = 512,
    bandCount: Int = 24,
) {
    val frameRate: Float = sampleRate.toFloat() / hopSize
    val frame = MusicFrame(bandCount)

    /** 0..1, set from the UI. Higher reacts to quieter sound and softer beats. */
    @Volatile var sensitivity: Float = 0.5f

    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / fftSize)).toFloat() }
    private val ring = FloatArray(fftSize)
    private val windowed = FloatArray(fftSize)
    private val magnitudes = FloatArray(fftSize / 2 + 1)
    private val logMagnitudes = FloatArray(fftSize / 2 + 1)
    private val previousLog = FloatArray(fftSize / 2 + 1)
    private val detector = BeatDetector(frameRate)

    private val binHz = sampleRate.toFloat() / fftSize
    private fun bin(hz: Float) = (hz / binHz).toInt().coerceIn(1, fftSize / 2)
    private val bassEnd = bin(250f)
    private val midEnd = bin(2_000f)
    private val trebleEnd = bin(10_000f)
    private val fluxEnd = bin(8_000f)
    private val kickEnd = bin(180f)

    private val bandEdges = IntArray(bandCount + 1) { i ->
        bin(BAND_LOW_HZ * (BAND_HIGH_HZ / BAND_LOW_HZ).pow(i.toFloat() / bandCount))
    }.also { edges -> for (i in 1 until edges.size) if (edges[i] <= edges[i - 1]) edges[i] = edges[i - 1] + 1 }

    private val levelGain = PeakFollower(frameRate, floor = 0.004f)
    private val bassGain = PeakFollower(frameRate, floor = 0.002f)
    private val midGain = PeakFollower(frameRate, floor = 0.001f)
    private val trebleGain = PeakFollower(frameRate, floor = 0.0005f)
    private val bandGain = PeakFollower(frameRate, floor = 1f)
    private val bandValues = FloatArray(bandCount)

    /** Analyses [samples] (at least [hopSize] values, mono, -1..1) and returns the updated [frame]. */
    fun process(samples: FloatArray): MusicFrame {
        ring.copyInto(ring, 0, hopSize, fftSize)
        samples.copyInto(ring, fftSize - hopSize, 0, hopSize)

        var power = 0f
        for (i in 0 until hopSize) power += samples[i] * samples[i]
        val rms = sqrt(power / hopSize)

        for (i in 0 until fftSize) windowed[i] = ring[i] * window[i]
        fft.magnitudes(windowed, magnitudes)

        // Log compression makes the flux follow perceived changes, not just the loudest bins.
        var flux = 0f
        var kickFlux = 0f
        for (k in 1..fluxEnd) {
            val log = ln(1f + LOG_GAIN * magnitudes[k])
            logMagnitudes[k] = log
            val rise = log - previousLog[k]
            if (rise > 0f) {
                flux += rise
                if (k <= kickEnd) kickFlux += rise
            }
        }
        logMagnitudes.copyInto(previousLog, 1, 1, fluxEnd + 1)
        val strength = flux / fluxEnd + KICK_WEIGHT * kickFlux / kickEnd

        val gain = 2f.pow((sensitivity - 0.5f) * 3f)
        frame.level = (levelGain.normalise(rms) * gain).coerceIn(0f, 1f)
        frame.bass = (bassGain.normalise(rmsOf(1, bassEnd)) * gain).coerceIn(0f, 1f)
        frame.mid = (midGain.normalise(rmsOf(bassEnd + 1, midEnd)) * gain).coerceIn(0f, 1f)
        frame.treble = (trebleGain.normalise(rmsOf(midEnd + 1, trebleEnd)) * gain).coerceIn(0f, 1f)
        frame.onset = strength
        frame.rms = rms
        frame.brightness = centroid()
        frame.beat = detector.process(strength, sensitivity)
        frame.bpm = detector.bpm
        updateBands(gain)
        return frame
    }

    fun reset() {
        ring.fill(0f)
        previousLog.fill(0f)
        detector.reset()
    }

    private fun rmsOf(from: Int, to: Int): Float {
        var sum = 0f
        for (k in from..to) sum += magnitudes[k] * magnitudes[k]
        return sqrt(sum / (to - from + 1))
    }

    /** Spectral centroid on a log scale between 100 Hz and 6 kHz, mapped to 0..1. */
    private fun centroid(): Float {
        var weighted = 0f
        var total = 0f
        for (k in 1..trebleEnd) {
            weighted += magnitudes[k] * k
            total += magnitudes[k]
        }
        if (total < 1e-6f) return frame.brightness
        val hz = weighted / total * binHz
        return (ln(hz.coerceAtLeast(100f) / 100f) / ln(60f)).coerceIn(0f, 1f)
    }

    private fun updateBands(gain: Float) {
        var loudest = 0f
        for (i in frame.bands.indices) {
            var peak = 0f
            for (k in bandEdges[i] until bandEdges[i + 1]) if (magnitudes[k] > peak) peak = magnitudes[k]
            val value = ln(1f + LOG_GAIN * peak)
            bandValues[i] = value
            if (value > loudest) loudest = value
        }
        val scale = bandGain.peakAfter(loudest)
        for (i in frame.bands.indices) {
            val target = (bandValues[i] / scale * gain).coerceIn(0f, 1f)
            val old = frame.bands[i]
            // Rise at once, fall slowly, so the bars read as music and not as noise.
            frame.bands[i] = if (target > old) target else old + (target - old) * BAND_RELEASE
        }
    }

    private companion object {
        const val LOG_GAIN = 1_000f
        const val KICK_WEIGHT = 0.5f
        const val BAND_LOW_HZ = 40f
        const val BAND_HIGH_HZ = 12_000f
        const val BAND_RELEASE = 0.18f
    }
}

/**
 * Tracks the recent peak of a signal, falling by half every few seconds, never below [floor].
 * Dividing by it is a simple automatic gain.
 */
internal class PeakFollower(frameRate: Float, private val floor: Float, halfLifeSeconds: Float = 4f) {
    private val decay = 0.5f.pow(1f / (frameRate * halfLifeSeconds))
    private var peak = floor

    fun peakAfter(value: Float): Float {
        peak = maxOf(value, peak * decay, floor)
        return peak
    }

    fun normalise(value: Float): Float = value / peakAfter(value)
}
