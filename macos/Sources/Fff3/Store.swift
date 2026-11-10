import Foundation
import Fff3Core

/// A strip you have added. macOS hides Bluetooth addresses, so `id` is the identifier
/// CoreBluetooth gives the strip on this Mac.
struct KnownDevice: Codable, Hashable, Identifiable, Sendable {
    let id: UUID
    let advertisedName: String
    let family: LedFamily
    var label: String?

    var displayName: String {
        if let label, !label.trimmingCharacters(in: .whitespaces).isEmpty { return label }
        return advertisedName.trimmingCharacters(in: .whitespaces)
    }
}

/// What fff3 last sent to a strip. The strips cannot report their state; nil is unknown.
struct LedState: Codable, Hashable, Sendable {
    var power: Bool?
    var color: Rgb?
    var brightness: Int?
    var effectId: Int?
    var sceneId: Int?
    var speed: Int?

    func after(_ command: Command) -> LedState {
        var next = self
        switch command {
        case .power(let on): next.power = on
        case .color(let rgb): next.color = rgb; next.effectId = nil; next.sceneId = nil
        case .brightness(let percent): next.brightness = percent.clamped(0, 100)
        case .effect(let id, let speed):
            next.effectId = id
            next.sceneId = nil
            if let speed { next.speed = speed.clamped(0, 100) }
        case .speed(let percent): next.speed = percent.clamped(0, 100)
        case .scene(let id): next.sceneId = id; next.effectId = nil
        default: break
        }
        return next
    }
}

enum MusicSource: String, Codable, CaseIterable, Sendable {
    /// What this Mac plays, through a Core Audio process tap.
    case systemAudio
    /// The room, through the microphone.
    case microphone
}

struct Settings: Codable, Hashable, Sendable {
    var musicSource = MusicSource.systemAudio
    /// Beat lock fine-tune in milliseconds: positive makes the lights later.
    var musicLightDelayMs = 0
    var beatColors = BeatColors.Bars
    var musicStyle = MusicStyle.Pulse
    /// 0...1: higher reacts to quieter sound and softer beats.
    var musicSensitivity: Float = 0.5
}

/// Everything fff3 keeps, in one JSON file in Application Support.
struct AppData: Codable, Sendable {
    var devices: [KnownDevice] = []
    /// By `KnownDevice.id`, as a string so the file stays readable.
    var states: [String: LedState] = [:]
    var settings = Settings()

    init() {}

    // Each part is read on its own, so one unreadable part never loses the saved strips.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        devices = (try? container.decodeIfPresent([KnownDevice].self, forKey: .devices)) ?? []
        states = (try? container.decodeIfPresent([String: LedState].self, forKey: .states)) ?? [:]
        settings = (try? container.decodeIfPresent(Settings.self, forKey: .settings)) ?? Settings()
    }
}

enum Store {
    static var url: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appending(path: "fff3/data.json")
    }

    static func load() -> AppData {
        guard let data = try? Data(contentsOf: url) else { return AppData() }
        return (try? JSONDecoder().decode(AppData.self, from: data)) ?? AppData()
    }

    static func save(_ app: AppData) {
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(app).write(to: url, options: .atomic)
        } catch {
            NSLog("fff3: cannot save %@: %@", url.path, error.localizedDescription)
        }
    }
}
