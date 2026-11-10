package com.kernoeb.fff3.music

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class BeatLockState { Acquiring, Locked }

/** Why the tracker last started over or took a new tempo. */
enum class RelockReason {
    None,
    /** Silence or a clear drop in loudness: the gap between 2 songs. */
    Gap,
    /** The sound changed and the music no longer fits the beat grid: a new song without a gap. */
    SongChange,
    /** Another tempo clearly beat the locked one for a long time. */
    NewTempo,
}

/**
 * Beat lock: estimates tempo and beat phase from the onset strength signal, then runs its own
 * beat clock, so the beat goes on through breaks, quiet parts and vocals.
 *
 * A song keeps one tempo for minutes, so once locked the tempo is hard to move:
 * - half, double, 3:2 and 4:3 readings are the same tempo, never a change;
 * - within a song, another tempo must clearly win for [SLOW_RELOCK_S] seconds;
 * - a quick re-lock needs a song boundary: a gap, or a new sound plus a tempo that leaves the beat grid;
 * - while the beat is weak (breakdown, vocals only) the clock coasts and the tempo is frozen;
 * - the first lock waits a few seconds while an unrelated tempo is almost as strong, as in many intros;
 * - a lock the song never backs up, often taken in an intro, gives way to the tempo heard most of the time.
 *
 * Feed it one analysis frame at a time with [process]. Times are seconds of audio since the start,
 * on the same scale as [time]. Allocates nothing after construction.
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
    private val shortFrames = (frameRate * SHORT_WINDOW_S).toInt()

    private val minLag = floor(frameRate * 60f / MAX_BPM).toInt()
    private val maxLag = ceil(frameRate * 60f / MIN_BPM).toInt()
    private val correlation = FloatArray(minOf(envelope.size / 2, 4 * maxLag + 8) + 2)
    // Taps and ×2 can set a clock slower than MIN_BPM, and the phase check combs at that period.
    private val phaseScores = FloatArray(ceil(frameRate * 60.0 / CLOCK_MIN_BPM).toInt() + 2)

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

    /** How many times the tracker locked from the music (taps not counted). */
    var lockCount = 0
        private set

    /** Why the tracker last started over. */
    var lastReason = RelockReason.None
        private set

    /** Rounded tempo for the screen, or 0 while acquiring. Moves only when the tempo clearly moved. */
    var displayBpm = 0
        private set

    /** The latest tempo heard over the long window, or 0, and how sure that reading is (0..1). */
    var heardBpm = 0f
        private set
    var heardConfidence = 0f
        private set
    /** The latest reading over the short window, only made while a song change is possible. */
    var shortBpm = 0f
        private set
    /** Seconds another tempo has been beating the locked one. */
    var challengeSeconds = 0f
        private set

    private var estimateConfidence = 0f
    /** The strongest tempo unrelated to the estimate, on the same scale as [estimateConfidence]. */
    private var estimateRival = 0f
    /** How strongly the window hears the locked tempo, 0..1. */
    var lockedSupport = 0f
        private set
    /** Like [lockedSupport], from the locked tempo, its half and its double only. */
    private var octaveSupport = 0f
    /** When the clock was last set from what was heard, rather than from taps or ×2. */
    private var guessedAt = Double.NEGATIVE_INFINITY
    private var previousEstimate = 0f
    private var previousClear = false
    /** True while the lock came from weak readings and no clear reading has backed it yet. */
    private var provisional = false
    private var agreeing = 0
    /** The latest readings, one per estimate, 0 when none: how often each tempo was heard lately. */
    private val recent = FloatArray(STEADY_READINGS)
    private var recentCount = 0
    /** True once the window heard the locked tempo most of the time: the lock was not a guess. */
    private var proven = false
    private var challenger = 0f
    private var fastCandidate = 0f
    private var fastSeconds = 0f

    private var onsetMean = 0f
    private var onsetDeviation = 0f
    private val statsAlpha = 1f / (frameRate * STATS_S)
    private var previousOnset = 0f

    private var windowBeat = Long.MIN_VALUE
    private var windowBest = 0f
    private var windowBestTime = 0.0
    private var windowBestBefore = 0f
    private var refinePending = false

    /** Share of recent beats with a strong onset; below one half the clock coasts. */
    private var pulse = 1f
    private var strengthReference = 0f

    /** True while the beat is too weak to correct the clock: it runs on its own. */
    val coasting: Boolean get() = state == BeatLockState.Locked && pulse < COAST_BELOW

    private val shortProfile = FloatArray(3)
    private val longProfile = FloatArray(3)
    private var profileReady = 0
    private val shortProfileAlpha = 1f / (frameRate * SHORT_PROFILE_S)
    private val longProfileAlpha = 1f / (frameRate * LONG_PROFILE_S)
    private var profileChangedAt = Double.NEGATIVE_INFINITY

    /** Biggest gap in dB between the recent and the long-term sound profile. */
    var novelty = 0f
        private set

    private var typicalLoudness = 0f
    private val loudFastAlpha = 1f / (frameRate * LOUD_S)
    private val loudSlowAlpha = 1f / (frameRate * QUIET_ADAPT_S)
    private var quietFrames = 0
    private val gapFrames = (frameRate * GAP_S).toInt()

    private val taps = DoubleArray(MAX_TAPS)
    private var tapCount = 0

    /** Audio time at the end of the latest frame. */
    val time: Double get() = frames / frameRate.toDouble()

    /** Tempo of the clock, or 0 while acquiring. */
    val bpm: Float get() = if (state == BeatLockState.Locked) (60.0 / period).toFloat() else 0f

    /** Beats at audio time [at]: the whole part counts beats (beat 0 of a bar is a multiple of 4), the fraction is the phase. */
    fun beatsAt(at: Double): Double = anchorBeat + (at - anchorTime) / period

    /** Feeds one frame: its onset [strength], [rms] loudness and raw band loudness for the sound profile. */
    fun process(strength: Float, rms: Float, bass: Float = 0f, mid: Float = 0f, treble: Float = 0f) {
        frames++
        val now = time

        // Between songs the sound stops or drops well below the song's usual level, even after a fade-out.
        val quiet = rms < SILENCE_FLOOR || rms < GAP_RATIO * typicalLoudness
        typicalLoudness += (rms - typicalLoudness) * if (quiet) loudSlowAlpha else loudFastAlpha
        quietFrames = if (quiet) quietFrames + 1 else 0
        if (quietFrames == gapFrames) restart(RelockReason.Gap)
        if (quietFrames >= gapFrames) {
            previousOnset = strength
            return
        }

        updateProfile(bass, mid, treble, now)
        if (state == BeatLockState.Locked) {
            track(strength, now - onsetDelay)
        }

        onsetMean += (strength - onsetMean) * statsAlpha
        onsetDeviation += (abs(strength - onsetMean) - onsetDeviation) * statsAlpha
        previousOnset = strength

        envelope[envelopeIndex] = strength
        envelopeIndex = (envelopeIndex + 1) % envelope.size
        if (envelopeCount < envelope.size) envelopeCount++

        if (++framesSinceEstimate >= (frameRate * ESTIMATE_EVERY_S).toInt() && envelopeCount >= frameRate * MIN_FILL_S) {
            framesSinceEstimate = 0
            onEstimate(now)
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

    private fun restart(reason: RelockReason) {
        state = BeatLockState.Acquiring
        lastReason = reason
        envelopeCount = 0
        envelopeIndex = 0
        previousEstimate = 0f
        previousClear = false
        agreeing = 0
        provisional = false
        recentCount = 0
        heardBpm = 0f
        displayBpm = 0
        framesSinceEstimate = 0
        profileReady = 0
        profileChangedAt = Double.NEGATIVE_INFINITY
        resetEvidence()
    }

    private fun resetEvidence() {
        challenger = 0f
        challengeSeconds = 0f
        fastCandidate = 0f
        fastSeconds = 0f
        windowBeat = Long.MIN_VALUE
        refinePending = false
    }

    private fun setClock(beatTime: Double, beat: Long, newPeriod: Double, heard: Boolean = false) {
        guessedAt = if (heard) time else Double.NEGATIVE_INFINITY
        anchorTime = beatTime
        anchorBeat = beat
        period = newPeriod
        acquiredPeriod = newPeriod
        state = BeatLockState.Locked
        displayBpm = (60.0 / newPeriod).roundToInt()
        pulse = 1f
        strengthReference = 0f
        provisional = false
        proven = false
        resetEvidence()
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
        val strong = windowBest > onsetMean + ONSET_DEVIATIONS * onsetDeviation &&
            windowBest >= MIN_ONSET &&
            windowBest >= STRONG_SHARE * strengthReference
        pulse += ((if (strong) 1f else 0f) - pulse) * PULSE_ALPHA
        if (!strong) {
            // A long weak stretch lowers the bar slowly, so a quieter song can lead the clock again.
            if (coasting) strengthReference *= REFERENCE_DECAY
            return
        }
        val wasCoasting = coasting
        if (!wasCoasting) {
            strengthReference = if (strengthReference == 0f) windowBest else strengthReference + (windowBest - strengthReference) * REFERENCE_ALPHA
        }
        val predicted = beatTime(beat)
        val error = windowBestTime - predicted
        val maxShift = (if (wasCoasting) COAST_MAX_SHIFT_BEATS else MAX_SHIFT_BEATS) * period
        val gain = if (wasCoasting) COAST_PHASE_GAIN else PHASE_GAIN
        anchorTime = predicted + (gain * error).coerceIn(-maxShift, maxShift)
        anchorBeat = beat
    }

    /**
     * Fine-tunes the tempo from a confident long-window reading of the same tempo. The loop itself
     * only moves the phase: onsets near a beat come from many instruments and would drag the tempo.
     */
    private fun refineTempo(estimate: Float) {
        if (coasting || estimateConfidence < REFINE_CONFIDENCE) return
        val locked = 60.0 / period
        if (abs(estimate - locked) > locked * MAX_DRIFT) return
        // Re-anchor on the last beat, so the new period does not move beats already passed.
        val beat = floor(beatsAt(time - onsetDelay)).toLong()
        anchorTime = beatTime(beat)
        anchorBeat = beat
        period = (period + (60.0 / estimate - period) * REFINE_GAIN)
            .coerceIn(acquiredPeriod * (1 - MAX_DRIFT), acquiredPeriod * (1 + MAX_DRIFT))
        val shown = 60.0 / period
        if (abs(shown - displayBpm) > DISPLAY_HYSTERESIS) displayBpm = shown.roundToInt()
    }

    private fun updateProfile(bass: Float, mid: Float, treble: Float, now: Double) {
        val total = bass * bass + mid * mid + treble * treble
        if (total < 1e-12f) return
        val alphaShort = if (profileReady == 0) 1f else shortProfileAlpha
        val alphaLong = if (profileReady == 0) 1f else longProfileAlpha
        var biggest = 0f
        for (band in 0..2) {
            val value = when (band) {
                0 -> bass
                1 -> mid
                else -> treble
            }
            val share = 10f * log10(value * value / total + 1e-6f)
            shortProfile[band] += (share - shortProfile[band]) * alphaShort
            longProfile[band] += (share - longProfile[band]) * alphaLong
            biggest = maxOf(biggest, abs(shortProfile[band] - longProfile[band]))
        }
        if (profileReady < Int.MAX_VALUE) profileReady++
        novelty = biggest
        if (profileReady > frameRate * SHORT_PROFILE_S && biggest > NOVELTY_DB) profileChangedAt = now
    }

    private fun onEstimate(now: Double) {
        val lag = estimateLag(envelopeCount)
        val estimate = if (lag > 0f) 60f * frameRate / lag else 0f
        heardBpm = if (estimateConfidence >= ACQUIRE_CONFIDENCE) estimate else 0f
        heardConfidence = estimateConfidence
        val clear = estimateConfidence >= ACQUIRE_CONFIDENCE && estimateRival < RIVAL_SHARE * estimateConfidence
        val consistent = estimate > 0f && previousEstimate > 0f && abs(estimate - previousEstimate) < previousEstimate * CONSISTENT
        val clearTwice = consistent && clear && previousClear
        previousEstimate = estimate
        previousClear = clear
        recent[recentCount % recent.size] = estimate
        recentCount++
        when (state) {
            BeatLockState.Acquiring -> {
                // 2 clear readings in a row lock at once. A weak or contested tempo must
                // come back for [PATIENT_ESTIMATES] readings in a row instead.
                agreeing = if (consistent) agreeing + 1 else 0
                if (clearTwice || agreeing + 1 >= PATIENT_ESTIMATES) {
                    lock(lag, envelopeCount)
                    provisional = !clearTwice
                }
            }
            BeatLockState.Locked -> {
                if (provisional && clear) {
                    // A lock from weak readings gives way to the first clear tempo outside its octaves.
                    if (related(estimate, OCTAVES)) {
                        provisional = false
                    } else if (clearTwice && now - guessedAt < YOUNG_LOCK_S) {
                        lastReason = RelockReason.NewTempo
                        lock(lag, envelopeCount)
                    }
                }
                if (estimate > 0f) refineTempo(estimate)
                weighChallenger(lag, estimate, now)
                if (!checkSteadyRival(lag, estimate)) checkSongChange(now)
                if (state == BeatLockState.Locked && !coasting) checkPhase()
            }
        }
    }

    /**
     * Within a song: another tempo must clearly beat the locked one for a long time. Soon after a
     * lock from what was heard, a tempo at 3:2 or 4:3 can also win, since the lock may be a guess.
     * Later it cannot, so a breakdown does not move the beat.
     */
    private fun weighChallenger(lag: Float, estimate: Float, now: Double) {
        if (estimate <= 0f || estimateConfidence < CHALLENGE_CONFIDENCE) return
        val young = now - guessedAt < YOUNG_LOCK_S
        val support = if (young) octaveSupport else lockedSupport
        if (related(estimate, if (young) OCTAVES else RATIOS) || estimateConfidence < support * CHALLENGE_MARGIN) {
            challengeSeconds = 0f
            challenger = 0f
            return
        }
        if (challenger > 0f && abs(estimate - challenger) < challenger * CONSISTENT) {
            challengeSeconds += ESTIMATE_EVERY_S
        } else {
            challenger = estimate
            challengeSeconds = ESTIMATE_EVERY_S
        }
        // A young lock the window barely hears was likely a wrong guess: it yields sooner.
        val hold = if (young && support < WEAK_SUPPORT) WEAK_RELOCK_S else SLOW_RELOCK_S
        if (challengeSeconds >= hold) {
            lastReason = RelockReason.NewTempo
            lock(lag, envelopeCount)
        }
    }

    /**
     * A lock the window never heard most of the time, often from an intro, gives way to a tempo
     * heard in most readings for [STEADY_READINGS] estimates while the locked one is almost never heard.
     * Returns true when it re-locked.
     */
    private fun checkSteadyRival(lag: Float, estimate: Float): Boolean {
        // Taps and ×2 come from the user: only a lock from what was heard can be a wrong guess.
        if (proven || guessedAt == Double.NEGATIVE_INFINITY) return false
        val lockedShare = recentShare(bpm)
        if (lockedShare >= PROVEN_SHARE) {
            proven = true
            return false
        }
        if (coasting || recentCount < recent.size || estimate <= 0f || related(estimate, OCTAVES)) return false
        if (lockedShare > STEADY_LOCKED_SHARE || recentShare(estimate) < STEADY_SHARE) return false
        lastReason = RelockReason.NewTempo
        lock(lag, envelopeCount)
        return true
    }

    /** Share of the recent readings at [bpm], its half or its double. */
    private fun recentShare(bpm: Float): Float {
        var count = 0
        for (i in 0 until minOf(recentCount, recent.size)) {
            val heard = recent[i]
            if (heard > 0f && OCTAVES.any { abs(heard - bpm * it) <= bpm * it * SAME_TEMPO }) count++
        }
        return count.toFloat() / recent.size
    }

    /**
     * A new song without a gap: the sound profile changed, and the short window clearly hears
     * an unrelated tempo, for a little while. Re-locks from the new song only.
     */
    private fun checkSongChange(now: Double) {
        shortBpm = 0f
        if (now - profileChangedAt > NOVELTY_HOLD_S || envelopeCount < shortFrames) {
            fastSeconds = 0f
            return
        }
        val longSupport = lockedSupport
        val lag = estimateLag(shortFrames)
        // The short reading is weighed against the locked tempo in the same short window; the
        // long-window support stays the one shown in logs.
        val shortSupport = lockedSupport
        lockedSupport = longSupport
        val estimate = if (lag > 0f) 60f * frameRate / lag else 0f
        shortBpm = if (estimateConfidence >= ACQUIRE_CONFIDENCE) estimate else 0f
        if (estimate <= 0f || estimateConfidence < FAST_CONFIDENCE || related(estimate) ||
            estimateConfidence < shortSupport * FAST_MARGIN
        ) {
            fastSeconds = 0f
            return
        }
        if (fastCandidate > 0f && abs(estimate - fastCandidate) < fastCandidate * CONSISTENT) {
            fastSeconds += ESTIMATE_EVERY_S
        } else {
            fastCandidate = estimate
            fastSeconds = ESTIMATE_EVERY_S
        }
        if (fastSeconds >= FAST_HOLD_S) {
            lastReason = RelockReason.SongChange
            // Forget the old song, so the long window does not pull back to its tempo.
            envelopeCount = shortFrames
            recentCount = 0
            lock(lag, shortFrames)
        }
    }

    /** Half, double, 3:2 and 4:3 of the locked tempo are the same music, counted another way. */
    private fun related(heard: Float, ratios: FloatArray = RATIOS): Boolean {
        val locked = (60.0 / period).toFloat()
        for (ratio in ratios) if (abs(heard - locked * ratio) <= locked * ratio * SAME_TEMPO) return true
        return false
    }

    /** Copies the newest [n] frames, oldest first; [forTempo] removes the mean and blurs them. */
    private fun linearise(n: Int, forTempo: Boolean) {
        val start = (envelopeIndex - n + envelope.size) % envelope.size
        var mean = 0f
        for (i in 0 until n) {
            linear[i] = envelope[(start + i) % envelope.size]
            mean += linear[i]
        }
        if (!forTempo) return
        mean /= n
        // A light blur widens the sharp onset peaks, so a beat that falls between 2 whole lags still scores.
        var before = linear[0] - mean
        for (i in 0 until n) {
            val current = linear[i] - mean
            val after = (if (i + 1 < n) linear[i + 1] else linear[i]) - mean
            linear[i] = (before + 2 * current + after) / 4
            before = current
        }
    }

    /**
     * The beat period in frames over the newest [n] frames, with a fraction, or 0 when the signal
     * has no clear tempo. Sets [estimateConfidence], [estimateRival] and, while locked, [lockedSupport].
     */
    private fun estimateLag(n: Int): Float {
        estimateConfidence = 0f
        estimateRival = 0f
        lockedSupport = 0f
        octaveSupport = 0f
        linearise(n, forTempo = true)
        val top = minOf(n / 2, correlation.size - 2)
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
            // 2 and 4 beats back up the real beat: a 4/4 bar repeats every 4 beats, while a
            // syncopated or dotted pulse does not. The prior gently prefers tempos near 120 BPM.
            var sum = correlation[lag]
            var weights = 1f
            if (2 * lag + 1 <= top) {
                sum += 0.5f * peakNear(2 * lag, 1)
                weights += 0.5f
            }
            if (4 * lag + 2 <= top) {
                sum += 0.5f * peakNear(4 * lag, 2)
                weights += 0.5f
            }
            val octaves = ln(60f * frameRate / lag / PREFERRED_BPM) / LN2
            val score = sum / weights * exp(-0.5f * octaves * octaves / (PRIOR_OCTAVES * PRIOR_OCTAVES))
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return 0f
        estimateConfidence = (correlation[bestLag] / energy).coerceAtLeast(0f)
        var rival = 0f
        for (lag in minLag..minOf(maxLag, top - 1)) {
            if (!relatedLag(lag, bestLag)) rival = maxOf(rival, correlation[lag])
        }
        estimateRival = (rival / energy).coerceAtLeast(0f)
        if (state == BeatLockState.Locked) {
            val lockedLag = period * frameRate
            for (ratio in RATIOS) {
                val at = (lockedLag / ratio).toFloat()
                if (at < 1f || at >= top - 1) continue
                val index = at.toInt()
                val value = correlation[index] + (correlation[index + 1] - correlation[index]) * (at - index)
                lockedSupport = maxOf(lockedSupport, value / energy)
                if (OCTAVES.any { it == ratio }) octaveSupport = maxOf(octaveSupport, value / energy)
            }
        }
        // Weaker readings are kept: one alone proves nothing, but the same one many times does.
        if (estimateConfidence < PATIENT_CONFIDENCE) return 0f

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

    /**
     * True when [lag] is [bestLag] or one of its [RATIOS], give or take. Peaks are a few lags
     * wide, so at fast tempos their sides must not count as a rival tempo.
     */
    internal fun relatedLag(lag: Int, bestLag: Int): Boolean =
        RATIOS.any { abs(lag - bestLag / it) <= maxOf(bestLag / it * RIVAL_TOLERANCE, RIVAL_MIN_LAGS) }

    /** The highest correlation within [radius] lags of [centre]: peaks are sharp and fall between whole lags. */
    private fun peakNear(centre: Int, radius: Int): Float {
        var best = correlation[centre]
        for (lag in centre - radius..centre + radius) if (correlation[lag] > best) best = correlation[lag]
        return best
    }

    /**
     * Nudges the clock towards the beat grid that fits the whole window best. The loop corrects
     * each beat from the strongest nearby onset, so syncopated hits slowly pull it off the beat.
     */
    private fun checkPhase() {
        val n = envelopeCount
        linearise(n, forTempo = false)
        val beatIndex = combBeat((period * frameRate).toFloat(), n)
        val beats = beatsAt((frames - (n - 1) + beatIndex.toDouble()) / frameRate - onsetDelay)
        val error = beats - Math.round(beats)
        // A bigger gap may be an off-beat section: leave it to the loop.
        if (abs(error) < PHASE_CHECK_RANGE) anchorTime += PHASE_CHECK_GAIN * error * period
    }

    /** Sets the clock from the newest [n] frames: phase from a comb at [lag], bar start from the loudest beat. */
    private fun lock(lag: Float, n: Int) {
        linearise(n, forTempo = false)
        val last = n - 1
        val beatIndex = combBeat(lag, n)

        // Bars often start with the loudest beat, so pick the beat of 4 that hits hardest.
        var downbeat = 0
        var total = 0f
        var bestSum = -1f
        for (offset in 0 until 4) {
            var sum = 0f
            var position = beatIndex - offset * lag
            while (position >= 0f) {
                sum += sample(position, n)
                position -= 4 * lag
            }
            total += sum
            if (sum > bestSum) {
                bestSum = sum
                downbeat = offset
            }
        }
        if (bestSum < total / 4 * DOWNBEAT_MARGIN) downbeat = 0

        val frame = frames - last + beatIndex.toDouble()
        setClock(frame / frameRate - onsetDelay, downbeat.toLong(), lag / frameRate.toDouble(), heard = true)
        lockCount++
    }

    /** Where the latest beat sits in the newest [n] linearised frames, from a comb at [lag]. */
    private fun combBeat(lag: Float, n: Int): Float {
        val last = n - 1
        val candidates = ceil(lag).toInt().coerceAtMost(phaseScores.size)
        var best = 0
        for (offset in 0 until candidates) {
            var sum = 0f
            var position = (last - offset).toFloat()
            while (position >= 0f) {
                sum += sample(position, n)
                position -= lag
            }
            phaseScores[offset] = sum
            if (sum > phaseScores[best]) best = offset
        }
        val before = phaseScores[(best - 1 + candidates) % candidates]
        val after = phaseScores[(best + 1) % candidates]
        val curve = before - 2 * phaseScores[best] + after
        val shift = if (curve < 0f) (0.5f * (before - after) / curve).coerceIn(-0.5f, 0.5f) else 0f
        return last - best - shift
    }

    private fun sample(position: Float, n: Int): Float {
        val index = position.toInt()
        val fraction = position - index
        val next = if (index + 1 < n) linear[index + 1] else linear[index]
        return linear[index] + (next - linear[index]) * fraction
    }

    private companion object {
        const val WINDOW_S = 12f
        const val SHORT_WINDOW_S = 6f
        const val MIN_FILL_S = 4f
        const val ESTIMATE_EVERY_S = 0.5f
        const val MIN_BPM = 70f
        const val MAX_BPM = 180f
        const val CLOCK_MIN_BPM = 40.0
        const val CLOCK_MAX_BPM = 240.0
        const val PREFERRED_BPM = 120f
        const val PRIOR_OCTAVES = 1f
        const val MIN_ENERGY = 0.025f * 0.025f
        const val ACQUIRE_CONFIDENCE = 0.2f
        const val CONSISTENT = 0.02f
        const val PATIENT_CONFIDENCE = 0.12f
        const val PATIENT_ESTIMATES = 8
        // An unrelated tempo this close to the best one makes the reading unclear.
        const val RIVAL_SHARE = 0.8f
        const val RIVAL_TOLERANCE = 0.04f
        const val RIVAL_MIN_LAGS = 2f
        val RATIOS = floatArrayOf(1f, 2f, 0.5f, 1.5f, 2f / 3f, 4f / 3f, 0.75f)
        val OCTAVES = floatArrayOf(1f, 2f, 0.5f)
        const val SAME_TEMPO = 0.03f

        const val CHALLENGE_CONFIDENCE = 0.25f
        const val CHALLENGE_MARGIN = 1.3f
        const val SLOW_RELOCK_S = 20f
        const val WEAK_SUPPORT = 0.25f
        const val WEAK_RELOCK_S = 6f
        const val YOUNG_LOCK_S = 45.0
        const val STEADY_READINGS = 60
        const val PROVEN_SHARE = 0.5f
        const val STEADY_SHARE = 0.8f
        const val STEADY_LOCKED_SHARE = 0.05f

        const val SHORT_PROFILE_S = 1.5f
        const val LONG_PROFILE_S = 15f
        const val NOVELTY_DB = 4f
        const val NOVELTY_HOLD_S = 20.0
        const val FAST_CONFIDENCE = 0.3f
        const val FAST_MARGIN = 1.5f
        const val FAST_HOLD_S = 1.5f

        const val WINDOW_BEATS = 0.2
        const val PHASE_GAIN = 0.3
        const val PHASE_CHECK_GAIN = 0.3
        const val PHASE_CHECK_RANGE = 0.25
        const val REFINE_CONFIDENCE = 0.2f
        const val REFINE_GAIN = 0.1
        const val MAX_SHIFT_BEATS = 0.05
        const val MAX_DRIFT = 0.015
        const val COAST_PHASE_GAIN = 0.1
        const val COAST_MAX_SHIFT_BEATS = 0.02
        const val COAST_BELOW = 0.5f
        const val PULSE_ALPHA = 0.15f
        const val STRONG_SHARE = 0.4f
        const val REFERENCE_ALPHA = 0.1f
        const val REFERENCE_DECAY = 0.995f
        const val DISPLAY_HYSTERESIS = 0.75
        const val ONSET_DEVIATIONS = 1f
        const val MIN_ONSET = 1e-4f
        const val STATS_S = 2f
        const val DOWNBEAT_MARGIN = 1.1f

        const val GAP_S = 1.5f
        // About 20 dB under the song's usual loudness, or digital silence, counts as a gap.
        const val GAP_RATIO = 0.1f
        const val SILENCE_FLOOR = 1e-4f
        const val LOUD_S = 10f
        const val QUIET_ADAPT_S = 30f

        const val MIN_TAPS = 4
        const val MAX_TAPS = 8
        const val TAP_RESET_S = 2.0
        val LN2 = ln(2f)
    }
}
