import Foundation
import Fff3Core

/// Settings the engine reads on every hop; the screen changes them while music runs.
struct MusicSettings: Sendable {
    var style = MusicStyle.Pulse
    var sensitivity: Float = 0.5
    var lightDelayMs = 50
    var beatColors = BeatColors.Bars
}

/// What the screen asks the beat clock to do, handled on the engine thread.
enum BeatCommand: Sendable {
    /// A tap on the beat, at this `DispatchTime` uptime in nanoseconds.
    case tap(UInt64)
    case scaleTempo(Double)
}

/// What the screen shows, sent about 30 times a second.
struct LiveMusic: Sendable {
    var bands = [Float](repeating: 0, count: 24)
    var color = Rgb.black
    var level: Float = 0
    var beatPhase: Float = -1
    var beatInBar = 0
    var locked = false
    var displayBpm = 0
    var energy: Float = 0
    var outputMs = 0
    var outputName: String?
    var captureMs = 0
    var lightsMs = 0
    var noAudio = false
}

/// Reads `source` on its own thread, analyses it and sends a colour to the strips about 30 times
/// a second. A port of `MusicEngine.kt`, without the microphone measurement of the output delay:
/// Core Audio reports it on the Mac.
final class MusicEngine: @unchecked Sendable {
    private let source: AudioSource
    private let controller: LedController
    private let targets: [UUID]
    private let baseColor: Rgb?
    private let onLive: @Sendable (LiveMusic) -> Void
    private let onFailure: @Sendable (String) -> Void

    private let lock = NSLock()
    private var settingsValue: MusicSettings
    private var commands: [BeatCommand] = []
    private var running = false
    private var thread: Thread?
    private let done = DispatchSemaphore(value: 0)
    private let closeLock = NSLock()
    private var sourceClosed = false

    // Engine thread only.
    private var outputDelay = 0.0
    private var outputName: String?
    private var captureLag = -1.0

    private static let sendIntervalNanos: UInt64 = 30_000_000
    // Slightly faster than the link's 35 ms, so the link never waits for a new colour.
    private static let holdBeforeBeatS = 0.035
    private static let captureSmoothing = 0.02
    private static let liveIntervalNanos: UInt64 = 33_000_000
    private static let logEveryNanos: UInt64 = 1_000_000_000
    private static let outputCheckNanos: UInt64 = 2_000_000_000
    private static let silenceHintMs = 6_000

    init(
        source: AudioSource,
        controller: LedController,
        targets: [UUID],
        baseColor: Rgb?,
        settings: MusicSettings,
        onLive: @escaping @Sendable (LiveMusic) -> Void,
        onFailure: @escaping @Sendable (String) -> Void
    ) {
        self.source = source
        self.controller = controller
        self.targets = targets
        self.baseColor = baseColor
        settingsValue = settings
        self.onLive = onLive
        self.onFailure = onFailure
    }

    var settings: MusicSettings {
        get { lock.withLock { settingsValue } }
        set { lock.withLock { settingsValue = newValue } }
    }

    func send(_ command: BeatCommand) { lock.withLock { commands.append(command) } }

    func start() {
        lock.withLock { running = true }
        let thread = Thread { [self] in
            run()
            done.signal()
        }
        thread.name = "fff3-music"
        thread.qualityOfService = .userInteractive
        self.thread = thread
        thread.start()
    }

    /// Stops and waits for the thread, so no colour is sent after this returns.
    func stop() {
        guard lock.withLock({ let was = running; running = false; return was }) || thread != nil else { return }
        closeSource()
        if thread != nil { _ = done.wait(timeout: .now() + 1) }
        thread = nil
    }

    private var isRunning: Bool { lock.withLock { running } }

    private func run() {
        do {
            try source.open()
        } catch {
            closeSource()
            fail(error.localizedDescription)
            return
        }
        loop()
        closeSource()
    }

    /// Both `stop` and the engine thread close the source; Core Audio objects must be freed once.
    private func closeSource() {
        closeLock.withLock {
            if sourceClosed { return }
            sourceClosed = true
            source.close()
        }
    }

