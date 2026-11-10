// Scores Beat lock tempo on decoded songs, like BeatBenchTest.kt, with the same input and output.
//
//   FFF3_BENCH="song.f32=114;other.f32=92@40" swift run -c release Fff3Bench
//
// Songs are 48 kHz mono f32le. "=114@40" starts listening 40 s in. FFF3_BENCH_LOCKS=1 prints every
// lock; FFF3_BEATS_DIR=dir writes each song's beat times; FFF3_WHITEN=0 uses the plain flux.

import Foundation
import Fff3Core

let startS = 15.0
let endS = 10.0
let environment = ProcessInfo.processInfo.environment

struct Score: Sendable {
    var right = 0.0
    var wrong = 0.0
    var seconds = 0.0
    var firstRight: Double?
    var heardRight = 0.0
    var heardWrong = 0.0
}

func pad(_ text: String, _ width: Int) -> String {
    text.count >= width ? text : text + String(repeating: " ", count: width - text.count)
}

func format(_ format: String, _ values: CVarArg...) -> String {
    String(format: format, locale: Locale(identifier: "en_US_POSIX"), arguments: values)
}

let printLock = NSLock()

func score(path: String, truth: Double, startAt: Double) -> Score {
    guard let data = FileManager.default.contents(atPath: path) else {
        FileHandle.standardError.write("cannot read \(path)\n".data(using: .utf8)!)
        exit(1)
    }
    let samples: [Float] = data.withUnsafeBytes { raw in
        Array(raw.bindMemory(to: Float.self))
    }
    var position = Int(startAt * 48_000) / 512 * 512
    // The start snaps to a whole hop, so beat times count from where listening really began.
    let startSeconds = Double(position) / 48_000.0
    let analyzer = MusicAnalyzer(sampleRate: 48_000, bandCount: 24, whiten: environment["FFF3_WHITEN"] != "0")
    let tracker = BeatTracker(frameRate: analyzer.frameRate, onsetDelay: Double(analyzer.fftSize) / 2.0 / 48_000)
    var hop = [Float](repeating: 0, count: analyzer.hopSize)
    let dt = Double(analyzer.hopSize) / 48_000.0
    let length = Double(samples.count - position) / 48_000.0
    func isRight(_ bpm: Double) -> Bool { [0.5, 1.0, 2.0].contains { abs(bpm - truth * $0) <= truth * $0 * 0.03 } }
    var result = Score()
    let name = URL(fileURLWithPath: path).lastPathComponent
    let printLocks = environment["FFF3_BENCH_LOCKS"] != nil
    var locks = 0
    var beatsOut: FileHandle?
    if let dir = environment["FFF3_BEATS_DIR"] {
        let file = "\(dir)/\(name)@\(Int(startAt)).txt"
        FileManager.default.createFile(atPath: file, contents: nil)
        beatsOut = FileHandle(forWritingAtPath: file)
    }
    var lastBeat = Int.min
    while samples.count - position >= hop.count {
        for i in 0..<hop.count { hop[i] = samples[position + i] }
        position += hop.count
        let frame = analyzer.process(hop)
        tracker.process(frame.beatOnset, rms: frame.rms, bass: frame.rawBass, mid: frame.rawMid, treble: frame.rawTreble)
        let t = tracker.time
        let locked = tracker.state == .Locked
        let ok = locked && isRight(Double(tracker.bpm))
        if printLocks && tracker.lockCount != locks {
            locks = tracker.lockCount
            let line = format(
                "LOCK %@ %6.1f s %@ %6.1f conf %.2f %@",
                pad("\(name)@\(Int(startAt))", 22), t, pad(tracker.lastReason.rawValue, 10), Double(tracker.bpm),
                Double(tracker.heardConfidence), ok ? "RIGHT" : "WRONG"
            )
            printLock.lock(); print(line); printLock.unlock()
        }
        if let beatsOut, locked {
            // Song time of each beat the clock gives, with its tempo, to check the phase offline.
            let beats = tracker.beatsAt(t)
            let whole = Int(beats.rounded(.down))
            if whole != lastBeat {
                lastBeat = whole
                let line = format("%.5f %.3f\n", startSeconds + t - (beats - Double(whole)) * tracker.period, Double(tracker.bpm))
                beatsOut.write(line.data(using: .utf8)!)
            }
        }
        if ok && result.firstRight == nil { result.firstRight = t }
        // Skip the start, which every song needs to fill its window, and the fade at the end.
        if t < startS || t > length - endS { continue }
        result.seconds += dt
        if ok { result.right += dt } else if locked { result.wrong += dt }
        let heard = Double(tracker.heardBpm)
        if heard > 0 {
            if isRight(heard) { result.heardRight += dt } else { result.heardWrong += dt }
        }
    }
    try? beatsOut?.close()
    return result
}

final class Results: @unchecked Sendable {
    private let lock = NSLock()
    private var scores: [Score?]
    init(count: Int) { scores = Array(repeating: nil, count: count) }
    subscript(index: Int) -> Score? {
        get { lock.lock(); defer { lock.unlock() }; return scores[index] }
        set { lock.lock(); scores[index] = newValue; lock.unlock() }
    }
}

guard let spec = environment["FFF3_BENCH"] else {
    print("Set FFF3_BENCH to run the benchmark, for example FFF3_BENCH=\"song.f32=114;other.f32=92@40\".")
    exit(2)
}
let entries: [(path: String, truth: String)] = spec.split(separator: ";").map {
    let parts = $0.split(separator: "=", maxSplits: 1)
    return (String(parts[0]), String(parts[1]))
}
let results = Results(count: entries.count)
// Songs are independent, so score them in parallel; print in the given order.
DispatchQueue.concurrentPerform(iterations: entries.count) { index in
    let truth = entries[index].truth
    let bpm = Double(truth.split(separator: "@")[0])!
    let start = truth.contains("@") ? Double(truth.split(separator: "@")[1])! : 0
    results[index] = score(path: entries[index].path, truth: bpm, startAt: start)
}
var totalRight = 0.0
var totalWrong = 0.0
var total = 0.0
for (index, entry) in entries.enumerated() {
    let score = results[index]!
    totalRight += score.right
    totalWrong += score.wrong
    total += score.seconds
    let start = entry.truth.contains("@") ? "@" + entry.truth.split(separator: "@")[1] : ""
    let first = score.firstRight.map { format("%.1f s", $0) } ?? "never"
    print(format(
        "BENCH %@ right %5.1f%%  wrong %5.1f%%  seconds %5.1f  first right %@  heard %5.1f%% %5.1f%%",
        pad(URL(fileURLWithPath: entry.path).lastPathComponent + start, 24),
        100 * score.right / score.seconds, 100 * score.wrong / score.seconds, score.seconds, first,
        100 * score.heardRight / score.seconds, 100 * score.heardWrong / score.seconds
    ))
}
print(format("BENCH TOTAL right %5.1f%%  wrong %5.1f%%", 100 * totalRight / total, 100 * totalWrong / total))
