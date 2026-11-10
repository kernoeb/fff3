import AVFoundation
import Foundation
import Observation
import Fff3Core

/// Which strips the controls act on: all of them, or one.
enum Target: Hashable {
    case all
    case device(UUID)
}

enum Page: String, CaseIterable {
    case light = "Light"
    case music = "Music"
}

/// The app's state on the main actor. Bluetooth and music run elsewhere and report back here.
@MainActor
@Observable
final class AppModel {
    private(set) var data = Store.load()
    private(set) var connection: [UUID: ConnectionState] = [:]
    private(set) var bluetooth = BluetoothStatus.unknown
    private(set) var scanned: [ScannedStrip] = []
    var scanning = false { didSet { scanning ? controller.startScan() : controller.stopScan() } }
    var target = Target.all
    var page = Page.light

    private(set) var musicRunning = false
    private(set) var musicError: String?
    private(set) var live = LiveMusic()
    @ObservationIgnored private var engine: MusicEngine?
    @ObservationIgnored private var musicTargets: [UUID] = []
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    @ObservationIgnored private(set) var controller: LedController!

    init() {
        controller = LedController(
            onStates: { states in Task { @MainActor [weak self] in self?.connection = states } },
            onScan: { found in Task { @MainActor [weak self] in self?.scanned = found } },
            onStatus: { status in Task { @MainActor [weak self] in self?.bluetooth = status } }
        )
        controller.setDevices(data.devices)
        let environment = ProcessInfo.processInfo.environment
        if let page = environment["FFF3_PAGE"].flatMap({ Page(rawValue: $0.capitalized) }) { self.page = page }
        // Test switches: a style for this run only, and music started once every strip is connected.
        if let style = environment["FFF3_STYLE"].flatMap(MusicStyle.init(rawValue:)) { data.settings.musicStyle = style }
        if environment["FFF3_AUTOSTART"] != nil {
            Task { [weak self] in
                for _ in 0..<60 {
                    try? await Task.sleep(for: .milliseconds(500))
                    guard let self else { return }
                    if !self.devices.isEmpty, self.devices.allSatisfy({ self.connection[$0.id] == .connected }) {
                        await self.startMusic()
                        return
                    }
                }
            }
        }
    }

    var devices: [KnownDevice] { data.devices }
    var settings: Settings { data.settings }

    var targetIds: [UUID] {
        switch target {
        case .all: data.devices.map(\.id)
        case .device(let id): data.devices.contains { $0.id == id } ? [id] : []
        }
    }

    var targetDevices: [KnownDevice] { data.devices.filter { targetIds.contains($0.id) } }

    /// The families of the target strips; effects show only when they all share one.
    var targetFamily: LedFamily? {
        let families = Set(targetDevices.map(\.family))
        return families.count == 1 ? families.first : nil
    }

    /// What all target strips agree on; a field is nil when one strip does not know it or two differ.
    var targetState: LedState {
        let states = targetIds.map { data.states[$0.uuidString] ?? LedState() }
        func agreed<T: Hashable>(_ field: (LedState) -> T?) -> T? {
            let values = Set(states.map { field($0) })
            return values.count == 1 ? values.first ?? nil : nil
        }
        return LedState(
            power: agreed(\.power), color: agreed(\.color), brightness: agreed(\.brightness),
            effectId: agreed(\.effectId), sceneId: agreed(\.sceneId), speed: agreed(\.speed)
        )
    }

    /// The colour the window takes its accent from: the target's last colour, or fff3 violet.
    var accent: Rgb {
        if musicRunning { return live.color }
        return targetState.color ?? data.devices.compactMap { data.states[$0.id.uuidString]?.color }.first ?? Rgb(0x8A7CFF)
    }


    func send(_ commands: [Command]) {
        let ids = targetIds
        controller.send(ids, commands)
        for id in ids {
            guard let family = data.devices.first(where: { $0.id == id })?.family else { continue }
            var state = data.states[id.uuidString] ?? LedState()
            for command in commands where family.supports(command) { state = state.after(command) }
            data.states[id.uuidString] = state
        }
        scheduleSave()
    }

