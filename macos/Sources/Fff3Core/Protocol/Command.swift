import Foundation

/// Something you can ask a strip to do. A family's protocol turns it into bytes.
///
/// Values outside their range are clamped by the encoder, never rejected.
/// Not every family supports every command: check `LedFamily.supports`.
/// No family has a white channel. For white, send `.color(Rgb.fromKelvin(k))`.
public enum Command: Hashable, Sendable {
    case power(Bool)
    case color(Rgb)
    /// Percent, 0...100.
    case brightness(Int)
    /// A built-in animation from the family's catalogue. With a speed (0...100), a speed frame follows.
    case effect(id: Int, speed: Int? = nil)
    case speed(Int)
    case micEnabled(Bool)
    case micMode(Int)
    case micSensitivity(Int)
    /// A MELK_SCENE preset from `LedFamily.scenes`.
    case scene(Int)
    /// Takes effect after a power cycle.
    case wireOrder(ChannelOrder)
    /// MELK only, 10...1000.
    case pixelCount(Int)
    case clockSync(hour: Int, minute: Int, second: Int, dayOfWeek: DayOfWeek)
    /// An empty `days` set means no day. `enabled` false clears it.
    case schedule(action: ScheduleAction, hour: Int, minute: Int, days: Set<DayOfWeek>, enabled: Bool = true)

    /// The kind of value a user drags. The write queue keeps only the newest pending value of each kind.
    public var coalesceKey: Int? {
        switch self {
        case .color: 1
        case .brightness: 2
        case .speed: 3
        case .micSensitivity: 4
        default: nil
        }
    }

    public var coalesces: Bool { coalesceKey != nil }

    public static func clockSync(at date: Date, calendar: Calendar = .current) -> Command {
        let parts = calendar.dateComponents([.hour, .minute, .second, .weekday], from: date)
        // Calendar counts 1 for Sunday; java.time counts 1 for Monday and 7 for Sunday.
        let weekday = parts.weekday ?? 1
        let day = DayOfWeek(rawValue: weekday == 1 ? 7 : weekday - 1) ?? .monday
        return .clockSync(hour: parts.hour ?? 0, minute: parts.minute ?? 0, second: parts.second ?? 0, dayOfWeek: day)
    }
}

/// Same numbers as `java.time.DayOfWeek`: 1 for Monday to 7 for Sunday.
public enum DayOfWeek: Int, CaseIterable, Hashable, Sendable {
    case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday
}

public enum ScheduleAction: Sendable { case turnOn, turnOff }

/// Wire order, named by the colour on the first, second and third wire.
public enum ChannelOrder: String, CaseIterable, Sendable {
    case RGB, RBG, GRB, GBR, BRG, BGR

    var wireCodes: (Int, Int, Int) {
        let codes = rawValue.map { $0 == "R" ? 1 : $0 == "G" ? 2 : 3 }
        return (codes[0], codes[1], codes[2])
    }
}
