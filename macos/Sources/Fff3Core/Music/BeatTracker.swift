import Foundation

public enum BeatLockState: String, Sendable { case Acquiring, Locked }

/// Why the tracker last started over or took a new tempo.
public enum RelockReason: String, Sendable {
    case None
    /// Silence or a clear drop in loudness: the gap between 2 songs.
    case Gap
    /// The sound changed and the music no longer fits the beat grid: a new song without a gap.
    case SongChange
    /// Another tempo clearly beat the locked one for a long time.
    case NewTempo
}

/// Beat lock: estimates tempo and beat phase from the onset strength signal, then runs its own
/// beat clock, so the beat goes on through breaks, quiet parts and vocals.
///
/// A line-for-line port of `BeatTracker.kt`: keep both in step, and check with `Fff3Bench`.
/// Feed it one analysis frame at a time with `process`. Times are seconds of audio since the start,
/// on the same scale as `time`. Allocates nothing after construction.
public final class BeatTracker {
    private let frameRate: Float
    /// How late the onset signal is behind the sound, for example half the analysis window.
    private let onsetDelay: Double

    private var envelope: [Float]
    private var envelopeCount = 0
    private var envelopeIndex = 0
    private var linear: [Float]
    private let shortFrames: Int

    private let minLag: Int
    private let maxLag: Int
    private var correlation: [Float]
    // Taps and ×2 can set a clock slower than MIN_BPM, and the phase check combs at that period.
    private var phaseScores: [Float]

    private var frames: Int = 0
    private var framesSinceEstimate = 0

    private var anchorTime = 0.0
    private var anchorBeat: Int = 0
    /// Seconds per beat.
    public private(set) var period = 0.5
    private var acquiredPeriod = 0.5

    public private(set) var state = BeatLockState.Acquiring

    /// Goes up each time the clock is set from scratch (lock, tap, ×2, ÷2), so beat numbers may jump.
    public private(set) var epoch = 0

    /// How many times the tracker locked from the music (taps not counted).
    public private(set) var lockCount = 0

    /// Why the tracker last started over.
    public private(set) var lastReason = RelockReason.None

    /// Rounded tempo for the screen, or 0 while acquiring. Moves only when the tempo clearly moved.
    public private(set) var displayBpm = 0

    /// The latest tempo heard over the long window, or 0, and how sure that reading is (0...1).
    public private(set) var heardBpm: Float = 0
    public private(set) var heardConfidence: Float = 0
    /// The latest reading over the short window, only made while a song change is possible.
    public private(set) var shortBpm: Float = 0
    /// Seconds another tempo has been beating the locked one.
    public private(set) var challengeSeconds: Float = 0

    private var estimateConfidence: Float = 0
    /// The strongest tempo unrelated to the estimate, on the same scale as `estimateConfidence`.
    private var estimateRival: Float = 0
    /// How strongly the window hears the locked tempo, 0...1.
    public private(set) var lockedSupport: Float = 0
    /// Like `lockedSupport`, from the locked tempo, its half and its double only.
    private var octaveSupport: Float = 0
    /// When the clock was last set from what was heard, rather than from taps or ×2.
    private var guessedAt = -Double.infinity
    private var previousEstimate: Float = 0
    private var previousClear = false
    /// True while the lock came from weak readings and no clear reading has backed it yet.
    private var provisional = false
    private var agreeing = 0
    /// The latest readings, one per estimate, 0 when none: how often each tempo was heard lately.
    private var recent: [Float]
    private var recentCount = 0
    /// True once the window heard the locked tempo most of the time: the lock was not a guess.
    private var proven = false
    private var challenger: Float = 0
    /// The highest `octaveSupport` since the lock: a guess from an intro fades once the song starts.
    private var peakSupport: Float = 0
    private var fastCandidate: Float = 0
    private var fastSeconds: Float = 0

    private var onsetMean: Float = 0
    private var onsetDeviation: Float = 0
    private let statsAlpha: Float
    private var previousOnset: Float = 0

    private static let noBeat = Int.min
    private var windowBeat = BeatTracker.noBeat
    private var windowBest: Float = 0
    private var windowBestTime = 0.0
    private var windowBestBefore: Float = 0
    private var refinePending = false

    /// Share of recent beats with a strong onset; below one half the clock coasts.
    private var pulse: Float = 1
    private var strengthReference: Float = 0

    /// True while the beat is too weak to correct the clock: it runs on its own.
    public var coasting: Bool { state == .Locked && pulse < C.coastBelow }

