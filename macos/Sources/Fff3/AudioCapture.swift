import AVFoundation
import AudioToolbox
import CoreAudio
import Foundation
import Fff3Core

/// The rate the analyser runs at, as on Android, so its tuning holds whatever the device rate.
let analysisRate = 48_000

/// What the music engine listens to. `read` blocks until a hop of mono samples at `analysisRate` is ready.
protocol AudioSource: AnyObject, Sendable {
    /// Host time, in nanoseconds of `DispatchTime`, when the newest sample handed out was captured.
    var lastSampleNanos: UInt64 { get }
    /// True for what this Mac plays, which reaches the ears after the output delay.
    var capturesPlayback: Bool { get }
    func open() throws
    func read(_ hop: inout [Float]) -> Bool
    func close()
}

struct AudioError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Samples from a capture callback to the engine thread. Converts to mono at `analysisRate`
/// on the way in, and remembers when the newest sample was captured.
final class SampleFifo: @unchecked Sendable {
    private let condition = NSCondition()
    private var buffer = [Float](repeating: 0, count: analysisRate * 2)
    private var start = 0
    private var count = 0
    private var newestNanos: UInt64 = 0
    private var handedNanos: UInt64 = 0
    private var closed = false
    private var converter: PcmConverter?
    private var converterKey = (0, 0)
    private var scratch = [Float](repeating: 0, count: 16_384)

    var lastSampleNanos: UInt64 { condition.withLock { handedNanos } }

    /// `samples` are interleaved, `frames` long. `firstSampleNanos` is the capture time of the first one.
    func push(_ samples: UnsafePointer<Float>, frames: Int, channels: Int, rate: Int, firstSampleNanos: UInt64) {
        condition.lock()
        defer { condition.unlock() }
        if converter == nil || converterKey != (channels, rate) {
            converter = PcmConverter(channels: channels, inputRate: rate, outputRate: analysisRate)
            converterKey = (channels, rate)
        }
        let length = frames * channels
        if scratch.count < converter!.maxOutput(length) { scratch = [Float](repeating: 0, count: converter!.maxOutput(length)) }
        let written = scratch.withUnsafeMutableBufferPointer { converter!.process(samples, length, $0.baseAddress!) }
        for i in 0..<written {
            if count == buffer.count {
                // The engine fell behind by a second: drop the oldest audio rather than lag more.
                start = (start + 1) % buffer.count
                count -= 1
            }
            buffer[(start + count) % buffer.count] = scratch[i]
            count += 1
        }
        newestNanos = firstSampleNanos + UInt64(Double(frames) / Double(rate) * 1e9)
        condition.signal()
    }

    func read(_ hop: inout [Float]) -> Bool {
        condition.lock()
        defer { condition.unlock() }
        while count < hop.count && !closed {
            if !condition.wait(until: Date().addingTimeInterval(2)) { return false }
        }
        if closed { return false }
        for i in 0..<hop.count { hop[i] = buffer[(start + i) % buffer.count] }
        start = (start + hop.count) % buffer.count
        count -= hop.count
        // Samples still waiting are newer than this hop.
        handedNanos = newestNanos - UInt64(Double(count) / Double(analysisRate) * 1e9)
        return true
    }

    func close() {
        condition.withLock {
            closed = true
            condition.broadcast()
        }
    }
}

func hostTimeNanos(_ hostTime: UInt64) -> UInt64 {
    var info = mach_timebase_info_data_t()
    mach_timebase_info(&info)
    return hostTime * UInt64(info.numer) / UInt64(info.denom)
}

/// What this Mac plays, through a Core Audio process tap on every process (macOS 14.2 and later).
/// The first start asks for the system audio recording permission.
final class SystemAudioSource: AudioSource, @unchecked Sendable {
    private let fifo = SampleFifo()
    private let ioQueue = DispatchQueue(label: "com.kernoeb.fff3.tap", qos: .userInteractive)
    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private var aggregateID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?

    var lastSampleNanos: UInt64 { fifo.lastSampleNanos }
    var capturesPlayback: Bool { true }

