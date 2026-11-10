import Foundation

/// What the analyser heard in one hop. Values in 0...1 are normalised by an automatic gain,
/// so quiet and loud rooms both use the full range. One instance is reused for every hop.
public final class MusicFrame {
    /// Overall loudness, 0...1.
    public var level: Float = 0
    public var bass: Float = 0
    public var mid: Float = 0
    public var treble: Float = 0
    /// Where the sound sits, 0 (low, bass-heavy) to 1 (bright).
    public var brightness: Float = 0
    /// Onset strength (spectral flux) of this hop, not normalised.
    public var onset: Float = 0
    /// Onset strength for the beat clock: the same flux after each bin is scaled to its recent peak.
    public var beatOnset: Float = 0
    /// Loudness before the automatic gain, for silence detection.
    public var rms: Float = 0
    /// Band loudness before the automatic gain, for the sound profile of a song.
    public var rawBass: Float = 0
    public var rawMid: Float = 0
    public var rawTreble: Float = 0
    public var beat = false
    /// Estimated tempo, or 0 while unknown.
    public var bpm: Float = 0
    /// Log-spaced spectrum bars for the visualiser, each 0...1.
    public var bands: [Float]

    init(bandCount: Int) {
        bands = [Float](repeating: 0, count: bandCount)
    }
}

/// Turns audio samples into `MusicFrame`s. Feed it `hopSize` samples at a time; each call
/// analyses the last `fftSize` samples. Allocates nothing after construction.
public final class MusicAnalyzer {
    public let sampleRate: Int
    public let fftSize: Int
    public let hopSize: Int
    /// False gives the beat clock the plain flux, to compare both in benchmarks.
    private let whiten: Bool
    public let frameRate: Float
    public let frame: MusicFrame

    /// 0...1. Higher reacts to quieter sound and softer beats.
    public var sensitivity: Float = 0.5

    private let fft: Fft
    private let window: [Float]
    private var ring: [Float]
    private var windowed: [Float]
    private var magnitudes: [Float]
    private var previousLog: [Float]
    private var previousWhite: [Float]
    private var binPeak: [Float]
    private var framesSeen = 0
    private let peakDecay: Float
    private let detector: BeatDetector

    private let binHz: Float
    private let bassEnd: Int
    private let midEnd: Int
    private let trebleEnd: Int
    private let fluxEnd: Int
    private let kickEnd: Int
    private let bandEdges: [Int]

    private let levelGain: PeakFollower
    private let bassGain: PeakFollower
    private let midGain: PeakFollower
    private let trebleGain: PeakFollower
    private let bandGain: PeakFollower
    private var bandValues: [Float]

    private static let logGain: Float = 1_000
    private static let whitenHalfLifeS: Float = 3
    private static let whitenFloor: Float = 1e-2
    // About a flat -55 dBFS noise: quieter bins are room or microphone noise.
    private static let whitenMin: Float = 0.03
    private static let primingFrames = 2
    private static let kickWeight: Float = 0.5
    private static let bandLowHz: Float = 40
    private static let bandHighHz: Float = 12_000
    private static let bandRelease: Float = 0.18

    public init(sampleRate: Int, fftSize: Int = 1024, hopSize: Int = 512, bandCount: Int = 24, whiten: Bool = true) {
        self.sampleRate = sampleRate
        self.fftSize = fftSize
        self.hopSize = hopSize
        self.whiten = whiten
        frameRate = Float(sampleRate) / Float(hopSize)
        frame = MusicFrame(bandCount: bandCount)
        fft = Fft(size: fftSize)
        window = (0..<fftSize).map { Float(0.5 - 0.5 * Foundation.cos(2 * Double.pi * Double($0) / Double(fftSize))) }
        ring = [Float](repeating: 0, count: fftSize)
        windowed = [Float](repeating: 0, count: fftSize)
        magnitudes = [Float](repeating: 0, count: fftSize / 2 + 1)
        previousLog = [Float](repeating: 0, count: fftSize / 2 + 1)
        previousWhite = [Float](repeating: 0, count: fftSize / 2 + 1)
        binPeak = [Float](repeating: 0, count: fftSize / 2 + 1)
        peakDecay = JMath.pow(0.5, 1 / (frameRate * Self.whitenHalfLifeS))
        detector = BeatDetector(frameRate: frameRate)

        let binHz = Float(sampleRate) / Float(fftSize)
        self.binHz = binHz
        func bin(_ hz: Float) -> Int { JMath.toInt(hz / binHz).clamped(1, fftSize / 2) }
        bassEnd = bin(250)
        midEnd = bin(2_000)
        trebleEnd = bin(10_000)
        fluxEnd = bin(8_000)
        kickEnd = bin(180)
        var edges = (0...bandCount).map { i in
            bin(Self.bandLowHz * JMath.pow(Self.bandHighHz / Self.bandLowHz, Float(i) / Float(bandCount)))
        }
        for i in 1..<edges.count where edges[i] <= edges[i - 1] { edges[i] = edges[i - 1] + 1 }
        bandEdges = edges

        levelGain = PeakFollower(frameRate: frameRate, floor: 0.004)
        bassGain = PeakFollower(frameRate: frameRate, floor: 0.002)
        midGain = PeakFollower(frameRate: frameRate, floor: 0.001)
        trebleGain = PeakFollower(frameRate: frameRate, floor: 0.0005)
        bandGain = PeakFollower(frameRate: frameRate, floor: 1)
        bandValues = [Float](repeating: 0, count: bandCount)
    }

