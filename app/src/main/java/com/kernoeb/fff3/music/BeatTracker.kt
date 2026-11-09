package com.kernoeb.fff3.music

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong

enum class BeatLockState { Acquiring, Locked }

/**
 * Beat lock: estimates tempo and beat phase from the onset strength signal, then runs its own
 * beat clock, so the beat goes on through breaks, quiet parts and vocals.
 *
 * Feed it one analysis frame at a time with [process]. Times are seconds of audio since the start,
 * on the same scale as [time]. Allocates nothing after construction.
 *
 * - Acquire: the autocorrelation of the last 8 s gives the tempo, a comb over the same window the phase.
 * - Track: a phase-locked loop nudges phase and tempo toward the strongest onset near each predicted beat.
 * - Re-lock: after about 2 s of silence, or when the tempo heard stays more than 4 % away for a few seconds.
 */
class BeatTracker(
    private val frameRate: Float,
    /** How late the onset signal is behind the sound, for example half the analysis window. */
    private val onsetDelay: Double = 0.0,
) {
    private val envelope = FloatArray((frameRate * WINDOW_S).toInt())
    private var envelopeCount = 0
    private var envelopeIndex = 0
    private val linear = FloatArray(envelope.size)

    private val minLag = floor(frameRate * 60f / MAX_BPM).toInt()
    private val maxLag = ceil(frameRate * 60f / MIN_BPM).toInt()
    private val correlation = FloatArray(envelope.size / 2 + 2)
    private val phaseScores = FloatArray(maxLag + 2)

    private var frames = 0L
    private var framesSinceEstimate = 0

    private var anchorTime = 0.0
    private var anchorBeat = 0L
    /** Seconds per beat. */
    var period = 0.5
        private set
    private var acquiredPeriod = 0.5

    var state = BeatLockState.Acquiring
        private set

    /** Goes up each time the clock is set from scratch (lock, tap, ×2, ÷2), so beat numbers may jump. */
    var epoch = 0
        private set

    /** How many times the tracker locked. */
    var lockCount = 0
        private set

    /** The latest tempo the autocorrelation heard, or 0. Can disagree with [bpm] for a while. */
    var heardBpm = 0f
        private set
    private var previousEstimate = 0f
    private var mismatchSince = -1.0

    private var onsetMean = 0f
    private var onsetDeviation = 0f
    private val statsAlpha = 1f / (frameRate * STATS_S)
    private var previousOnset = 0f

    private var windowBeat = Long.MIN_VALUE
    private var windowBest = 0f
    private var windowBestTime = 0.0
    private var windowBestBefore = 0f
    private var refinePending = false

    private var loudPeak = SILENCE_FLOOR
    private val loudDecay = Math.pow(0.5, 1.0 / (frameRate * LOUD_HALF_LIFE_S)).toFloat()
    private val silence = SilenceDetector(SILENCE_MS, SILENCE_RATIO)
    private var wasSilent = false

    private val taps = DoubleArray(MAX_TAPS)
    private var tapCount = 0

    /** Audio time at the end of the latest frame. */
    val time: Double get() = frames / frameRate.toDouble()

    /** Tempo of the clock, or 0 while acquiring. */
    val bpm: Float get() = if (state == BeatLockState.Locked) (60.0 / period).toFloat() else 0f

    /** Beats at audio time [at]: the whole part counts beats (beat 0 of a bar is a multiple of 4), the fraction is the phase. */
    fun beatsAt(at: Double): Double = anchorBeat + (at - anchorTime) / period

    /** Feeds one frame: its onset [strength] and [rms] loudness. */
    fun process(strength: Float, rms: Float) {
        frames++
        val now = time

        loudPeak = maxOf(rms, loudPeak * loudDecay, SILENCE_FLOOR)
        val silent = silence.update(rms / loudPeak, (now * 1000).toLong())
        if (silent) {
            if (!wasSilent) restart(clearHistory = true)
            wasSilent = true
            previousOnset = strength
            return
        }
        wasSilent = false

        if (state == BeatLockState.Locked) track(strength, now - onsetDelay)

        onsetMean += (strength - onsetMean) * statsAlpha
        onsetDeviation += (abs(strength - onsetMean) - onsetDeviation) * statsAlpha
        previousOnset = strength

        envelope[envelopeIndex] = strength
        envelopeIndex = (envelopeIndex + 1) % envelope.size
        if (envelopeCount < envelope.size) envelopeCount++

        if (++framesSinceEstimate >= (frameRate * ESTIMATE_EVERY_S).toInt() && envelopeCount >= frameRate * MIN_FILL_S) {
            framesSinceEstimate = 0
            val estimate = estimateLag()
            if (estimate > 0f) onEstimate(estimate, now) else heardBpm = 0f
        }
    }

    /** A tap the user made on the beat, at audio time [at]. 4 taps or more set the clock. */
    fun tap(at: Double) {
        if (tapCount > 0 && at - taps[(tapCount - 1) % MAX_TAPS] > TAP_RESET_S) tapCount = 0
        taps[tapCount % MAX_TAPS] = at
        tapCount++
        if (tapCount < MIN_TAPS) return
        // A least-squares line through the last taps evens out the user's timing.
        val count = minOf(tapCount, MAX_TAPS)
        val first = tapCount - count
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until count) {
            meanX += i
            meanY += taps[(first + i) % MAX_TAPS]
        }
        meanX /= count
        meanY /= count
        var covariance = 0.0
        var variance = 0.0
        for (i in 0 until count) {
            val dx = i - meanX
            covariance += dx * (taps[(first + i) % MAX_TAPS] - meanY)
            variance += dx * dx
        }
        val slope = covariance / variance
        if (60.0 / slope !in CLOCK_MIN_BPM..CLOCK_MAX_BPM) return
        val lastBeatTime = meanY + slope * (count - 1 - meanX)
        // The first tap of a run is taken as the first beat of a bar.
        setClock(lastBeatTime, (tapCount - 1).toLong(), slope)
    }

    /** Multiplies the tempo by [factor], 2 or 0.5, to fix a tempo heard at half or double speed. */
    fun scaleTempo(factor: Double) {
        if (state != BeatLockState.Locked) return
        val newPeriod = period / factor
        if (60.0 / newPeriod !in CLOCK_MIN_BPM..CLOCK_MAX_BPM) return
        val beat = floor(beatsAt(time - onsetDelay)).toLong()
        setClock(anchorTime + (beat - anchorBeat) * period, beat, newPeriod)
    }

    fun reset() {
        frames = 0
        restart(clearHistory = true)
        loudPeak = SILENCE_FLOOR
        onsetMean = 0f
        onsetDeviation = 0f
        tapCount = 0
    }

    private fun restart(clearHistory: Boolean) {
        state = BeatLockState.Acquiring
        if (clearHistory) {
            envelopeCount = 0
            envelopeIndex = 0
            previousEstimate = 0f
            heardBpm = 0f
        }
        framesSinceEstimate = 0
        mismatchSince = -1.0
        windowBeat = Long.MIN_VALUE
        refinePending = false
    }

    private fun setClock(beatTime: Double, beat: Long, newPeriod: Double) {
        anchorTime = beatTime
        anchorBeat = beat
        period = newPeriod
        acquiredPeriod = newPeriod
        state = BeatLockState.Locked
        mismatchSince = -1.0
        windowBeat = Long.MIN_VALUE
        refinePending = false
        epoch++
    }

    private fun beatTime(beat: Long): Double = anchorTime + (beat - anchorBeat) * period

    /** The loop: one correction per beat, from the strongest onset in a window around the prediction. */
    private fun track(strength: Float, eventTime: Double) {
        if (refinePending) {
            refinePending = false
            // A parabola through the peak and its neighbours places the onset between frames.
            val a = windowBestBefore
            val b = windowBest
            val curve = a - 2 * b + strength
            if (curve < 0f) windowBestTime += (0.5f * (a - strength) / curve).coerceIn(-0.5f, 0.5f) / frameRate
        }
        val beat = beatsAt(eventTime).roundToLong()
        val inWindow = abs(eventTime - beatTime(beat)) <= WINDOW_BEATS * period
        if (windowBeat != Long.MIN_VALUE && (!inWindow || beat != windowBeat)) closeWindow()
        if (!inWindow) return
        if (windowBeat != beat) {
            windowBeat = beat
            windowBest = 0f
        }
        if (strength > windowBest) {
            windowBest = strength
            windowBestTime = eventTime
            windowBestBefore = previousOnset
            refinePending = true
        }
    }

    private fun closeWindow() {
        val beat = windowBeat
        windowBeat = Long.MIN_VALUE
        if (windowBest <= onsetMean + ONSET_DEVIATIONS * onsetDeviation || windowBest < MIN_ONSET) return
        val predicted = beatTime(beat)
        val error = windowBestTime - predicted
        val shift = (PHASE_GAIN * error).coerceIn(-MAX_SHIFT_BEATS * period, MAX_SHIFT_BEATS * period)
        val step = (TEMPO_GAIN * error).coerceIn(-MAX_TEMPO_STEP * period, MAX_TEMPO_STEP * period)
        anchorTime = predicted + shift
        anchorBeat = beat
        // The loop may refine the tempo, never wander to another one: a new tempo needs a re-lock.
        period = (period + step).coerceIn(acquiredPeriod * (1 - MAX_DRIFT), acquiredPeriod * (1 + MAX_DRIFT))
    }

    private fun onEstimate(lag: Float, now: Double) {
        val estimate = 60f * frameRate / lag
        heardBpm = estimate
        when (state) {
            BeatLockState.Acquiring -> {
                val previous = previousEstimate
                previousEstimate = estimate
                if (previous > 0f && abs(estimate - previous) < previous * CONSISTENT) lock(lag)
            }
            BeatLockState.Locked -> {
                if (sameTempo(estimate, bpm)) {
                    mismatchSince = -1.0
                } else {
                    if (mismatchSince < 0) mismatchSince = now
                    if (now - mismatchSince >= MISMATCH_S) {
                        restart(clearHistory = false)
                        previousEstimate = estimate
                    }
                }
            }
        }
    }

    /** Half and double count as the same tempo: they only differ in which beats flash. */
    private fun sameTempo(heard: Float, clock: Float): Boolean {
        for (factor in OCTAVES) if (abs(heard - clock * factor) <= clock * factor * MISMATCH) return true
        return false
    }

    private fun linearise(removeMean: Boolean): Int {
        val n = envelopeCount
        val start = if (n < envelope.size) 0 else envelopeIndex
        var mean = 0f
        for (i in 0 until n) {
            linear[i] = envelope[(start + i) % envelope.size]
            mean += linear[i]
        }
        if (removeMean) {
            mean /= n
            for (i in 0 until n) linear[i] -= mean
        }
        return n
    }

    /** The beat period in frames, with a fraction, or 0 when the signal has no clear tempo. */
    private fun estimateLag(): Float {
        val n = linearise(removeMean = true)
        val top = n / 2
        for (lag in 0..top) {
            var sum = 0f
            for (i in 0 until n - lag) sum += linear[i] * linear[i + lag]
            correlation[lag] = sum / (n - lag)
        }
        val energy = correlation[0]
        // A steady tone has a small periodic ripple in its flux: it is not a beat.
        if (energy < MIN_ENERGY) return 0f

        var bestLag = -1
        var bestScore = 0f
        for (lag in minLag..minOf(maxLag, top - 1)) {
            // The double period backs up the real beat against a busy off-beat, and the prior
            // gently prefers tempos near 120 BPM between a tempo and its half or double.
            val double = if (2 * lag <= top) correlation[2 * lag] else 0f
            val octaves = ln(60f * frameRate / lag / PREFERRED_BPM) / LN2
            val score = (correlation[lag] + 0.5f * double) * exp(-0.5f * octaves * octaves / (PRIOR_OCTAVES * PRIOR_OCTAVES))
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0 || correlation[bestLag] < energy * MIN_CONFIDENCE) return 0f

        // The peaks at 2, 3 and 4 periods give a finer period than the first one alone.
        var sumPeaks = 0f
        var sumMultiples = 0
        for (multiple in 1..4) {
            val centre = multiple * bestLag
            if (centre + multiple + 1 > top) break
            var peak = centre
            for (lag in centre - multiple..centre + multiple) if (correlation[lag] > correlation[peak]) peak = lag
            val a = correlation[peak - 1]
            val b = correlation[peak]
            val c = correlation[peak + 1]
            val curve = a - 2 * b + c
            val shift = if (curve < 0f) (0.5f * (a - c) / curve).coerceIn(-0.5f, 0.5f) else 0f
            sumPeaks += peak + shift
            sumMultiples += multiple
        }
        return if (sumMultiples > 0) sumPeaks / sumMultiples else bestLag.toFloat()
    }

    private fun lock(lag: Float) {
        val n = linearise(removeMean = false)
        val last = n - 1
        val candidates = ceil(lag).toInt().coerceAtMost(phaseScores.size)
        var best = 0
        for (offset in 0 until candidates) {
            var sum = 0f
            var position = (last - offset).toFloat()
            while (position >= 0f) {
                sum += sample(position)
                position -= lag
            }
            phaseScores[offset] = sum
            if (sum > phaseScores[best]) best = offset
        }
        val before = phaseScores[(best - 1 + candidates) % candidates]
        val after = phaseScores[(best + 1) % candidates]
        val curve = before - 2 * phaseScores[best] + after
        val shift = if (curve < 0f) (0.5f * (before - after) / curve).coerceIn(-0.5f, 0.5f) else 0f
        val beatIndex = last - best - shift

        // Bars often start with the loudest beat, so pick the beat of 4 that hits hardest.
        var downbeat = 0
        var total = 0f
        var bestSum = -1f
        for (offset in 0 until 4) {
            var sum = 0f
            var position = beatIndex - offset * lag
            while (position >= 0f) {
                sum += sample(position)
                position -= 4 * lag
            }
            total += sum
            if (sum > bestSum) {
                bestSum = sum
                downbeat = offset
            }
        }
        if (bestSum < total / 4 * DOWNBEAT_MARGIN) downbeat = 0

        val frame = frames - last + beatIndex
        setClock(frame / frameRate - onsetDelay, downbeat.toLong(), lag / frameRate.toDouble())
        lockCount++
    }

    private fun sample(position: Float): Float {
        val index = position.toInt()
        val fraction = position - index
        val next = if (index + 1 < envelopeCount) linear[index + 1] else linear[index]
        return linear[index] + (next - linear[index]) * fraction
    }

    private companion object {
        const val WINDOW_S = 8f
        const val MIN_FILL_S = 4f
        const val ESTIMATE_EVERY_S = 0.5f
        const val MIN_BPM = 70f
        const val MAX_BPM = 180f
        const val CLOCK_MIN_BPM = 40.0
        const val CLOCK_MAX_BPM = 240.0
        const val PREFERRED_BPM = 120f
        const val PRIOR_OCTAVES = 1f
        const val MIN_CONFIDENCE = 0.1f
        const val MIN_ENERGY = 0.025f * 0.025f
        const val CONSISTENT = 0.02f
        const val MISMATCH = 0.04f
        const val MISMATCH_S = 3.0
        val OCTAVES = floatArrayOf(1f, 2f, 0.5f)

        const val WINDOW_BEATS = 0.2
        const val PHASE_GAIN = 0.3
        const val TEMPO_GAIN = 0.05
        const val MAX_SHIFT_BEATS = 0.05
        const val MAX_TEMPO_STEP = 0.005
        const val MAX_DRIFT = 0.03
        const val ONSET_DEVIATIONS = 1f
        const val MIN_ONSET = 1e-4f
        const val STATS_S = 2f
        const val DOWNBEAT_MARGIN = 1.1f

        const val SILENCE_MS = 2_000L
        // Quieter than the recent peak by about 34 dB counts as silence between songs.
        const val SILENCE_RATIO = 0.02f
        const val SILENCE_FLOOR = 1e-4f
        const val LOUD_HALF_LIFE_S = 10f

        const val MIN_TAPS = 4
        const val MAX_TAPS = 8
        const val TAP_RESET_S = 2.0
        val LN2 = ln(2f)
    }
}