    func open() throws {
        guard #available(macOS 14.2, *) else { throw AudioError(message: "System audio needs macOS 14.2 or later.") }
        let description = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
        description.uuid = UUID()
        description.muteBehavior = .unmuted
        description.isPrivate = true
        try check(AudioHardwareCreateProcessTap(description, &tapID), "create the audio tap")

        var format = AudioStreamBasicDescription()
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        var address = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        try check(AudioObjectGetPropertyData(tapID, &address, 0, nil, &size, &format), "read the tap format")
        let rate = Int(format.mSampleRate)
        let channels = Int(format.mChannelsPerFrame)
        let interleaved = format.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0

        let outputUID = try OutputDevice.defaultUID()
        let aggregate: [String: Any] = [
            kAudioAggregateDeviceNameKey: "fff3 system audio",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: outputUID,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: outputUID]],
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapDriftCompensationKey: true, kAudioSubTapUIDKey: description.uuid.uuidString]],
        ]
        try check(AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &aggregateID), "create the capture device")

        let fifo = self.fifo
        var mono = [Float](repeating: 0, count: 0)
        var logged = false
        try check(AudioDeviceCreateIOProcIDWithBlock(&procID, aggregateID, ioQueue) { _, input, inputTime, _, _ in
            let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
            if logged == false, ProcessInfo.processInfo.environment["FFF3_BEAT_LOG"] != nil {
                logged = true
                let shape = buffers.map { "\($0.mNumberChannels)ch \($0.mDataByteSize)B" }.joined(separator: ", ")
                FileHandle.standardError.write("fff3-tap rate=\(rate) channels=\(channels) interleaved=\(interleaved) buffers=[\(shape)]\n".data(using: .utf8)!)
            }
            // An output device with inputs, such as a USB audio interface, puts its own input
            // buffers first. The tap's buffers come last.
            let tap = Array(buffers.suffix(interleaved ? 1 : channels))
            guard let first = tap.first, let data = first.mData else { return }
            let firstNanos = hostTimeNanos(inputTime.pointee.mHostTime)
            if tap.count == 1 {
                let perBuffer = max(Int(first.mNumberChannels), 1)
                let frames = Int(first.mDataByteSize) / MemoryLayout<Float>.size / perBuffer
                fifo.push(data.assumingMemoryBound(to: Float.self), frames: frames, channels: perBuffer, rate: rate, firstSampleNanos: firstNanos)
            } else {
                // One buffer per channel: interleave them so the converter can mix them down.
                let frames = Int(first.mDataByteSize) / MemoryLayout<Float>.size
                let count = tap.count
                if mono.count < frames * count { mono = [Float](repeating: 0, count: frames * count) }
                for (c, buffer) in tap.enumerated() {
                    guard let samples = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
                    for f in 0..<frames { mono[f * count + c] = samples[f] }
                }
                mono.withUnsafeBufferPointer {
                    fifo.push($0.baseAddress!, frames: frames, channels: count, rate: rate, firstSampleNanos: firstNanos)
                }
            }
        }, "start the capture")
        try check(AudioDeviceStart(aggregateID, procID), "start the capture device")
    }

    func read(_ hop: inout [Float]) -> Bool { fifo.read(&hop) }

    func close() {
        fifo.close()
        if aggregateID != kAudioObjectUnknown {
            AudioDeviceStop(aggregateID, procID)
            if let procID { AudioDeviceDestroyIOProcID(aggregateID, procID) }
            AudioHardwareDestroyAggregateDevice(aggregateID)
            aggregateID = AudioObjectID(kAudioObjectUnknown)
        }
        if tapID != kAudioObjectUnknown, #available(macOS 14.2, *) {
            AudioHardwareDestroyProcessTap(tapID)
            tapID = AudioObjectID(kAudioObjectUnknown)
        }
    }

    private func check(_ status: OSStatus, _ what: String) throws {
        if status != noErr { throw AudioError(message: "Could not \(what) (error \(status)).") }
    }
}

/// The room, through the default microphone.
final class MicrophoneSource: AudioSource, @unchecked Sendable {
    private let fifo = SampleFifo()
    private let engine = AVAudioEngine()

    var lastSampleNanos: UInt64 { fifo.lastSampleNanos }
    var capturesPlayback: Bool { false }