    private var shortProfile: [Float] = [0, 0, 0]
    private var longProfile: [Float] = [0, 0, 0]
    private var profileReady = 0
    private let shortProfileAlpha: Float
    private let longProfileAlpha: Float
    private var profileChangedAt = -Double.infinity

    /// Biggest gap in dB between the recent and the long-term sound profile.
    public private(set) var novelty: Float = 0

    private var typicalLoudness: Float = 0
    private let loudFastAlpha: Float
    private let loudSlowAlpha: Float
    private var quietFrames = 0
    private let gapFrames: Int

    private var taps: [Double]
    private var tapCount = 0

    public init(frameRate: Float, onsetDelay: Double = 0.0) {
        self.frameRate = frameRate
        self.onsetDelay = onsetDelay
        let envelopeSize = JMath.toInt(frameRate * C.windowS)
        envelope = [Float](repeating: 0, count: envelopeSize)
        linear = [Float](repeating: 0, count: envelopeSize)
        shortFrames = JMath.toInt(frameRate * C.shortWindowS)
        minLag = JMath.toInt(JMath.floor(frameRate * 60 / C.maxBpm))
        maxLag = JMath.toInt(JMath.ceil(frameRate * 60 / C.minBpm))
        correlation = [Float](repeating: 0, count: min(envelopeSize / 2, 4 * maxLag + 8) + 2)
        phaseScores = [Float](repeating: 0, count: JMath.toInt((Double(frameRate) * 60.0 / C.clockMinBpm).rounded(.up)) + 2)
        recent = [Float](repeating: 0, count: C.steadyReadings)
        statsAlpha = 1 / (frameRate * C.statsS)
        shortProfileAlpha = 1 / (frameRate * C.shortProfileS)
        longProfileAlpha = 1 / (frameRate * C.longProfileS)
        loudFastAlpha = 1 / (frameRate * C.loudS)
        loudSlowAlpha = 1 / (frameRate * C.quietAdaptS)
        gapFrames = JMath.toInt(frameRate * C.gapS)
        taps = [Double](repeating: 0, count: C.maxTaps)
    }

    /// Audio time at the end of the latest frame.
    public var time: Double { Double(frames) / Double(frameRate) }

    /// Tempo of the clock, or 0 while acquiring.
    public var bpm: Float { state == .Locked ? Float(60.0 / period) : 0 }

    /// Beats at audio time `at`: the whole part counts beats (beat 0 of a bar is a multiple of 4), the fraction is the phase.
    public func beatsAt(_ at: Double) -> Double { Double(anchorBeat) + (at - anchorTime) / period }

    /// Feeds one frame: its onset `strength`, `rms` loudness and raw band loudness for the sound profile.
    public func process(_ strength: Float, rms: Float, bass: Float = 0, mid: Float = 0, treble: Float = 0) {
        frames += 1
        let now = time

        // Between songs the sound stops or drops well below the song's usual level, even after a fade-out.
        let quiet = rms < C.silenceFloor || rms < C.gapRatio * typicalLoudness
        typicalLoudness += (rms - typicalLoudness) * (quiet ? loudSlowAlpha : loudFastAlpha)
        quietFrames = quiet ? quietFrames + 1 : 0
        if quietFrames == gapFrames { restart(.Gap) }
        if quietFrames >= gapFrames {
            previousOnset = strength
            return
        }

        updateProfile(bass, mid, treble, now)
        if state == .Locked {
            track(strength, now - onsetDelay)
        }

        onsetMean += (strength - onsetMean) * statsAlpha
        onsetDeviation += (abs(strength - onsetMean) - onsetDeviation) * statsAlpha
        previousOnset = strength

        envelope[envelopeIndex] = strength
        envelopeIndex = (envelopeIndex + 1) % envelope.count
        if envelopeCount < envelope.count { envelopeCount += 1 }

        framesSinceEstimate += 1
        if framesSinceEstimate >= JMath.toInt(frameRate * C.estimateEveryS) && Float(envelopeCount) >= frameRate * C.minFillS {
            framesSinceEstimate = 0
            onEstimate(now)
        }
    }

