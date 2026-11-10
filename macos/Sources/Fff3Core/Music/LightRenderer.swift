import Foundation

/// How the lights follow the music.
public enum MusicStyle: String, CaseIterable, Codable, Sendable {
    /// Each beat flashes a new colour, then fades.
    case Pulse
    /// Colour follows the pitch of the sound: bass is red, bright sound is blue and violet.
    case Spectrum
    /// One colour, brightness follows loudness.
    case Energy
    /// Slow colour drift that breathes with the music.
    case Calm
    /// Follows its own beat clock, so the lights stay on the beat through quiet parts.
    case BeatLock
}

/// Where Beat lock takes its colour from.
public enum BeatColors: String, CaseIterable, Codable, Sendable {
    /// A new colour each bar of 4 beats.
    case Bars
    /// One colour per tempo, so a song keeps its colour until the tempo changes.
    case Tempo
    /// The strip's colour from before music started.
    case Strip
}

/// Turns `MusicFrame`s into one colour for the strips, with smoothing so it never flickers.
/// Brightness is baked into the colour, so each tick is one coalesced colour frame on the link.
public final class LightRenderer {
    /// Hue of the strip before music started, in degrees. Energy keeps it.
    private let baseHue: Float
    private let baseSaturation: Float
    private var hue: Float
    private var targetHue: Float
    private var value: Float = 0
    private var flash: Float = 0
    private var slowLevel: Float = 0
    private var lockMix: Float = 0
    private var seenEpoch = -1
    private var lastBeat = Int.min
    private var bar = 0
    private var accent: Float = 0
    private var beatHue: Float
    private var tempoHueValue = Float.nan
    private let energy = SongEnergy()

    /// 0...1, how intense this part of the song is compared with the last 2 minutes.
    public var songEnergy: Float { energy.value }

    /// The colour of the last `render`, for the visualiser.
    public private(set) var color = Rgb.black
    /// The colour of the last `render` at full brightness.
    public private(set) var fullColor = Rgb.black
    /// 0...1, the beat flash of the last `render`, for the visualiser.
    public var beatFlash: Float { flash }
    /// True when the last `render` started a beat of the beat lock clock.
    public private(set) var beatEdge = false
    /// Beat lock phase of the last `render`, 0...1 from the beat, or -1 when not locked.
    public private(set) var beatPhase: Float = -1
    /// 0 to 3: the beat of the bar `beatPhase` counts from.
    public private(set) var beatInBar = 0
    /// Seconds from the last `render` to the next beat it will show, or infinity when not locked.
    public private(set) var untilBeat = Double.infinity

    public init(baseHue: Float = 260, baseSaturation: Float = 1) {
        self.baseHue = baseHue
        self.baseSaturation = baseSaturation
        hue = baseHue
        targetHue = baseHue
        beatHue = baseHue
    }

