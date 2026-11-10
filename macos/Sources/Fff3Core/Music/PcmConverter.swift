/// Turns interleaved PCM with any channel count and rate into mono samples at `outputRate`.
/// Keeps its state between calls, so chunks may end in the middle of a frame.
public final class PcmConverter {
    private let channels: Int
    public let outputRate: Int
    private let step: Double
    private var sum: Float = 0
    private var channel = 0
    private var previous: Float = 0
    // Where the next output sample sits, in input frames: 0 is `previous`, 1 is the next frame.
    private var position = 1.0

    public init(channels: Int, inputRate: Int, outputRate: Int) {
        precondition(channels >= 1 && inputRate > 0 && outputRate > 0)
        self.channels = channels
        self.outputRate = outputRate
        step = Double(inputRate) / Double(outputRate)
    }

    /// The most samples `process` can write for `length` input values.
    public func maxOutput(_ length: Int) -> Int { Int(Double(length / channels + 1) / step) + 2 }

    /// Converts `length` values from `input` and returns how many samples it wrote to `out`.
    public func process(_ input: UnsafePointer<Float>, _ length: Int, _ out: UnsafeMutablePointer<Float>) -> Int {
        var written = 0
        for i in 0..<length {
            sum += input[i]
            channel += 1
            if channel < channels { continue }
            let current = sum / Float(channels)
            sum = 0
            channel = 0
            // Linear interpolation is enough: the analyser only looks below 12 kHz.
            while position <= 1.0 {
                out[written] = previous + (current - previous) * Float(position)
                written += 1
                position += step
            }
            position -= 1.0
            previous = current
        }
        return written
    }
}

/// Says when captured audio has been digital silence for `holdMillis`.
/// Blocked apps give exact zeros, so the threshold sits near one step of 16-bit audio.
public final class SilenceDetector {
    private let holdMillis: Int
    private let threshold: Float
    private var silentSince = -1
    public private(set) var silent = false

    public init(holdMillis: Int, threshold: Float = 1e-4) {
        self.holdMillis = holdMillis
        self.threshold = threshold
    }

    /// `peak` is the loudest absolute sample of the latest block. Returns `silent`.
    public func update(peak: Float, nowMillis: Int) -> Bool {
        if peak > threshold {
            silentSince = -1
            silent = false
        } else {
            if silentSince < 0 { silentSince = nowMillis }
            silent = nowMillis - silentSince >= holdMillis
        }
        return silent
    }
}