    /// A tap the user made on the beat, at audio time `at`. 4 taps or more set the clock.
    public func tap(_ at: Double) {
        if tapCount > 0 && at - taps[(tapCount - 1) % C.maxTaps] > C.tapResetS { tapCount = 0 }
        taps[tapCount % C.maxTaps] = at
        tapCount += 1
        if tapCount < C.minTaps { return }
        // A least-squares line through the last taps evens out the user's timing.
        let count = min(tapCount, C.maxTaps)
        let first = tapCount - count
        var meanX = 0.0
        var meanY = 0.0
        for i in 0..<count {
            meanX += Double(i)
            meanY += taps[(first + i) % C.maxTaps]
        }
        meanX /= Double(count)
        meanY /= Double(count)
        var covariance = 0.0
        var variance = 0.0
        for i in 0..<count {
            let dx = Double(i) - meanX
            covariance += dx * (taps[(first + i) % C.maxTaps] - meanY)
            variance += dx * dx
        }
        let slope = covariance / variance
        if !(C.clockMinBpm...C.clockMaxBpm).contains(60.0 / slope) { return }
        let lastBeatTime = meanY + slope * (Double(count - 1) - meanX)
        // The first tap of a run is taken as the first beat of a bar.
        setClock(lastBeatTime, tapCount - 1, slope)
    }

    /// Multiplies the tempo by `factor`, 2 or 0.5, to fix a tempo heard at half or double speed.
    public func scaleTempo(_ factor: Double) {
        if state != .Locked { return }
        let newPeriod = period / factor
        if !(C.clockMinBpm...C.clockMaxBpm).contains(60.0 / newPeriod) { return }
        let beat = JMath.toLong(beatsAt(time - onsetDelay).rounded(.down))
        setClock(anchorTime + Double(beat - anchorBeat) * period, beat, newPeriod)
    }

    private func restart(_ reason: RelockReason) {
        state = .Acquiring
        lastReason = reason
        envelopeCount = 0
        envelopeIndex = 0
        previousEstimate = 0
        previousClear = false
        agreeing = 0
        provisional = false
        recentCount = 0
        heardBpm = 0
        displayBpm = 0
        framesSinceEstimate = 0
        profileReady = 0
        profileChangedAt = -Double.infinity
        resetEvidence()
    }

    private func resetEvidence() {
        challenger = 0
        challengeSeconds = 0
        fastCandidate = 0
        fastSeconds = 0
        windowBeat = Self.noBeat
        refinePending = false
    }

    private func setClock(_ beatTime: Double, _ beat: Int, _ newPeriod: Double, heard: Bool = false) {
        guessedAt = heard ? time : -Double.infinity
        anchorTime = beatTime
        anchorBeat = beat
        period = newPeriod
        acquiredPeriod = newPeriod
        state = .Locked
        displayBpm = JMath.roundToInt(60.0 / newPeriod)
        pulse = 1
        strengthReference = 0
        provisional = false
        proven = false
        peakSupport = 0
        resetEvidence()
        epoch += 1
    }

    private func beatTime(_ beat: Int) -> Double { anchorTime + Double(beat - anchorBeat) * period }

    /// The loop: one correction per beat, from the strongest onset in a window around the prediction.
    private func track(_ strength: Float, _ eventTime: Double) {
        if refinePending {
            refinePending = false
            // A parabola through the peak and its neighbours places the onset between frames.
            let a = windowBestBefore
            let b = windowBest
            let curve = a - 2 * b + strength
            if curve < 0 { windowBestTime += Double((0.5 * (a - strength) / curve).clamped(-0.5, 0.5) / frameRate) }
        }
        let beat = JMath.roundToLong(beatsAt(eventTime))
        let inWindow = abs(eventTime - beatTime(beat)) <= C.windowBeats * period
        if windowBeat != Self.noBeat && (!inWindow || beat != windowBeat) { closeWindow() }
        if !inWindow { return }
        if windowBeat != beat {
            windowBeat = beat
            windowBest = 0
        }
        if strength > windowBest {
            windowBest = strength
            windowBestTime = eventTime
            windowBestBefore = previousOnset
            refinePending = true
        }
    }

    private func closeWindow() {
        let beat = windowBeat
        windowBeat = Self.noBeat
        let strong = windowBest > onsetMean + C.onsetDeviations * onsetDeviation &&
            windowBest >= C.minOnset &&
            windowBest >= C.strongShare * strengthReference
        pulse += ((strong ? 1 : 0) - pulse) * C.pulseAlpha
        if !strong {
            // A long weak stretch lowers the bar slowly, so a quieter song can lead the clock again.
            if coasting { strengthReference *= C.referenceDecay }
            return
        }
        let wasCoasting = coasting
        if !wasCoasting {
            strengthReference = strengthReference == 0 ? windowBest : strengthReference + (windowBest - strengthReference) * C.referenceAlpha
        }
        let predicted = beatTime(beat)
        let error = windowBestTime - predicted
        let maxShift = (wasCoasting ? C.coastMaxShiftBeats : C.maxShiftBeats) * period
        let gain = wasCoasting ? C.coastPhaseGain : C.phaseGain
        anchorTime = predicted + (gain * error).clamped(-maxShift, maxShift)
        anchorBeat = beat
    }