    private func loop() {
        let analyzer = MusicAnalyzer(sampleRate: analysisRate, bandCount: 24)
        let tracker = BeatTracker(frameRate: analyzer.frameRate, onsetDelay: Double(analyzer.fftSize) / 2.0 / Double(analysisRate))
        let renderer: LightRenderer
        if let baseColor, baseColor.hsv.saturation > 0.2 {
            renderer = LightRenderer(baseHue: baseColor.hsv.hue, baseSaturation: baseColor.hsv.saturation)
        } else {
            renderer = LightRenderer()
        }
        let silence = SilenceDetector(holdMillis: Self.silenceHintMs)
        var hop = [Float](repeating: 0, count: analyzer.hopSize)
        let dt = Float(analyzer.hopSize) / Float(analysisRate)
        var lastSent: Rgb?
        var lastSentAt: UInt64 = 0
        var liveAt: UInt64 = 0
        var loggedAt: UInt64 = 0
        var loggedLocks = 0
        var outputCheckedAt: UInt64 = 0
        var noAudio = false
        var hopLoud = false
        // Android measures each strip with the camera. The Mac has no calibration, so it uses the
        // write-to-light time measured once with the Mac camera on the ELK-BLEDOM strips.
        let cameraDelays = [Int?](repeating: LightTiming.macStripDelayMs, count: max(targets.count, 1))

        while isRunning {
            if !source.read(&hop) {
                if isRunning { fail("The sound stopped.") }
                return
            }
            if !isRunning { return }
            let now = nowNanos()

            if source.capturesPlayback {
                var peak: Float = 0
                for sample in hop { peak = max(peak, abs(sample)) }
                noAudio = silence.update(peak: peak, nowMillis: Int(now / 1_000_000))
                // The output can change while music runs, for example to headphones.
                if now - outputCheckedAt >= Self.outputCheckNanos {
                    outputCheckedAt = now
                    outputDelay = OutputDevice.latency()
                    outputName = OutputDevice.name()
                }
            }

            let settings = self.settings
            analyzer.sensitivity = settings.sensitivity
            let frame = analyzer.process(hop)
            tracker.process(frame.beatOnset, rms: frame.rms, bass: frame.rawBass, mid: frame.rawMid, treble: frame.rawTreble)

            let lag = Double(Int64(nowNanos()) - Int64(source.lastSampleNanos)) / 1e9
            captureLag = captureLag < 0 ? lag : captureLag + (lag - captureLag) * Self.captureSmoothing
            let link = LightTiming.linkSeconds(cameraMs: cameraDelays, queueWaitMs: controller.queueWait.medianMillis, writeMs: controller.linkLatency.medianMillis)
            handleCommands(tracker)

            let lead = LightTiming.lead(link: link, capture: captureLag, output: outputDelay, lightDelay: Double(settings.lightDelayMs) / 1000.0)
            let color = renderer.render(frame, style: settings.style, dt: dt, clock: tracker, lead: lead, beatColors: settings.beatColors)

            if now - liveAt >= Self.liveIntervalNanos {
                liveAt = now
                onLive(LiveMusic(
                    bands: frame.bands, color: renderer.fullColor, level: frame.level,
                    beatPhase: renderer.beatPhase, beatInBar: renderer.beatInBar,
                    locked: tracker.state == .Locked, displayBpm: tracker.displayBpm, energy: renderer.songEnergy,
                    outputMs: JMath.roundToInt(outputDelay * 1000), outputName: outputName, captureMs: JMath.roundToInt(captureLag * 1000),
                    lightsMs: JMath.roundToInt(link * 1000), noAudio: noAudio
                ))
            }

            if now - loggedAt >= Self.logEveryNanos, ProcessInfo.processInfo.environment["FFF3_BEAT_LOG"] != nil {
                loggedAt = now
                let relocked = tracker.lockCount != loggedLocks
                loggedLocks = tracker.lockCount
                logBeat(tracker, relocked ? tracker.lastReason : nil, link, renderer.songEnergy, frame.rms)
            }

            // The link sends one colour per slot: keep it free just before a beat, so the beat goes out at once.
            let holdForBeat = settings.style == .BeatLock && renderer.untilBeat < Self.holdBeforeBeatS
            let due = renderer.beatEdge || (!holdForBeat && now - lastSentAt >= Self.sendIntervalNanos)
            if TimingLog.enabled {
                let peak = hop.reduce(Float(0)) { max($0, abs($1)) }
                if peak > 0.003 && !hopLoud {
                    TimingLog.write("analysed sample=\(TimingLog.ms(source.lastSampleNanos)) now=\(TimingLog.ms(now)) lead=\(String(format: "%.0f", lead * 1000))")
                }
                hopLoud = peak > 0.003 ? true : (peak < 0.001 ? false : hopLoud)
                if renderer.beatEdge || (settings.style != .BeatLock && frame.beat) {
                    TimingLog.write("beat now=\(TimingLog.ms(now)) sent=\(color != lastSent && due) value=\(max(color.red, color.green, color.blue))")
                }
            }
            if color != lastSent && due {
                controller.send(targets, [.color(color)])
                lastSent = color
                lastSentAt = now
            }
        }
    }

    /// One line a second on standard error with FFF3_BEAT_LOG=1, like `adb logcat -s fff3-beat`.
    private func logBeat(_ tracker: BeatTracker, _ relock: RelockReason?, _ link: Double, _ energy: Float, _ rms: Float) {
        let line = String(
            format: "fff3-beat t=%.0f est=%.1f conf=%.2f short=%.1f locked=%.2f shown=%d state=%@ coast=%@ novelty=%.1f challenge=%.1f queue=%d write=%d link=%.0f output=%.0f capture=%.0f energy=%.2f rms=%.5f%@\n",
            tracker.time, Double(tracker.heardBpm), Double(tracker.heardConfidence), Double(tracker.shortBpm), Double(tracker.bpm),
            tracker.displayBpm, tracker.state.rawValue, tracker.coasting ? "true" : "false", Double(tracker.novelty),
            Double(tracker.challengeSeconds), controller.queueWait.medianMillis, controller.linkLatency.medianMillis,
            link * 1000, outputDelay * 1000, captureLag * 1000, Double(energy), Double(rms), relock.map { " relock=\($0.rawValue)" } ?? ""
        )
        FileHandle.standardError.write(line.data(using: .utf8)!)
    }

    private func handleCommands(_ tracker: BeatTracker) {
        let pending = lock.withLock { () -> [BeatCommand] in
            defer { commands.removeAll() }
            return commands
        }
        for command in pending {
            switch command {
            case .tap(let nanos):
                // The user taps on what they hear, which is the captured sound played after the output delay.
                tracker.tap(tracker.time + (Double(nanos) - Double(nowNanos())) / 1e9 + captureLag - outputDelay)
            case .scaleTempo(let factor):
                tracker.scaleTempo(factor)
            }
        }
    }

    private func fail(_ message: String) {
        guard lock.withLock({ let was = running; running = false; return was }) else { return }
        onFailure(message)
    }
}