    /// `clock` and `lead` are only used by Beat lock: the colour shows the clock
    /// `lead` seconds after the newest audio, so it is visible when its beat is heard.
    @discardableResult
    public func render(
        _ frame: MusicFrame,
        style: MusicStyle,
        dt: Float,
        clock: BeatTracker? = nil,
        lead: Double = 0.0,
        beatColors: BeatColors = .Bars
    ) -> Rgb {
        beatEdge = false
        beatPhase = -1
        untilBeat = .infinity
        energy.update(rms: frame.rms, onset: frame.onset, dt: dt)
        let beatPeriod: Float = frame.bpm > 0 ? 60 / frame.bpm : 0.5
        if frame.beat { flash = 1 } else { flash *= decay(dt, beatPeriod * 0.3) }

        let v: Float
        let s: Float
        switch style {
        case .Pulse:
            if frame.beat { targetHue = hue + C.pulseHueStep }
            hue = approachAngle(hue, targetHue, dt, 0.05)
            value = 0.12 + 0.88 * flash + 0.15 * frame.level * (1 - flash)
            v = value
            s = 1 - 0.15 * flash
        case .Spectrum:
            targetHue = frame.brightness * 280
            hue = approachAngle(hue, targetHue, dt, 0.25)
            value = follow(value, frame.level, dt, attack: 0.03, release: 0.25)
            v = 0.06 + 0.8 * value + 0.2 * flash
            s = 1
        case .Energy:
            hue = baseHue
            value = follow(value, frame.level, dt, attack: 0.02, release: 0.18)
            v = 0.04 + 0.86 * value + 0.2 * flash
            s = baseSaturation
        case .Calm:
            slowLevel = follow(slowLevel, frame.level, dt, attack: 0.8, release: 2.5)
            if frame.beat { targetHue += C.calmBeatNudge }
            targetHue += (C.calmDrift + C.calmEnergyDrift * slowLevel) * dt
            hue = approachAngle(hue, targetHue, dt, 0.6)
            v = 0.3 + 0.45 * slowLevel
            s = 0.85
        case .BeatLock:
            let locked = clock != nil && clock!.state == .Locked
            lockMix += ((locked ? 1 : 0) - lockMix) * (1 - decay(dt, C.lockFade))
            var beatValue: Float = 0
            var beatSaturation: Float = 1
            flash = 0
            if let clock, locked {
                let beats = clock.beatsAt(clock.time + lead)
                let whole = JMath.toLong(beats.rounded(.down))
                // A new clock starts mid-beat: show that beat as if it had fired, without a flash.
                if clock.epoch != seenEpoch {
                    seenEpoch = clock.epoch
                    lastBeat = whole
                    startBeat(whole)
                } else if whole > lastBeat {
                    lastBeat = whole
                    beatEdge = true
                    startBeat(whole)
                }
                // A phase correction can step back over a beat already shown: hold it, never flash twice.
                let phase = whole < lastBeat ? 0.0 : beats - Double(whole)
                beatPhase = Float(phase)
                untilBeat = (Double(lastBeat + 1) - beats) * clock.period
                // Calm parts glow longer and dimmer; a chorus or a drop gets short, full flashes.
                let intensity = energy.value
                let decayBeats = C.calmDecayBeats + (C.decayBeats - C.calmDecayBeats) * Double(intensity)
                let pulse = Float(Foundation.exp(-phase / decayBeats))
                beatValue = (C.beatFloor + (accent - C.beatFloor) * pulse) * (C.calmPeak + (1 - C.calmPeak) * intensity)
                if beatInBar == 0 { beatSaturation = 1 - C.downbeatWhite * intensity * pulse }
                flash = accent * pulse * lockMix
            } else {
                beatHue += C.fallbackDrift * dt
            }
            switch beatColors {
            case .Bars: hue = beatHue
            case .Strip: hue = baseHue
            case .Tempo:
                // While listening, keep the last song's colour rather than drift.
                if let clock, locked {
                    let target = tempoHue(clock.bpm)
                    tempoHueValue = tempoHueValue.isNaN ? target : approachAngle(tempoHueValue, target, dt, C.tempoHueFade)
                }
                hue = tempoHueValue.isNaN ? baseHue : wrap(tempoHueValue)
            }
            targetHue = hue
            value = follow(value, frame.level, dt, attack: 0.05, release: 0.4)
            let fallback = C.fallbackFloor + C.fallbackRange * value
            v = fallback + (beatValue - fallback) * lockMix
            s = beatColors == .Strip
                ? baseSaturation * (1 + (beatSaturation - 1) * lockMix)
                : 0.9 + (beatSaturation - 0.9) * lockMix
        }
        // LEDs are linear while eyes are not: a power curve makes the fades look even.
        let out = max(JMath.pow(v.clamped(0, 1), C.gamma), C.minOutput)
        hue = wrap(hue)
        targetHue = hue + shortest(hue, targetHue)
        color = Rgb.fromHsv(hue, saturation: s, value: out)
        fullColor = Rgb.fromHsv(hue, saturation: s, value: 1)
        return color
    }

    /// Each beat steps the hue a little; each bar moves to the next palette colour, with a stronger flash.
    private func startBeat(_ beat: Int) {
        beatInBar = JMath.floorMod(beat, 4)
        if beatInBar == 0 { bar += 1 }
        beatHue = C.palette[bar % C.palette.count] + Float(beatInBar) * C.beatHueStep
        accent = beatInBar == 0 ? 1 : C.offbeatAccent
    }

    /// One full turn of the colour wheel per doubling of tempo, so 64 and 128 BPM share a colour:
    /// the beat clock may lock on either for the same song.
    private func tempoHue(_ bpm: Float) -> Float {
        var folded = max(bpm, 1)
        while folded < C.tempoHueBase { folded *= 2 }
        while folded >= C.tempoHueBase * 2 { folded /= 2 }
        return (JMath.ln(folded / C.tempoHueBase) / JMath.ln(2)) * 360
    }

    private func follow(_ current: Float, _ target: Float, _ dt: Float, attack: Float, release: Float) -> Float {
        let tau = target > current ? attack : release
        return target + (current - target) * decay(dt, tau)
    }

    private func approachAngle(_ current: Float, _ target: Float, _ dt: Float, _ tau: Float) -> Float {
        current + shortest(current, target) * (1 - decay(dt, tau))
    }

    private func decay(_ dt: Float, _ tau: Float) -> Float { JMath.exp(-dt / max(tau, 1e-3)) }

    private func shortest(_ from: Float, _ to: Float) -> Float {
        let d = wrap(to - from)
        return d > 180 ? d - 360 : d
    }

    private func wrap(_ angle: Float) -> Float {
        (angle.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
    }

    private enum C {
        static let pulseHueStep: Float = 47
        static let calmDrift: Float = 4
        static let calmEnergyDrift: Float = 18
        static let calmBeatNudge: Float = 6
        static let palette: [Float] = [285, 330, 15, 200, 165, 245]
        static let beatHueStep: Float = 18
        static let offbeatAccent: Float = 0.62
        static let decayBeats = 0.3
        static let beatFloor: Float = 0.12
        static let calmPeak: Float = 0.4
        static let calmDecayBeats = 0.55
        static let downbeatWhite: Float = 0.3
        static let lockFade: Float = 0.4
        static let fallbackDrift: Float = 5
        static let fallbackFloor: Float = 0.15
        static let fallbackRange: Float = 0.45
        static let tempoHueBase: Float = 80
        static let tempoHueFade: Float = 1
        static let gamma: Float = 1.8
        static let minOutput: Float = 0.01
    }
}