    /// Fine-tunes the tempo from a confident long-window reading of the same tempo. The loop itself
    /// only moves the phase: onsets near a beat come from many instruments and would drag the tempo.
    private func refineTempo(_ estimate: Float) {
        if coasting || estimateConfidence < C.refineConfidence { return }
        let locked = 60.0 / period
        if abs(Double(estimate) - locked) > locked * C.maxDrift { return }
        // Re-anchor on the last beat, so the new period does not move beats already passed.
        let beat = JMath.toLong(beatsAt(time - onsetDelay).rounded(.down))
        anchorTime = beatTime(beat)
        anchorBeat = beat
        period = (period + (60.0 / Double(estimate) - period) * C.refineGain)
            .clamped(acquiredPeriod * (1 - C.maxDrift), acquiredPeriod * (1 + C.maxDrift))
        let shown = 60.0 / period
        if abs(shown - Double(displayBpm)) > C.displayHysteresis { displayBpm = JMath.roundToInt(shown) }
    }

    private func updateProfile(_ bass: Float, _ mid: Float, _ treble: Float, _ now: Double) {
        let total = bass * bass + mid * mid + treble * treble
        if total < 1e-12 { return }
        let alphaShort: Float = profileReady == 0 ? 1 : shortProfileAlpha
        let alphaLong: Float = profileReady == 0 ? 1 : longProfileAlpha
        var biggest: Float = 0
        for band in 0...2 {
            let value = band == 0 ? bass : band == 1 ? mid : treble
            let share = 10 * JMath.log10(value * value / total + 1e-6)
            shortProfile[band] += (share - shortProfile[band]) * alphaShort
            longProfile[band] += (share - longProfile[band]) * alphaLong
            biggest = max(biggest, abs(shortProfile[band] - longProfile[band]))
        }
        if profileReady < Int(Int32.max) { profileReady += 1 }
        novelty = biggest
        if Float(profileReady) > frameRate * C.shortProfileS && biggest > C.noveltyDb { profileChangedAt = now }
    }

    private func onEstimate(_ now: Double) {
        let lag = estimateLag(envelopeCount)
        let estimate: Float = lag > 0 ? 60 * frameRate / lag : 0
        heardBpm = estimateConfidence >= C.acquireConfidence ? estimate : 0
        heardConfidence = estimateConfidence
        let clear = estimateConfidence >= C.acquireConfidence && estimateRival < C.rivalShare * estimateConfidence
        let consistent = estimate > 0 && previousEstimate > 0 && abs(estimate - previousEstimate) < previousEstimate * C.consistent
        let clearTwice = consistent && clear && previousClear
        previousEstimate = estimate
        previousClear = clear
        recent[recentCount % recent.count] = estimate
        recentCount += 1
        switch state {
        case .Acquiring:
            // 2 clear readings in a row lock at once. A weak or contested tempo must
            // come back for PATIENT_ESTIMATES readings in a row instead. Torn readings do not count.
            if !consistent {
                agreeing = 0
            } else if !torn(estimate, estimateConfidence, estimateRival, recent, recentCount) {
                agreeing += 1
            }
            if clearTwice || agreeing + 1 >= C.patientEstimates {
                lock(lag, envelopeCount)
                provisional = !clearTwice
            }
        case .Locked:
            agreeing = consistent ? agreeing + 1 : 0
            if provisional {
                if related(estimate, C.octaves) {
                    if clear { provisional = false }
                } else if now - guessedAt < C.youngLockS {
                    // A lock from weak readings gives way to the first clear tempo outside its octaves,
                    // or to one that came back as long as the lock needed and is now heard better.
                    let patient = !coasting && agreeing + 1 >= C.patientEstimates &&
                        estimateConfidence >= octaveSupport * C.patientMargin
                    if clearTwice || patient {
                        lastReason = .NewTempo
                        // The new lock is not provisional: letting it move again pulled right
                        // tempos away mid-song in tests (Heavy Is The Crown at 55 s).
                        lock(lag, envelopeCount)
                    }
                }
            }
            if estimate > 0 { refineTempo(estimate) }
            weighChallenger(lag, estimate, now)
            if !checkSteadyRival(lag, estimate) { checkSongChange(now) }
            if state == .Locked && !coasting { checkPhase() }
        }
    }