    /// Analyses `samples` (at least `hopSize` values, mono, -1...1) and returns the updated `frame`.
    @discardableResult
    public func process(_ samples: [Float]) -> MusicFrame {
        for i in 0..<(fftSize - hopSize) { ring[i] = ring[i + hopSize] }
        for i in 0..<hopSize { ring[fftSize - hopSize + i] = samples[i] }

        var power: Float = 0
        for i in 0..<hopSize { power += samples[i] * samples[i] }
        let rms = JMath.sqrt(power / Float(hopSize))

        for i in 0..<fftSize { windowed[i] = ring[i] * window[i] }
        fft.magnitudes(windowed, &magnitudes)

        // Log compression makes the flux follow perceived changes, not just the loudest bins.
        // The beat clock gets a second flux where each bin is divided by its own recent peak, so
        // volume and speaker colour do not change its onsets. Bins far below the loudest stay at a floor.
        var loudest: Float = 0
        for k in 1...fluxEnd {
            binPeak[k] = max(magnitudes[k], binPeak[k] * peakDecay)
            loudest = max(loudest, binPeak[k])
        }
        let floor = max(loudest * Self.whitenFloor, Self.whitenMin)
        var flux: Float = 0
        var kickFlux: Float = 0
        var whiteFlux: Float = 0
        var whiteKickFlux: Float = 0
        for k in 1...fluxEnd {
            let log = JMath.ln(1 + Self.logGain * magnitudes[k])
            let rise = log - previousLog[k]
            previousLog[k] = log
            if rise > 0 {
                flux += rise
                if k <= kickEnd { kickFlux += rise }
            }
            let white = JMath.ln(1 + Self.logGain * magnitudes[k] / max(binPeak[k], floor))
            let whiteRise = white - previousWhite[k]
            previousWhite[k] = white
            if whiteRise > 0 {
                whiteFlux += whiteRise
                if k <= kickEnd { whiteKickFlux += whiteRise }
            }
        }
        // The first frames rise from nothing: that is the stream starting, not a beat.
        let primed = framesSeen >= Self.primingFrames
        if !primed { framesSeen += 1 }
        let strength: Float = primed ? flux / Float(fluxEnd) + Self.kickWeight * kickFlux / Float(kickEnd) : 0
        let whiteStrength: Float = primed ? whiteFlux / Float(fluxEnd) + Self.kickWeight * whiteKickFlux / Float(kickEnd) : 0
        frame.beatOnset = whiten ? whiteStrength : strength

        let gain = JMath.pow(2, (sensitivity - 0.5) * 3)
        frame.level = (levelGain.normalise(rms) * gain).clamped(0, 1)
        frame.rawBass = rmsOf(1, bassEnd)
        frame.rawMid = rmsOf(bassEnd + 1, midEnd)
        frame.rawTreble = rmsOf(midEnd + 1, trebleEnd)
        frame.bass = (bassGain.normalise(frame.rawBass) * gain).clamped(0, 1)
        frame.mid = (midGain.normalise(frame.rawMid) * gain).clamped(0, 1)
        frame.treble = (trebleGain.normalise(frame.rawTreble) * gain).clamped(0, 1)
        frame.onset = strength
        frame.rms = rms
        frame.brightness = centroid()
        frame.beat = detector.process(strength, sensitivity: sensitivity)
        frame.bpm = detector.bpm
        updateBands(gain)
        return frame
    }

    public func reset() {
        for i in ring.indices { ring[i] = 0 }
        for i in previousLog.indices { previousLog[i] = 0; previousWhite[i] = 0; binPeak[i] = 0 }
        framesSeen = 0
        detector.reset()
    }

    private func rmsOf(_ from: Int, _ to: Int) -> Float {
        var sum: Float = 0
        for k in from...to { sum += magnitudes[k] * magnitudes[k] }
        return JMath.sqrt(sum / Float(to - from + 1))
    }

    /// Spectral centroid on a log scale between 100 Hz and 6 kHz, mapped to 0...1.
    private func centroid() -> Float {
        var weighted: Float = 0
        var total: Float = 0
        for k in 1...trebleEnd {
            weighted += magnitudes[k] * Float(k)
            total += magnitudes[k]
        }
        if total < 1e-6 { return frame.brightness }
        let hz = weighted / total * binHz
        return (JMath.ln(max(hz, 100) / 100) / JMath.ln(60)).clamped(0, 1)
    }

    private func updateBands(_ gain: Float) {
        var loudest: Float = 0
        for i in frame.bands.indices {
            var peak: Float = 0
            for k in bandEdges[i]..<bandEdges[i + 1] where magnitudes[k] > peak { peak = magnitudes[k] }
            let value = JMath.ln(1 + Self.logGain * peak)
            bandValues[i] = value
            if value > loudest { loudest = value }
        }
        let scale = bandGain.peakAfter(loudest)
        for i in frame.bands.indices {
            let target = (bandValues[i] / scale * gain).clamped(0, 1)
            let old = frame.bands[i]
            // Rise at once, fall slowly, so the bars read as music and not as noise.
            frame.bands[i] = target > old ? target : old + (target - old) * Self.bandRelease
        }
    }
}

/// Tracks the recent peak of a signal, falling by half every few seconds, never below `floor`.
/// Dividing by it is a simple automatic gain.
final class PeakFollower {
    private let decay: Float
    private let floor: Float
    private var peak: Float

    init(frameRate: Float, floor: Float, halfLifeSeconds: Float = 4) {
        decay = JMath.pow(0.5, 1 / (frameRate * halfLifeSeconds))
        self.floor = floor
        peak = floor
    }

    func peakAfter(_ value: Float) -> Float {
        peak = max(value, max(peak * decay, floor))
        return peak
    }

    func normalise(_ value: Float) -> Float { value / peakAfter(value) }
}
