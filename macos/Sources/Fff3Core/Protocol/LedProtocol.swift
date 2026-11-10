/// Turns commands into the bytes one controller family understands. Pure and stateless.
public protocol LedProtocol: Sendable {
    var gatt: GattProfile { get }
    /// The frames to write, in order, one GATT write each. Empty when there is no frame for it.
    func encode(_ command: Command) -> [[UInt8]]
}

/// Where to write the frames. The link falls back to a normal write if the characteristic
/// does not allow writes without response.
public struct GattProfile: Sendable {
    public let service: String
    public let writeCharacteristic: String
    public let writeWithoutResponse: Bool
}

/// One entry of an effect or scene catalogue. `id` is the value `.effect` or `.scene` takes.
public struct EffectInfo: Hashable, Sendable {
    public let id: Int
    public let name: String
    public let group: String

    init(_ id: Int, _ name: String, _ group: String) {
        self.id = id
        self.name = name
        self.group = group
    }
}

func bytesOf(_ values: Int...) -> [UInt8] { values.map { UInt8(truncatingIfNeeded: $0) } }

extension Array where Element == UInt8 {
    /// Lower-case hex with no separator, for logs and checks: `7e0404...`.
    public var hex: String { map { String(format: "%02x", $0) }.joined() }
}

/// ELK-BLEDOM family (also ELK-BLEDOB, ELK-HR, CLK-BLEDDM).
///
/// Every frame is 9 bytes: `7e LL CC … ef`. From the protocol notes kept outside this repo.
public struct DomProtocol: LedProtocol {
    public static let shared = DomProtocol()

    public let gatt = GattProfile(
        service: "0000fff0-0000-1000-8000-00805f9b34fb",
        writeCharacteristic: "0000fff3-0000-1000-8000-00805f9b34fb",
        writeWithoutResponse: true
    )

    /// Byte 7 of the colour frame. Strips accept `10` and `00`. Meaning unknown.
    static let colorTrailer = 0x10
    static let effectCount = 29
    static let micModeCount = 4

    public func encode(_ command: Command) -> [[UInt8]] {
        switch command {
        case .power(let on): [Self.power(on)]
        case .color(let rgb): [Self.color(rgb)]
        case .brightness(let percent): [Self.brightness(percent)]
        case .effect(let id, let speed):
            Self.effect(id).map { [$0] + (speed.map { [Self.speed($0)] } ?? []) } ?? []
        case .speed(let percent): [Self.speed(percent)]
        case .micEnabled(let on): [Self.micEnabled(on)]
        case .micMode(let index): [Self.micMode(index, modeCount: Self.micModeCount)]
        case .micSensitivity(let percent): [Self.micSensitivity(percent)]
        case .wireOrder(let order): [Self.wireOrder(order)]
        case .clockSync: [Self.clockSync(command)]
        case .schedule: [Self.schedule(command)]
        case .scene, .pixelCount: []
        }
    }

    static func power(_ on: Bool) -> [UInt8] {
        let p = on ? 0x01 : 0x00
        return bytesOf(0x7e, 0x04, 0x04, p, 0x00, p, 0xff, 0x00, 0xef)
    }

    static func color(_ rgb: Rgb) -> [UInt8] {
        bytesOf(0x7e, 0x07, 0x05, 0x03, rgb.red, rgb.green, rgb.blue, colorTrailer, 0xef)
    }