    /// Within a song: another tempo must clearly beat the locked one for a long time. Soon after a
    /// lock from what was heard, a tempo at 3:2 or 4:3 can also win, since the lock may be a guess,
    /// and sooner once the locked tempo fades, as when an intro ends.
    private func weighChallenger(_ lag: Float, _ estimate: Float, _ now: Double) {
        let young = now - guessedAt < C.youngLockS
        if young { peakSupport = max(peakSupport, octaveSupport) }
        if estimate <= 0 || estimateConfidence < C.challengeConfidence { return }
        let support = young ? octaveSupport : lockedSupport
        let faded = young && octaveSupport < peakSupport * C.fadedShare && related(estimate, C.ratios)
        let margin = faded ? C.fadedMargin : C.challengeMargin
        if related(estimate, young ? C.octaves : C.ratios) || estimateConfidence < support * margin {
            challengeSeconds = 0
            challenger = 0
            return
        }
        // A young lock's challenger often comes in turn at half and double speed: it is one tempo.
        let same = challenger > 0 && (near(estimate, challenger) ||
            young && (near(estimate, 2 * challenger) || near(estimate, challenger / 2)))
        if same {
            challengeSeconds += C.estimateEveryS
        } else {
            challenger = estimate
            challengeSeconds = C.estimateEveryS
        }
        // A young lock the window barely hears was likely a wrong guess: it yields sooner.
        let hold: Float = if young && support < C.weakSupport { C.weakRelockS } else if faded { C.fadedRelockS } else { C.slowRelockS }
        if challengeSeconds >= hold {
            lastReason = .NewTempo
            lock(lag, envelopeCount)
        }
    }

    private func near(_ heard: Float, _ target: Float) -> Bool { abs(heard - target) < target * C.consistent }

    /// A lock the window never heard most of the time, often from an intro, gives way to a tempo
    /// heard in most readings that hear one, over STEADY_READINGS estimates, while the locked one
    /// is almost never heard. Returns true when it re-locked.
    private func checkSteadyRival(_ lag: Float, _ estimate: Float) -> Bool {
        // Taps and ×2 come from the user: only a lock from what was heard can be a wrong guess.
        if proven || guessedAt == -Double.infinity { return false }
        let lockedShare = recentShare(bpm)
        if lockedShare >= C.provenShare {
            proven = true
            return false
        }
        if coasting || recentCount < recent.count || estimate <= 0 || related(estimate, C.octaves) { return false }
        // Quiet songs and vocal parts give readings with no tempo: they prove nothing either way.
        let heard = heardShare()
        if lockedShare > C.steadyLockedShare || heard < C.steadyHeard || recentShare(estimate) < C.steadyShare * heard { return false }
        lastReason = .NewTempo
        lock(lag, envelopeCount)
        return true
    }

    /// True when an unrelated `rival` tempo is as strong as `estimate` and the latest `readings`
    /// swapped between them: the music has 2 pulses, and picking one is a coin toss.
    func torn(_ estimate: Float, _ confidence: Float, _ rival: Float, _ readings: [Float], _ count: Int) -> Bool {
        if rival < C.tornRivalShare * confidence { return false }
        let backs = min(C.tornReadings, count)
        if backs > 1 {
            for back in 1..<backs {
                let heard = readings[(count - 1 - back) % readings.count]
                if heard > 0 && !C.ratios.contains(where: { abs(heard - estimate * $0) <= estimate * $0 * C.sameTempo }) { return true }
            }
        }
        return false
    }

    /// Share of the recent readings at `bpm`, its half or its double.
    private func recentShare(_ bpm: Float) -> Float {
        var count = 0
        for i in 0..<min(recentCount, recent.count) {
            let heard = recent[i]
            if heard > 0 && C.octaves.contains(where: { abs(heard - bpm * $0) <= bpm * $0 * C.sameTempo }) { count += 1 }
        }
        return Float(count) / Float(recent.count)
    }

    /// Share of the recent readings that heard a tempo at all.
    private func heardShare() -> Float {
        var count = 0
        for i in 0..<min(recentCount, recent.count) where recent[i] > 0 { count += 1 }
        return Float(count) / Float(recent.count)
    }