    func add(_ strip: ScannedStrip) {
        guard !data.devices.contains(where: { $0.id == strip.id }) else { return }
        data.devices.append(KnownDevice(id: strip.id, advertisedName: strip.name, family: strip.family))
        controller.setDevices(data.devices)
        save()
    }

    func rename(_ id: UUID, to label: String) {
        guard let index = data.devices.firstIndex(where: { $0.id == id }) else { return }
        data.devices[index].label = label
        save()
    }

    func forget(_ id: UUID) {
        data.devices.removeAll { $0.id == id }
        data.states[id.uuidString] = nil
        if target == .device(id) { target = .all }
        controller.setDevices(data.devices)
        save()
    }

    func updateSettings(_ change: (inout Settings) -> Void) {
        change(&data.settings)
        engine?.settings = musicSettings
        scheduleSave()
    }

    func retry() { controller.retryNow() }


    private var musicSettings: MusicSettings {
        MusicSettings(
            style: data.settings.musicStyle, sensitivity: data.settings.musicSensitivity,
            lightDelayMs: data.settings.musicLightDelayMs, beatColors: data.settings.beatColors
        )
    }

    func startMusic() async {
        guard !musicRunning, !targetIds.isEmpty else { return }
        musicError = nil
        let source: AudioSource
        if let file = ProcessInfo.processInfo.environment["FFF3_AUDIO_FILE"] {
            source = FileSource(path: file)
        } else if data.settings.musicSource == .microphone {
            guard await AVCaptureDevice.requestAccess(for: .audio) else {
                musicError = "fff3 cannot use the microphone. Allow it in System Settings, Privacy and Security, Microphone."
                return
            }
            // A second click during the permission request must not start a second engine.
            guard !musicRunning, !targetIds.isEmpty else { return }
            source = MicrophoneSource()
        } else {
            source = SystemAudioSource()
        }
        musicTargets = targetIds
        // Music light is not recorded, so the saved state stays the user's; stopping restores it.
        controller.send(musicTargets, [.power(true)])
        let engine = MusicEngine(
            source: source, controller: controller, targets: musicTargets, baseColor: targetState.color,
            settings: musicSettings,
            onLive: { live in Task { @MainActor [weak self] in if self?.musicRunning == true { self?.live = live } } },
            onFailure: { message in Task { @MainActor [weak self] in self?.musicFailed(message) } }
        )
        self.engine = engine
        musicRunning = true
        engine.start()
    }

    func stopMusic() {
        guard let engine else { return }
        self.engine = nil
        engine.stop()
        musicRunning = false
        live = LiveMusic()
        restoreAfterMusic()
    }

    func beat(_ command: BeatCommand) { engine?.send(command) }

    private func musicFailed(_ message: String) {
        guard engine != nil else { return }
        stopMusic()
        musicError = message
    }

    /// Puts each strip back as it was before music started, like `MusicRestore.kt`.
    private func restoreAfterMusic() {
        for id in musicTargets where data.devices.contains(where: { $0.id == id }) {
            let state = data.states[id.uuidString]
            var commands: [Command] = []
            if let effect = state?.effectId {
                commands.append(.effect(id: effect, speed: state?.speed))
            } else if let scene = state?.sceneId {
                commands.append(.scene(scene))
            } else if let color = state?.color {
                commands.append(.color(color))
            }
            switch state?.power {
            case false: commands.append(.power(false))
            // Music turned the strip on, so an unknown power is now known to be on.
            case nil: commands.append(.power(true)); data.states[id.uuidString, default: LedState()].power = true
            case true: break
            }
            controller.send([id], commands)
        }
        musicTargets = []
        scheduleSave()
    }

    func shutdown() {
        stopMusic()
        save()
        controller.closeAll()
    }


    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.save()
        }
    }

    private func save() { Store.save(data) }
}
