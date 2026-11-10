import SwiftUI
import Fff3Core

/// Music sync: source, style, the live picture, and Beat lock's tempo and delay controls.
struct MusicPage: View {
    @Bindable var model: AppModel
    let theme: Theme
    let accent: Color
    @State private var lightDelay = 50.0
    @State private var sensitivity = 0.5

    private var settings: Settings { model.settings }
    private var live: LiveMusic { model.live }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) {
                    QuietButton(
                        title: model.musicRunning ? "Stop" : "Start",
                        systemImage: model.musicRunning ? "stop.fill" : "play.fill",
                        theme: theme, accent: accent, prominent: true, onAccent: theme.onAccent(model.accent)
                    ) {
                        if model.musicRunning { model.stopMusic() } else { Task { await model.startMusic() } }
                    }
                    PillSwitch(
                        options: [(MusicSource.systemAudio, "This Mac"), (.microphone, "Microphone")],
                        selection: Binding(get: { settings.musicSource }, set: { value in model.updateSettings { $0.musicSource = value } }),
                        accent: accent, theme: theme
                    )
                    .disabled(model.musicRunning)
                    .opacity(model.musicRunning ? 0.5 : 1)
                    Spacer()
                    Text(statusLine).font(.system(size: 12)).foregroundStyle(theme.dim).lineLimit(1)
                }
                if let error = model.musicError {
                    HStack(spacing: 7) {
                        StatusDot(color: theme.danger)
                        Text(error).font(.system(size: 12)).foregroundStyle(theme.text)
                    }
                } else if live.noAudio {
                    HStack(spacing: 7) {
                        StatusDot(color: theme.warn)
                        Text("No sound captured. Play some music on this Mac, or try the microphone.").font(.system(size: 12))
                    }
                }

                Visualiser(live: live, running: model.musicRunning, showsBeats: settings.musicStyle == .BeatLock, theme: theme)
                    .frame(height: 96)

                block("Style", body: styleBody) {
                    PillSwitch(
                        options: MusicStyle.allCases.map { ($0, styleName($0)) },
                        selection: Binding(get: { settings.musicStyle }, set: { value in model.updateSettings { $0.musicStyle = value } }),
                        accent: accent, theme: theme
                    )
                }

                if settings.musicStyle == .BeatLock { beatLock }

                block("Sensitivity", body: nil) {
                    HStack(spacing: 10) {
                        Text("Loud music").font(.system(size: 11)).foregroundStyle(theme.dim)
                        LineSlider(value: $sensitivity, range: 0...1, accent: accent, theme: theme) {
                            model.updateSettings { $0.musicSensitivity = Float(sensitivity) }
                        }
                        Text("Quiet room").font(.system(size: 11)).foregroundStyle(theme.dim)
                    }
                    .frame(maxWidth: 420)
                }
                Text("Sound is analysed on this Mac. It is never recorded or sent anywhere.")
                    .font(.system(size: 11)).foregroundStyle(theme.dim)
            }
            .padding(20)
        }
        .scrollContentBackground(.hidden)
        .onAppear {
            lightDelay = Double(settings.musicLightDelayMs)
            sensitivity = Double(settings.musicSensitivity)
        }
    }

    private var beatLock: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(tempoText).font(.system(size: 20, weight: .semibold).monospacedDigit())
                    Text(model.musicRunning ? (live.locked ? "Locked on the beat" : "Finding the beat") : "Start music sync to find the beat.")
                        .font(.system(size: 11)).foregroundStyle(theme.dim)
                }
                .frame(minWidth: 150, alignment: .leading)
                TapButton(theme: theme, accent: accent, enabled: model.musicRunning) { model.beat(.tap($0)) }
                QuietButton(title: "×2", theme: theme) { model.beat(.scaleTempo(2)) }
                    .help("Double the tempo")
                QuietButton(title: "÷2", theme: theme) { model.beat(.scaleTempo(0.5)) }
                    .help("Halve the tempo")
            }
            Text("Tap 4 times or more on the beat to set the tempo. If the lights go twice too fast or too slow, use ×2 or ÷2.")
                .font(.system(size: 11)).foregroundStyle(theme.dim).fixedSize(horizontal: false, vertical: true)

            block("Colours", body: colourBody) {
                PillSwitch(
                    options: [(BeatColors.Bars, "Each bar"), (.Tempo, "Tempo"), (.Strip, "Strip")],
                    selection: Binding(get: { settings.beatColors }, set: { value in model.updateSettings { $0.beatColors = value } }),
                    accent: accent, theme: theme
                )
            }

            block("Light delay", value: "\(lightDelay > 0 ? "+" : "")\(Int(lightDelay)) ms",
                  body: "Fine-tune if the lights come before or after the beat. Higher makes them later.") {
                LineSlider(value: $lightDelay, range: -100...200, step: 5, accent: accent, theme: theme) {
                    model.updateSettings { $0.musicLightDelayMs = Int(lightDelay) }
                }
                .frame(maxWidth: 420)
                .onChange(of: lightDelay) { _, new in model.updateSettings { $0.musicLightDelayMs = Int(new) } }
            }
            if model.musicRunning {
                Text(delayLine).font(.system(size: 11).monospacedDigit()).foregroundStyle(theme.dim)
            }
        }
    }

    private var tempoText: String {
        if !model.musicRunning { return "– BPM" }
        return live.locked ? "\(live.displayBpm) BPM" : "Listening…"
    }

    private var delayLine: String {
        if settings.musicSource == .microphone && ProcessInfo.processInfo.environment["FFF3_AUDIO_FILE"] == nil {
            return "Delay: microphone \(live.captureMs) ms, lights \(live.lightsMs) ms"
        }
        return "Delay: \(OutputDevice.name() ?? "sound output") \(live.outputMs) ms, capture \(live.captureMs) ms, lights \(live.lightsMs) ms"
    }

    private var statusLine: String {
        guard model.musicRunning else { return model.targetDevices.isEmpty ? "Add a strip first." : "Play some music, then start." }
        let names = model.targetDevices.count == 1 ? model.targetDevices[0].displayName : "\(model.targetDevices.count) strips"
        return "Playing on \(names)"
    }

    private var styleBody: String {
        switch settings.musicStyle {
        case .Pulse: "Each beat flashes a new colour, then fades."
        case .Spectrum: "Colour follows the sound: bass is red, high notes are blue and violet."
        case .Energy: "Keeps your strip's colour. Brightness follows how loud the music is."
        case .Calm: "A slow colour drift that breathes with the music. Good for background music."
        case .BeatLock: "Finds the tempo, then keeps the lights on the beat, even in quiet parts and breaks."
        }
    }

    private var colourBody: String {
        switch settings.beatColors {
        case .Bars: "A new colour every 4 beats."
        case .Tempo: "One colour per tempo. The colour stays until the song changes, and each tempo has its own colour."
        case .Strip: "Keeps the strip's colour from before music started. Only the brightness follows the beat."
        }
    }

    private func styleName(_ style: MusicStyle) -> String {
        style == .BeatLock ? "Beat lock" : style.rawValue
    }

    private func block<Content: View>(_ title: String, value: String? = nil, body: String?, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(title).font(.system(size: 11, weight: .semibold)).foregroundStyle(theme.dim)
                if let value { Text(value).font(.system(size: 11).monospacedDigit()).foregroundStyle(theme.text) }
            }
            content()
            if let body {
                Text(body).font(.system(size: 11)).foregroundStyle(theme.dim).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// Reacts on press, not on release, so the tap lands on the beat.
private struct TapButton: View {
    let theme: Theme
    let accent: Color
    let enabled: Bool
    let onTap: (UInt64) -> Void
    @State private var pressed = false

    var body: some View {
        Text("Tap")
            .font(.system(size: 12, weight: .semibold))
            .frame(width: 72, height: 28)
            .background(RoundedRectangle(cornerRadius: 7).fill(pressed ? accent.opacity(0.4) : accent.opacity(0.16)))
            .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(accent.opacity(0.5), lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 7))
            .opacity(enabled ? 1 : 0.45)
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard enabled, !pressed else { return }
                    pressed = true
                    onTap(nowNanos())
                }
                .onEnded { _ in pressed = false })
            .help("Tap on the beat")
    }
}

/// Spectrum bars in the live colour, with the 4 beats of the bar under them while Beat lock runs.
private struct Visualiser: View {
    let live: LiveMusic
    let running: Bool
    let showsBeats: Bool
    let theme: Theme

    var body: some View {
        let color = running ? theme.accent(live.color) : theme.rule
        VStack(spacing: 10) {
            HStack(alignment: .bottom, spacing: 4) {
                ForEach(0..<live.bands.count, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 2)
                        .fill(color.opacity(0.35 + 0.65 * Double(live.bands[i])))
                        .frame(height: max(3, 70 * CGFloat(live.bands[i])))
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 70, alignment: .bottom)
            if showsBeats { HStack(spacing: 8) {
                ForEach(0..<4, id: \.self) { beat in
                    let lit = live.beatPhase >= 0 && live.beatInBar == beat
                    let glow = lit ? Double(max(0, 1 - live.beatPhase)) : 0
                    Circle()
                        .fill(lit ? color.opacity(0.3 + 0.7 * glow) : theme.rule)
                        .frame(width: beat == 0 ? 9 : 7, height: beat == 0 ? 9 : 7)
                }
                Spacer()
            } }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(theme.surface.opacity(0.55)))
        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(theme.rule, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