    /// A new song without a gap: the sound profile changed, and the short window clearly hears
    /// an unrelated tempo, for a little while. Re-locks from the new song only.
    private func checkSongChange(_ now: Double) {
        shortBpm = 0
        if now - profileChangedAt > C.noveltyHoldS || envelopeCount < shortFrames {
            fastSeconds = 0
            return
        }
        let longSupport = lockedSupport
        let lag = estimateLag(shortFrames)
        // The short reading is weighed against the locked tempo in the same short window; the
        // long-window support stays the one shown in logs.
        let shortSupport = lockedSupport
        lockedSupport = longSupport
        let estimate: Float = lag > 0 ? 60 * frameRate / lag : 0
        shortBpm = estimateConfidence >= C.acquireConfidence ? estimate : 0
        if estimate <= 0 || estimateConfidence < C.fastConfidence || related(estimate) ||
            estimateConfidence < shortSupport * C.fastMargin {
            fastSeconds = 0
            return
        }
        if fastCandidate > 0 && abs(estimate - fastCandidate) < fastCandidate * C.consistent {
            fastSeconds += C.estimateEveryS
        } else {
            fastCandidate = estimate
            fastSeconds = C.estimateEveryS
        }
        if fastSeconds >= C.fastHoldS {
            lastReason = .SongChange
            // Forget the old song, so the long window does not pull back to its tempo.
            envelopeCount = shortFrames
            recentCount = 0
            lock(lag, shortFrames)
        }
    }

    /// Half, double, 3:2 and 4:3 of the locked tempo are the same music, counted another way.
    private func related(_ heard: Float, _ ratios: [Float] = C.ratios) -> Bool {
        let locked = Float(60.0 / period)
        for ratio in ratios where abs(heard - locked * ratio) <= locked * ratio * C.sameTempo { return true }
        return false
    }

    /// Copies the newest `n` frames, oldest first; `forTempo` removes the mean and blurs them.
    private func linearise(_ n: Int, forTempo: Bool) {
        let start = (envelopeIndex - n + envelope.count) % envelope.count
        var mean: Float = 0
        for i in 0..<n {
            linear[i] = envelope[(start + i) % envelope.count]
            mean += linear[i]
        }
        if !forTempo { return }
        mean /= Float(n)
        // A light blur widens the sharp onset peaks, so a beat that falls between 2 whole lags still scores.
        var before = linear[0] - mean
        for i in 0..<n {
            let current = linear[i] - mean
            let after = (i + 1 < n ? linear[i + 1] : linear[i]) - mean
            linear[i] = (before + 2 * current + after) / 4
            before = current
        }
    }

    /// The beat period in frames over the newest `n` frames, with a fraction, or 0 when the signal
    /// has no clear tempo. Sets `estimateConfidence`, `estimateRival` and, while locked, `lockedSupport`.
    private func estimateLag(_ n: Int) -> Float {
        estimateConfidence = 0
        estimateRival = 0
        lockedSupport = 0
        octaveSupport = 0
        linearise(n, forTempo: true)
        let top = min(n / 2, correlation.count - 2)
        linear.withUnsafeBufferPointer { linear in
            for lag in 0...top {
                var sum: Float = 0
                for i in 0..<(n - lag) { sum += linear[i] * linear[i + lag] }
                correlation[lag] = sum / Float(n - lag)
            }
        }
        let energy = correlation[0]
        // A steady tone has a small periodic ripple in its flux: it is not a beat.
        if energy < C.minEnergy { return 0 }

        var bestLag = -1
        var bestScore: Float = 0
        let lastLag = min(maxLag, top - 1)
        if minLag <= lastLag {
            for lag in minLag...lastLag {
                // 2 and 4 beats back up the real beat: a 4/4 bar repeats every 4 beats, while a
                // syncopated or dotted pulse does not. The prior gently prefers tempos near 120 BPM.
                var sum = correlation[lag]
                var weights: Float = 1
                if 2 * lag + 1 <= top {
                    sum += 0.5 * peakNear(2 * lag, 1)
                    weights += 0.5
                }
                if 4 * lag + 2 <= top {
                    sum += 0.5 * peakNear(4 * lag, 2)
                    weights += 0.5
                }
                let octaves = JMath.ln(60 * frameRate / Float(lag) / C.preferredBpm) / C.ln2
                let score = sum / weights * JMath.exp(-0.5 * octaves * octaves / (C.priorOctaves * C.priorOctaves))
                if score > bestScore {
                    bestScore = score
                    bestLag = lag
                }
            }
        }
        if bestLag < 0 { return 0 }
        estimateConfidence = max(correlation[bestLag] / energy, 0)
        var rival: Float = 0
        for lag in minLag...lastLag where !relatedLag(lag, bestLag) { rival = max(rival, correlation[lag]) }
        estimateRival = max(rival / energy, 0)
        if state == .Locked {
            let lockedLag = period * Double(frameRate)
            for ratio in C.ratios {
                let at = Float(lockedLag / Double(ratio))
                if at < 1 || at >= Float(top - 1) { continue }
                let index = JMath.toInt(at)
                let value = correlation[index] + (correlation[index + 1] - correlation[index]) * (at - Float(index))
                lockedSupport = max(lockedSupport, value / energy)
                if C.octaves.contains(ratio) { octaveSupport = max(octaveSupport, value / energy) }
            }
        }
        // Weaker readings are kept: one alone proves nothing, but the same one many times does.
        if estimateConfidence < C.patientConfidence { return 0 }

        // The peaks at 2, 3 and 4 periods give a finer period than the first one alone.
        var sumPeaks: Float = 0
        var sumMultiples = 0
        for multiple in 1...4 {
            let centre = multiple * bestLag
            if centre + multiple + 1 > top { break }
            var peak = centre
            for lag in (centre - multiple)...(centre + multiple) where correlation[lag] > correlation[peak] { peak = lag }
            let a = correlation[peak - 1]
            let b = correlation[peak]
            let c = correlation[peak + 1]
            let curve = a - 2 * b + c
            let shift: Float = curve < 0 ? (0.5 * (a - c) / curve).clamped(-0.5, 0.5) : 0
            sumPeaks += Float(peak) + shift
            sumMultiples += multiple
        }
        return sumMultiples > 0 ? sumPeaks / Float(sumMultiples) : Float(bestLag)
    }

