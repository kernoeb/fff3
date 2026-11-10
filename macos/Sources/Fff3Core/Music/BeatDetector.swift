/// Finds beats in an onset strength signal (spectral flux), one value per analysis frame,
/// and estimates the tempo from the same signal.
///
/// A beat fires when the strength rises above the mean plus a few standard deviations of the
/// last second, so loud and quiet music both work. The tempo comes from the autocorrelation of
/// the last few seconds, which is steadier than the gaps between single beats.
final class BeatDetector {
    private let frameRate: Float
    private var history: [Float]
    private var historyCount = 0
    private var historyIndex = 0

    private var envelope: [Float]
    private var envelopeCount = 0
    private var envelopeIndex = 0
    private var linear: [Float]

    private let minLag: Int
    private let maxLag: Int
    private var correlation: [Float]

    private var framesSinceBeat = Int(Int32.max) / 2
    private let refractoryFrames: Int
    private var previous: Float = 0
    private var framesSinceTempo = 0

    /// Estimated tempo in beats per minute, or 0 until there is enough signal.
    private(set) var bpm: Float = 0

    private static let thresholdWindowS: Float = 1.2
    private static let tempoWindowS: Float = 6
    private static let tempoEveryFrames = 24
    private static let minBpm: Float = 60
    private static let maxBpm: Float = 200
    private static let preferredBpm: Float = 120
    private static let priorOctaves: Float = 1.2
    private static let minConfidence: Float = 0.1
    private static let maxDeviations: Float = 2.4
    private static let minDeviations: Float = 1.0
    private static let minStrength: Float = 0.04
    private static let ln2 = JMath.ln(2)

    init(frameRate: Float) {
        self.frameRate = frameRate
        history = [Float](repeating: 0, count: max(JMath.toInt(frameRate * Self.thresholdWindowS), 8))
        let envelopeSize = JMath.toInt(frameRate * Self.tempoWindowS)
        envelope = [Float](repeating: 0, count: envelopeSize)
        linear = [Float](repeating: 0, count: envelopeSize)
        minLag = max(JMath.toInt(frameRate * 60 / Self.maxBpm), 1)
        maxLag = min(JMath.toInt(frameRate * 60 / Self.minBpm), envelopeSize / 2)
        correlation = [Float](repeating: 0, count: maxLag + 2)
        refractoryFrames = JMath.toInt(frameRate * 60 / Self.maxBpm)
    }

    /// Feeds one frame's onset `strength` and returns true if it is a beat.
    /// `sensitivity` 0...1: higher catches softer beats.
    func process(_ strength: Float, sensitivity: Float = 0.5) -> Bool {
        var mean: Float = 0
        var deviation: Float = 0
        if historyCount > 0 {
            for i in 0..<historyCount { mean += history[i] }
            mean /= Float(historyCount)
            for i in 0..<historyCount { deviation += (history[i] - mean) * (history[i] - mean) }
            deviation = JMath.sqrt(deviation / Float(historyCount))
        }
        let factor = Self.maxDeviations - (Self.maxDeviations - Self.minDeviations) * sensitivity.clamped(0, 1)
        let threshold = mean + factor * deviation + Self.minStrength * (1.5 - sensitivity)

        framesSinceBeat += 1
        let beat = historyCount >= history.count / 4 &&
            strength > threshold &&
            strength >= previous &&
            framesSinceBeat >= refractoryFrames
        if beat { framesSinceBeat = 0 }
        previous = strength

        history[historyIndex] = strength
        historyIndex = (historyIndex + 1) % history.count
        if historyCount < history.count { historyCount += 1 }

        envelope[envelopeIndex] = strength
        envelopeIndex = (envelopeIndex + 1) % envelope.count
        if envelopeCount < envelope.count { envelopeCount += 1 }
        framesSinceTempo += 1
        if framesSinceTempo >= Self.tempoEveryFrames && envelopeCount >= envelope.count / 2 {
            framesSinceTempo = 0
            updateTempo()
        }
        return beat
    }

    func reset() {
        historyCount = 0; historyIndex = 0
        envelopeCount = 0; envelopeIndex = 0
        framesSinceBeat = Int(Int32.max) / 2
        previous = 0
        bpm = 0
    }

    private func updateTempo() {
        let n = envelopeCount
        let start = n < envelope.count ? 0 : envelopeIndex
        var mean: Float = 0
        for i in 0..<n {
            linear[i] = envelope[(start + i) % envelope.count]
            mean += linear[i]
        }
        mean /= Float(n)
        for i in 0..<n { linear[i] -= mean }

        var energy: Float = 0
        for i in 0..<n { energy += linear[i] * linear[i] }
        if energy <= 1e-9 { return }

        let top = min(maxLag + 1, n - 1)
        var bestLag = -1
        var bestScore: Float = 0
        if minLag <= top {
            for lag in minLag...top {
                var sum: Float = 0
                for i in 0..<(n - lag) { sum += linear[i] * linear[i + lag] }
                correlation[lag] = sum
                if lag > top - 1 { continue }
                // A mild preference for common tempos breaks ties between a tempo and its half or double.
                let bpmAtLag = 60 * frameRate / Float(lag)
                let octaves = JMath.ln(bpmAtLag / Self.preferredBpm) / Self.ln2
                let score = sum * JMath.exp(-0.5 * octaves * octaves / (Self.priorOctaves * Self.priorOctaves))
                if score > bestScore {
                    bestScore = score
                    bestLag = lag
                }
            }
        }
        if bestLag <= minLag || bestLag >= top || correlation[bestLag] < energy * Self.minConfidence { return }

        // Parabolic fit around the peak gives a fraction of a frame, about 0.2 BPM at 120 BPM.
        let a = correlation[bestLag - 1]
        let b = correlation[bestLag]
        let c = correlation[bestLag + 1]
        let curve = a - 2 * b + c
        let shift: Float = curve < 0 ? (0.5 * (a - c) / curve).clamped(-0.5, 0.5) : 0
        let estimate = 60 * frameRate / (Float(bestLag) + shift)
        bpm = (bpm == 0 || abs(estimate - bpm) > bpm * 0.08) ? estimate : bpm * 0.7 + estimate * 0.3
    }
}
