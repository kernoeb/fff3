package com.kernoeb.fff3.music

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Finds beats in an onset strength signal (spectral flux), one value per analysis frame,
 * and estimates the tempo from the same signal.
 *
 * A beat fires when the strength rises above the mean plus a few standard deviations of the
 * last second, so loud and quiet music both work. The tempo comes from the autocorrelation of
 * the last few seconds, which is steadier than the gaps between single beats.
 */
class BeatDetector(private val frameRate: Float) {

    private val history = FloatArray((frameRate * THRESHOLD_WINDOW_S).toInt().coerceAtLeast(8))
    private var historyCount = 0
    private var historyIndex = 0

    private val envelope = FloatArray((frameRate * TEMPO_WINDOW_S).toInt())
    private var envelopeCount = 0
    private var envelopeIndex = 0
    private val linear = FloatArray(envelope.size)

    private val minLag = (frameRate * 60f / MAX_BPM).toInt().coerceAtLeast(1)
    private val maxLag = (frameRate * 60f / MIN_BPM).toInt().coerceAtMost(envelope.size / 2)
    private val correlation = FloatArray(maxLag + 2)

    private var framesSinceBeat = Int.MAX_VALUE / 2
    private val refractoryFrames = (frameRate * 60f / MAX_BPM).toInt()
    private var previous = 0f
    private var framesSinceTempo = 0

    /** Estimated tempo in beats per minute, or 0 until there is enough signal. */
    var bpm: Float = 0f
        private set

    /**
     * Feeds one frame's onset [strength] and returns true if it is a beat.
     * [sensitivity] 0..1: higher catches softer beats.
     */
    fun process(strength: Float, sensitivity: Float = 0.5f): Boolean {
        var mean = 0f
        var deviation = 0f
        if (historyCount > 0) {
            for (i in 0 until historyCount) mean += history[i]
            mean /= historyCount
            for (i in 0 until historyCount) deviation += (history[i] - mean) * (history[i] - mean)
            deviation = sqrt(deviation / historyCount)
        }
        val factor = MAX_DEVIATIONS - (MAX_DEVIATIONS - MIN_DEVIATIONS) * sensitivity.coerceIn(0f, 1f)
        val threshold = mean + factor * deviation + MIN_STRENGTH * (1.5f - sensitivity)

        framesSinceBeat++
        val beat = historyCount >= history.size / 4 &&
            strength > threshold &&
            strength >= previous &&
            framesSinceBeat >= refractoryFrames
        if (beat) framesSinceBeat = 0
        previous = strength

        history[historyIndex] = strength
        historyIndex = (historyIndex + 1) % history.size
        if (historyCount < history.size) historyCount++

        envelope[envelopeIndex] = strength
        envelopeIndex = (envelopeIndex + 1) % envelope.size
        if (envelopeCount < envelope.size) envelopeCount++
        if (++framesSinceTempo >= TEMPO_EVERY_FRAMES && envelopeCount >= envelope.size / 2) {
            framesSinceTempo = 0
            updateTempo()
        }
        return beat
    }

    fun reset() {
        historyCount = 0; historyIndex = 0
        envelopeCount = 0; envelopeIndex = 0
        framesSinceBeat = Int.MAX_VALUE / 2
        previous = 0f
        bpm = 0f
    }

    private fun updateTempo() {
        val n = envelopeCount
        val start = if (n < envelope.size) 0 else envelopeIndex
        var mean = 0f
        for (i in 0 until n) {
            linear[i] = envelope[(start + i) % envelope.size]
            mean += linear[i]
        }
        mean /= n
        for (i in 0 until n) linear[i] -= mean

        var energy = 0f
        for (i in 0 until n) energy += linear[i] * linear[i]
        if (energy <= 1e-9f) return

        val top = minOf(maxLag + 1, n - 1)
        var bestLag = -1
        var bestScore = 0f
        for (lag in minLag..top) {
            var sum = 0f
            for (i in 0 until n - lag) sum += linear[i] * linear[i + lag]
            correlation[lag] = sum
            if (lag > top - 1) continue
            // A mild preference for common tempos breaks ties between a tempo and its half or double.
            val bpmAtLag = 60f * frameRate / lag
            val octaves = ln(bpmAtLag / PREFERRED_BPM) / LN2
            val score = sum * exp(-0.5f * octaves * octaves / (PRIOR_OCTAVES * PRIOR_OCTAVES))
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= minLag || bestLag >= top || correlation[bestLag] < energy * MIN_CONFIDENCE) return

        // Parabolic fit around the peak gives a fraction of a frame, about 0.2 BPM at 120 BPM.
        val a = correlation[bestLag - 1]
        val b = correlation[bestLag]
        val c = correlation[bestLag + 1]
        val curve = a - 2 * b + c
        val shift = if (curve < 0f) (0.5f * (a - c) / curve).coerceIn(-0.5f, 0.5f) else 0f
        val estimate = 60f * frameRate / (bestLag + shift)
        bpm = if (bpm == 0f || kotlin.math.abs(estimate - bpm) > bpm * 0.08f) estimate else bpm * 0.7f + estimate * 0.3f
    }

    private companion object {
        const val THRESHOLD_WINDOW_S = 1.2f
        const val TEMPO_WINDOW_S = 6f
        const val TEMPO_EVERY_FRAMES = 24
        const val MIN_BPM = 60f
        const val MAX_BPM = 200f
        const val PREFERRED_BPM = 120f
        const val PRIOR_OCTAVES = 1.2f
        const val MIN_CONFIDENCE = 0.1f
        const val MAX_DEVIATIONS = 2.4f
        const val MIN_DEVIATIONS = 1.0f
        const val MIN_STRENGTH = 0.04f
        val LN2 = ln(2f)
    }
}