    /// True when `lag` is `bestLag` or one of its ratios, give or take. Peaks are a few lags
    /// wide, so at fast tempos their sides must not count as a rival tempo.
    func relatedLag(_ lag: Int, _ bestLag: Int) -> Bool {
        C.ratios.contains { abs(Float(lag) - Float(bestLag) / $0) <= max(Float(bestLag) / $0 * C.rivalTolerance, C.rivalMinLags) }
    }

    /// The highest correlation within `radius` lags of `centre`: peaks are sharp and fall between whole lags.
    private func peakNear(_ centre: Int, _ radius: Int) -> Float {
        var best = correlation[centre]
        for lag in (centre - radius)...(centre + radius) where correlation[lag] > best { best = correlation[lag] }
        return best
    }

    /// Nudges the clock towards the beat grid that fits the whole window best. The loop corrects
    /// each beat from the strongest nearby onset, so syncopated hits slowly pull it off the beat.
    private func checkPhase() {
        let n = envelopeCount
        linearise(n, forTempo: false)
        let beatIndex = combBeat(Float(period * Double(frameRate)), n)
        let beats = beatsAt((Double(frames - (n - 1)) + Double(beatIndex)) / Double(frameRate) - onsetDelay)
        let error = beats - Double(JMath.roundToLong(beats))
        // A bigger gap may be an off-beat section: leave it to the loop.
        if abs(error) < C.phaseCheckRange { anchorTime += C.phaseCheckGain * error * period }
    }

    /// Sets the clock from the newest `n` frames: phase from a comb at `lag`, bar start from the loudest beat.
    private func lock(_ lag: Float, _ n: Int) {
        linearise(n, forTempo: false)
        let last = n - 1
        let beatIndex = combBeat(lag, n)

        // Bars often start with the loudest beat, so pick the beat of 4 that hits hardest.
        var downbeat = 0
        var total: Float = 0
        var bestSum: Float = -1
        for offset in 0..<4 {
            var sum: Float = 0
            var position = beatIndex - Float(offset) * lag
            while position >= 0 {
                sum += sample(position, n)
                position -= 4 * lag
            }
            total += sum
            if sum > bestSum {
                bestSum = sum
                downbeat = offset
            }
        }
        if bestSum < total / 4 * C.downbeatMargin { downbeat = 0 }

        let frame = Double(frames - last) + Double(beatIndex)
        setClock(frame / Double(frameRate) - onsetDelay, downbeat, Double(lag) / Double(frameRate), heard: true)
        lockCount += 1
        agreeing = 0
    }