    static func brightness(_ percent: Int) -> [UInt8] {
        bytesOf(0x7e, 0x04, 0x01, percent.clamped(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)
    }

    /// `index` 0...28, sent as `0x80 + index`. Nil outside the catalogue.
    static func effect(_ index: Int) -> [UInt8]? {
        guard (0..<effectCount).contains(index) else { return nil }
        return bytesOf(0x7e, 0x05, 0x03, 0x80 + index, 0x03, 0xff, 0xff, 0x00, 0xef)
    }

    static func speed(_ percent: Int) -> [UInt8] {
        bytesOf(0x7e, 0x04, 0x02, percent.clamped(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)
    }

    static func micEnabled(_ on: Bool) -> [UInt8] {
        bytesOf(0x7e, 0x04, 0x07, on ? 0x01 : 0x00, 0xff, 0xff, 0xff, 0x00, 0xef)
    }

    static func micSensitivity(_ percent: Int) -> [UInt8] {
        bytesOf(0x7e, 0x04, 0x06, percent.clamped(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)
    }

    /// Shared with MELK, which has 8 modes instead of 4.
    static func micMode(_ index: Int, modeCount: Int) -> [UInt8] {
        bytesOf(0x7e, 0x05, 0x03, 0x80 + index.clamped(0, modeCount - 1), 0x04, 0xff, 0xff, 0x00, 0xef)
    }

    static func wireOrder(_ order: ChannelOrder) -> [UInt8] {
        let (a, b, c) = order.wireCodes
        return bytesOf(0x7e, 0x06, 0x81, a, b, c, 0xff, 0x00, 0xef)
    }

    /// The weekday byte is 0 for Sunday to 6 for Saturday.
    static func clockSync(_ command: Command) -> [UInt8] {
        guard case let .clockSync(hour, minute, second, day) = command else { return [] }
        return bytesOf(0x7e, 0x07, 0x83, hour.clamped(0, 23), minute.clamped(0, 59), second.clamped(0, 59), day.rawValue % 7, 0xff, 0xef)
    }

    /// Byte 6 is 00 for the on schedule, 01 for the off schedule. Byte 7 is the weekday mask
    /// (bit 0 Monday … bit 6 Sunday), with bit 7 set while enabled. Bit order not confirmed on hardware.
    static func schedule(_ command: Command) -> [UInt8] {
        guard case let .schedule(action, hour, minute, days, enabled) = command else { return [] }
        let actionByte = action == .turnOn ? 0x00 : 0x01
        let mask = weekdayMask(days) | (enabled ? 0x80 : 0x00)
        return bytesOf(0x7e, 0x08, 0x82, hour.clamped(0, 23), minute.clamped(0, 59), 0x00, actionByte, mask, 0xef)
    }

    static func weekdayMask(_ days: Set<DayOfWeek>) -> Int {
        days.reduce(0) { $0 | (1 << ($1.rawValue - 1)) }
    }
}

/// MELK family (MELK-OA…, and MELK-OC/OD/OF which add scenes).
///
/// Same 9-byte frame and GATT layout as DOM. MELK changes speed, pattern, mic mode and wire order,
/// and adds scenes and pixel count. Everything else uses the DOM frame.
public struct MelkProtocol: LedProtocol {
    public static let shared = MelkProtocol()

    public var gatt: GattProfile { DomProtocol.shared.gatt }

    static let micModeCount = 8

    public func encode(_ command: Command) -> [[UInt8]] {
        switch command {
        case .power, .color, .brightness, .micEnabled, .micSensitivity, .clockSync, .schedule:
            DomProtocol.shared.encode(command)
        case .effect(let id, let speed): [Self.pattern(id)] + (speed.map { [Self.speed($0)] } ?? [])
        case .speed(let percent): [Self.speed(percent)]
        case .micMode(let index): [DomProtocol.micMode(index, modeCount: Self.micModeCount)]
        case .wireOrder(let order): [Self.wireOrder(order)]
        case .scene(let id): [Self.scene(id)]
        case .pixelCount(let count): [Self.pixelCount(count)]
        }
    }

    static func speed(_ percent: Int) -> [UInt8] {
        bytesOf(0x7e, 0x07, 0x02, percent.clamped(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)
    }

    /// `id` is the pattern byte itself, 0...255.
    static func pattern(_ id: Int) -> [UInt8] {
        bytesOf(0x7e, 0x07, 0x03, id.clamped(0, 255), 0x06, 0xff, 0xff, 0x00, 0xef)
    }

    static func wireOrder(_ order: ChannelOrder) -> [UInt8] {
        let (a, b, c) = order.wireCodes
        return bytesOf(0x7e, 0x07, 0x81, a, b, c, 0xff, 0x00, 0xef)
    }

    static func scene(_ id: Int) -> [UInt8] {
        bytesOf(0x7e, 0x07, 0x31, id.clamped(0, 255), 0x07, 0xff, 0xff, 0x00, 0xef)
    }

    /// Little-endian count, 10...1000.
    static func pixelCount(_ count: Int) -> [UInt8] {
        let n = count.clamped(10, 1000)
        return bytesOf(0x7e, 0x07, 0x21, n & 0xff, (n >> 8) & 0xff, 0x00, 0xff, 0x00, 0xef)
    }
}
