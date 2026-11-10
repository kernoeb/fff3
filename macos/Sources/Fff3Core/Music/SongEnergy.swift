import Foundation

/// How intense this moment of the song is: 0 for the calmest part heard lately, 1 for the loudest.
/// It compares loudness and attack density over about 2 seconds with the last 2 minutes,
/// so a chorus or a drop reads high and a verse or a break reads low, whatever the volume.
public final class SongEnergy {
    private var power = 0.0
    private var attacks = 0.0
    private var history = [Float](repeating: 0, count: C.historySeconds)
    private var sorted = [Float](repeating: 0, count: C.historySeconds)
    private var count = 0
    private var head = 0
    private var sinceSample: Float = 0
    private var low: Float = 0
    private var high: Float = 0

    /// The smoothed result of the last `update`, 0...1.
    public private(set) var value: Float = C.unknown

    public init() {}

    /// `rms` and `onset` come straight from `MusicFrame`, before any automatic gain.
    @discardableResult
    public func update(rms: Float, onset: Float, dt: Float) -> Float {
        let keep = Double(JMath.exp(-dt / C.windowS))
        power = power * keep + Double(rms) * Double(rms) * (1 - keep)
        attacks = attacks * keep + Double(onset) * (1 - keep)

        let silent = rms < C.silenceRms
        let score = Float(10 * Foundation.log10(power + 1e-12) + Double(C.attackWeight * 10) * Foundation.log10(attacks + 1e-9))
        sinceSample += dt
        if !silent && sinceSample >= 1 {
            sinceSample = 0
            history[head] = score
            head = (head + 1) % C.historySeconds
            if count < C.historySeconds { count += 1 }
            for i in 0..<count { sorted[i] = history[i] }
            sorted[0..<count].sort()
            low = sorted[JMath.toInt(Float(count) * C.lowPercentile)]
            high = sorted[JMath.toInt(Float(count - 1) * C.highPercentile)]
        }

        // Early on, the loudest part is often still to come: a verse should not read as a chorus.
        let top = high + C.headroomDb * max(1 - Float(count) / Float(C.headroomSeconds), 0)
        // A song with little dynamics stays near full, rather than stretching small changes.
        let span = max(top - low, C.minRangeDb)
        let target: Float = if silent { 0 } else if count < C.minHistory { C.unknown } else { ((score - (top - span)) / span).clamped(0, 1) }
        value += (target - value) * (1 - JMath.exp(-dt / C.smoothingS))
        return value
    }

    private enum C {
        static let historySeconds = 120
        static let headroomSeconds = 60
        static let headroomDb: Float = 3
        static let minHistory = 6
        static let windowS: Float = 1.5
        static let smoothingS: Float = 0.6
        static let attackWeight: Float = 1
        static let lowPercentile: Float = 0.15
        static let highPercentile: Float = 0.9
        static let minRangeDb: Float = 6
        static let silenceRms: Float = 1e-4
        static let unknown: Float = 0.8
    }
}
