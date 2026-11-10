import Foundation

/// An in-place radix-2 FFT of a fixed `size`, a power of 2.
/// Tables are built once, and `magnitudes` allocates nothing, so it can run on the audio thread.
public final class Fft {
    public let size: Int
    private let cosTable: [Float]
    private let sinTable: [Float]
    private let reversed: [Int]
    private var re: [Float]
    private var im: [Float]

    public init(size: Int) {
        precondition(size >= 2 && size & (size - 1) == 0, "Size must be a power of 2: \(size)")
        self.size = size
        cosTable = (0..<size / 2).map { Float(Foundation.cos(2 * Double.pi * Double($0) / Double(size))) }
        sinTable = (0..<size / 2).map { Float(Foundation.sin(2 * Double.pi * Double($0) / Double(size))) }
        let bits = size.trailingZeroBitCount
        reversed = (0..<size).map { value in
            var result = 0
            for bit in 0..<bits where value & (1 << bit) != 0 { result |= 1 << (bits - 1 - bit) }
            return result
        }
        re = [Float](repeating: 0, count: size)
        im = [Float](repeating: 0, count: size)
    }

    /// Transforms `real` and `imag` in place. Both must hold `size` values.
    public func transform(_ real: inout [Float], _ imag: inout [Float]) {
        real.withUnsafeMutableBufferPointer { real in
            imag.withUnsafeMutableBufferPointer { imag in
                transform(real, imag)
            }
        }
    }

    private func transform(_ real: UnsafeMutableBufferPointer<Float>, _ imag: UnsafeMutableBufferPointer<Float>) {
        for i in 0..<size {
            let j = reversed[i]
            if j > i {
                let tr = real[i]; real[i] = real[j]; real[j] = tr
                let ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        cosTable.withUnsafeBufferPointer { cosTable in
            sinTable.withUnsafeBufferPointer { sinTable in
                var half = 1
                while half < size {
                    let step = size / (half * 2)
                    var start = 0
                    while start < size {
                        for k in 0..<half {
                            let wr = cosTable[k * step]
                            let wi = -sinTable[k * step]
                            let a = start + k
                            let b = a + half
                            // Two separate roundings, as on the JVM: no fused multiply-add.
                            let xr = (real[b] * wr) - (imag[b] * wi)
                            let xi = (real[b] * wi) + (imag[b] * wr)
                            real[b] = real[a] - xr
                            imag[b] = imag[a] - xi
                            real[a] += xr
                            imag[a] += xi
                        }
                        start += half * 2
                    }
                    half *= 2
                }
            }
        }
    }

    /// Writes the magnitude of bins 0...size/2 of the real signal `input` into `out`, which must
    /// hold size / 2 + 1 values. Divided by size / 2, so a full-scale sine gives about 1 in its bin.
    public func magnitudes(_ input: [Float], _ out: inout [Float]) {
        for i in 0..<size { re[i] = input[i] }
        for i in 0..<size { im[i] = 0 }
        re.withUnsafeMutableBufferPointer { re in
            im.withUnsafeMutableBufferPointer { im in
                transform(re, im)
            }
        }
        let scale = 2 / Float(size)
        for k in 0...size / 2 { out[k] = JMath.sqrt(re[k] * re[k] + im[k] * im[k]) * scale }
    }
}