    func open() throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw AudioError(message: "No microphone found.") }
        let rate = Int(format.sampleRate)
        let fifo = self.fifo
        var interleaved = [Float]()
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, time in
            guard let channels = buffer.floatChannelData else { return }
            let frames = Int(buffer.frameLength)
            let count = Int(buffer.format.channelCount)
            let nanos = time.isHostTimeValid ? hostTimeNanos(time.hostTime) : nowNanos()
            if interleaved.count < frames * count { interleaved = [Float](repeating: 0, count: frames * count) }
            for c in 0..<count {
                for f in 0..<frames { interleaved[f * count + c] = channels[c][f] }
            }
            interleaved.withUnsafeBufferPointer {
                fifo.push($0.baseAddress!, frames: frames, channels: count, rate: rate, firstSampleNanos: nanos)
            }
        }
        engine.prepare()
        try engine.start()
    }

    func read(_ hop: inout [Float]) -> Bool { fifo.read(&hop) }

    func close() {
        fifo.close()
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }
}

/// Plays a decoded song (48 kHz mono f32le) in real time instead of capturing, so music sync
/// can be tested on the strips without audio permissions. Set FFF3_AUDIO_FILE to use it.
final class FileSource: AudioSource, @unchecked Sendable {
    private let path: String
    private var samples: [Float] = []
    private var position = 0
    private var started: UInt64 = 0
    private let lock = NSLock()
    private var newest: UInt64 = 0
    private var closed = false

    init(path: String) { self.path = path }

    var lastSampleNanos: UInt64 { lock.withLock { newest } }
    var capturesPlayback: Bool { false }

    func open() throws {
        guard let data = FileManager.default.contents(atPath: path) else { throw AudioError(message: "Cannot read \(path).") }
        samples = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        started = nowNanos()
    }

    func read(_ hop: inout [Float]) -> Bool {
        if lock.withLock({ closed }) || position + hop.count > samples.count { return false }
        let due = started + UInt64(Double(position + hop.count) / Double(analysisRate) * 1e9)
        let now = nowNanos()
        if due > now { Thread.sleep(forTimeInterval: Double(due - now) / 1e9) }
        for i in 0..<hop.count { hop[i] = samples[position + i] }
        position += hop.count
        lock.withLock { newest = due }
        return true
    }

    func close() { lock.withLock { closed = true } }
}

/// The default output device: its name, and the time from captured audio to sound out of it.
enum OutputDevice {
    static func defaultID() -> AudioObjectID? {
        var id = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &id)
        return status == noErr && id != kAudioObjectUnknown ? id : nil
    }

    static func defaultUID() throws -> String {
        guard let id = defaultID(), let uid = string(id, kAudioDevicePropertyDeviceUID) else {
            throw AudioError(message: "No sound output found.")
        }
        return uid
    }

    static func name() -> String? { defaultID().flatMap { string($0, kAudioObjectPropertyName) } }

    static var isBluetooth: Bool {
        guard let id = defaultID(), let transport = uint32(id, kAudioDevicePropertyTransportType, kAudioObjectPropertyScopeGlobal) else { return false }
        return transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE
    }

    /// Seconds, from what Core Audio reports: device and stream latency, safety offset and one buffer.
    static func latency() -> Double {
        guard let id = defaultID() else { return 0 }
        let scope = kAudioObjectPropertyScopeOutput
        var frames = 0
        frames += Int(uint32(id, kAudioDevicePropertyLatency, scope) ?? 0)
        frames += Int(uint32(id, kAudioDevicePropertySafetyOffset, scope) ?? 0)
        frames += Int(uint32(id, kAudioDevicePropertyBufferFrameSize, scope) ?? 0)
        if let stream = firstOutputStream(id) { frames += Int(uint32(stream, kAudioStreamPropertyLatency, kAudioObjectPropertyScopeGlobal) ?? 0) }
        var rate = Float64(0)
        var size = UInt32(MemoryLayout<Float64>.size)
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyNominalSampleRate, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &rate) == noErr, rate > 0 else { return 0 }
        return Double(frames) / rate
    }

    private static func firstOutputStream(_ id: AudioObjectID) -> AudioObjectID? {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreams, mScope: kAudioObjectPropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
        var size = UInt32(0)
        guard AudioObjectGetPropertyDataSize(id, &address, 0, nil, &size) == noErr, size > 0 else { return nil }
        var streams = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &streams) == noErr else { return nil }
        return streams.first
    }

    private static func uint32(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope) -> UInt32? {
        var value = UInt32(0)
        var size = UInt32(MemoryLayout<UInt32>.size)
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
        return AudioObjectGetPropertyData(id, &address, 0, nil, &size, &value) == noErr ? value : nil
    }

    private static func string(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &value) == noErr, let value else { return nil }
        return value.takeRetainedValue() as String
    }
}
