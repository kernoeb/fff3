/// A controller family, picked from the name the strip advertises.
/// Each family knows its protocol, catalogues and which commands its hardware accepts.
public enum LedFamily: String, CaseIterable, Codable, Sendable {
    case DOM, DOB, MELK, MELK_SCENE

    public var label: String {
        switch self {
        case .DOM: "ELK-BLEDOM"
        case .DOB: "ELK-BLEDOB"
        case .MELK: "MELK"
        case .MELK_SCENE: "MELK (scenes)"
        }
    }

    public var proto: any LedProtocol {
        switch self {
        case .DOM, .DOB: DomProtocol.shared
        case .MELK, .MELK_SCENE: MelkProtocol.shared
        }
    }

    public var effects: [EffectInfo] {
        switch self {
        case .DOM, .DOB: DomCatalog.effects
        case .MELK, .MELK_SCENE: MelkCatalog.effects
        }
    }

    public var scenes: [EffectInfo] { self == .MELK_SCENE ? MelkCatalog.scenes : [] }

    /// DOB has the DOM bytes but no microphone modes.
    public var micModes: [String] {
        switch self {
        case .DOM: DomCatalog.micModes
        case .DOB: []
        case .MELK, .MELK_SCENE: MelkCatalog.micModes
        }
    }

    public var hasWireOrder: Bool { true }
    public var hasPixelCount: Bool { self == .MELK || self == .MELK_SCENE }
    public var hasMic: Bool { !micModes.isEmpty }

    /// The frames for `command`, or none if this family cannot do it.
    public func encode(_ command: Command) -> [[UInt8]] {
        accepts(command) ? proto.encode(command) : []
    }

    public func supports(_ command: Command) -> Bool { !encode(command).isEmpty }

    private func accepts(_ command: Command) -> Bool {
        switch command {
        case .micEnabled, .micMode, .micSensitivity: hasMic
        case .effect(let id, _): effects.contains { $0.id == id }
        case .scene(let id): scenes.contains { $0.id == id }
        case .wireOrder: hasWireOrder
        case .pixelCount: hasPixelCount
        case .power, .color, .brightness, .speed, .clockSync, .schedule: true
        }
    }

    /// Families are tried in a fixed order. Matching is "contains", case-sensitive.
    /// Nil for names of families fff3 does not support (DMX, Triones, SP…) or no name.
    public static func detect(_ advertisedName: String?) -> LedFamily? {
        guard let name = advertisedName else { return nil }
        if name.contains("ELK-BLEDOM") || name.contains("ELK-HR") || name.contains("CLK-BLE") { return .DOM }
        if name.contains("MELK-OC") || name.contains("MELK-OD") || name.contains("MELK-OF") { return .MELK_SCENE }
        if name.contains("MELK-O") { return .MELK }
        if name.contains("ELK-BLEDOB") { return .DOB }
        if name.contains("ELK-BLE") { return .DOM }
        return nil
    }
}