    /// Where the latest beat sits in the newest `n` linearised frames, from a comb at `lag`.
    private func combBeat(_ lag: Float, _ n: Int) -> Float {
        let last = n - 1
        let candidates = min(JMath.toInt(JMath.ceil(lag)), phaseScores.count)
        var best = 0
        for offset in 0..<candidates {
            var sum: Float = 0
            var position = Float(last - offset)
            while position >= 0 {
                sum += sample(position, n)
                position -= lag
            }
            phaseScores[offset] = sum
            if sum > phaseScores[best] { best = offset }
        }
        let before = phaseScores[(best - 1 + candidates) % candidates]
        let after = phaseScores[(best + 1) % candidates]
        let curve = before - 2 * phaseScores[best] + after
        let shift: Float = curve < 0 ? (0.5 * (before - after) / curve).clamped(-0.5, 0.5) : 0
        return Float(last - best) - shift
    }

    @inline(__always)
    private func sample(_ position: Float, _ n: Int) -> Float {
        let index = JMath.toInt(position)
        let fraction = position - Float(index)
        let next = index + 1 < n ? linear[index + 1] : linear[index]
        return linear[index] + (next - linear[index]) * fraction
    }

    private enum C {
        static let windowS: Float = 12
        static let shortWindowS: Float = 6
        static let minFillS: Float = 4
        static let estimateEveryS: Float = 0.5
        static let minBpm: Float = 70
        static let maxBpm: Float = 180
        static let clockMinBpm = 40.0
        static let clockMaxBpm = 240.0
        static let preferredBpm: Float = 120
        static let priorOctaves: Float = 1
        static let minEnergy: Float = 0.025 * 0.025
        static let acquireConfidence: Float = 0.2
        static let consistent: Float = 0.02
        static let patientConfidence: Float = 0.12
        static let patientEstimates = 8
        // A provisional lock yields to a patient tempo heard this much better than the locked one.
        static let patientMargin: Float = 1.2
        static let tornRivalShare: Float = 0.95
        static let tornReadings = 8
        // An unrelated tempo this close to the best one makes the reading unclear.
        static let rivalShare: Float = 0.8
        static let rivalTolerance: Float = 0.04
        static let rivalMinLags: Float = 2
        static let ratios: [Float] = [1, 2, 0.5, 1.5, 2 / 3, 4 / 3, 0.75]
        static let octaves: [Float] = [1, 2, 0.5]
        static let sameTempo: Float = 0.03

        static let challengeConfidence: Float = 0.25
        static let challengeMargin: Float = 1.3
        static let slowRelockS: Float = 20
        static let weakSupport: Float = 0.25
        static let weakRelockS: Float = 6
        static let youngLockS = 45.0
        // The locked tempo has lost 40% of its best support since the lock.
        static let fadedShare: Float = 0.6
        static let fadedMargin: Float = 1.1
        static let fadedRelockS: Float = 8
        static let steadyReadings = 60
        static let provenShare: Float = 0.5
        static let steadyShare: Float = 0.8
        // The window must hear some tempo in at least a quarter of the readings, 7.5 s of 30.
        static let steadyHeard: Float = 0.25
        static let steadyLockedShare: Float = 0.05

        static let shortProfileS: Float = 1.5
        static let longProfileS: Float = 15
        static let noveltyDb: Float = 4
        static let noveltyHoldS = 20.0
        static let fastConfidence: Float = 0.3
        static let fastMargin: Float = 1.5
        static let fastHoldS: Float = 1.5

        static let windowBeats = 0.2
        static let phaseGain = 0.3
        static let phaseCheckGain = 0.3
        static let phaseCheckRange = 0.25
        static let refineConfidence: Float = 0.2
        static let refineGain = 0.1
        static let maxShiftBeats = 0.05
        static let maxDrift = 0.015
        static let coastPhaseGain = 0.1
        static let coastMaxShiftBeats = 0.02
        static let coastBelow: Float = 0.5
        static let pulseAlpha: Float = 0.15
        static let strongShare: Float = 0.4
        static let referenceAlpha: Float = 0.1
        static let referenceDecay: Float = 0.995
        static let displayHysteresis = 0.75
        static let onsetDeviations: Float = 1
        static let minOnset: Float = 1e-4
        static let statsS: Float = 2
        static let downbeatMargin: Float = 1.1

        static let gapS: Float = 1.5
        // About 20 dB under the song's usual loudness, or digital silence, counts as a gap.
        static let gapRatio: Float = 0.1
        static let silenceFloor: Float = 1e-4
        static let loudS: Float = 10
        static let quietAdaptS: Float = 30

        static let minTaps = 4
        static let maxTaps = 8
        static let tapResetS = 2.0
        static let ln2 = JMath.ln(2)
    }
}
