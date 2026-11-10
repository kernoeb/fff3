// Every frame with its exact hex, copied from DomProtocolTest.kt, MelkProtocolTest.kt,
// LedFamilyTest.kt and RgbTest.kt. Run with tools/test.sh.

import Foundation

var failures = 0
var checks = 0

func check(_ condition: Bool, _ message: @autoclosure () -> String, line: Int = #line) {
    checks += 1
    if !condition {
        failures += 1
        print("FAIL line \(line): \(message())")
    }
}

func frames(_ proto: any LedProtocol, _ command: Command) -> String {
    proto.encode(command).map { frame in frame.map { String(format: "%02x", $0) }.joined(separator: " ") }.joined(separator: " | ")
}

func expect(_ hex: String, _ proto: any LedProtocol, _ command: Command, line: Int = #line) {
    let got = frames(proto, command)
    check(got == hex, "\(command): expected \(hex), got \(got)", line: line)
}

let dom = DomProtocol.shared
let melk = MelkProtocol.shared

expect("7e 04 04 01 00 01 ff 00 ef", dom, .power(true))
expect("7e 04 04 00 00 00 ff 00 ef", dom, .power(false))
expect("7e 04 01 32 ff ff ff 00 ef", dom, .brightness(50))
expect("7e 04 01 64 ff ff ff 00 ef", dom, .brightness(100))
expect("7e 04 01 64 ff ff ff 00 ef", dom, .brightness(250))
expect("7e 04 01 00 ff ff ff 00 ef", dom, .brightness(-4))
expect("7e 07 05 03 ff 80 00 10 ef", dom, .color(Rgb(0xFF8000)))
expect("7e 07 05 03 12 34 56 10 ef", dom, .color(Rgb.of(0x12, 0x34, 0x56)))
expect("7e 05 03 80 03 ff ff 00 ef", dom, .effect(id: 0))
expect("7e 05 03 9c 03 ff ff 00 ef", dom, .effect(id: 28))
expect("7e 05 03 8a 03 ff ff 00 ef | 7e 04 02 4b ff ff ff 00 ef", dom, .effect(id: 10, speed: 75))
check(dom.encode(.effect(id: 29)).isEmpty, "DOM effect 29 has no frame")
check(dom.encode(.effect(id: -1, speed: 50)).isEmpty, "DOM effect -1 has no frame")
expect("7e 04 02 0a ff ff ff 00 ef", dom, .speed(10))
expect("7e 04 07 01 ff ff ff 00 ef", dom, .micEnabled(true))
expect("7e 04 07 00 ff ff ff 00 ef", dom, .micEnabled(false))
expect("7e 04 06 1e ff ff ff 00 ef", dom, .micSensitivity(30))
expect("7e 05 03 80 04 ff ff 00 ef", dom, .micMode(0))
expect("7e 05 03 83 04 ff ff 00 ef", dom, .micMode(3))
expect("7e 05 03 83 04 ff ff 00 ef", dom, .micMode(9))
expect("7e 06 81 01 02 03 ff 00 ef", dom, .wireOrder(.RGB))
expect("7e 06 81 02 01 03 ff 00 ef", dom, .wireOrder(.GRB))
expect("7e 06 81 03 02 01 ff 00 ef", dom, .wireOrder(.BGR))
expect("7e 07 83 14 1e 05 00 ff ef", dom, .clockSync(hour: 20, minute: 30, second: 5, dayOfWeek: .sunday))
expect("7e 07 83 08 00 00 01 ff ef", dom, .clockSync(hour: 8, minute: 0, second: 0, dayOfWeek: .monday))
expect("7e 07 83 17 3b 3b 06 ff ef", dom, .clockSync(hour: 23, minute: 59, second: 59, dayOfWeek: .saturday))
let weekdays: Set<DayOfWeek> = [.monday, .tuesday, .wednesday, .thursday, .friday]
expect("7e 08 82 07 00 00 00 9f ef", dom, .schedule(action: .turnOn, hour: 7, minute: 0, days: weekdays))
expect("7e 08 82 17 1e 00 01 c0 ef", dom, .schedule(action: .turnOff, hour: 23, minute: 30, days: [.sunday]))
expect("7e 08 82 17 1e 00 01 40 ef", dom, .schedule(action: .turnOff, hour: 23, minute: 30, days: [.sunday], enabled: false))
check(dom.encode(.scene(1)).isEmpty, "DOM has no scenes")
check(dom.encode(.pixelCount(100)).isEmpty, "DOM has no pixel count")

expect("7e 04 04 01 00 01 ff 00 ef", melk, .power(true))
expect("7e 04 01 32 ff ff ff 00 ef", melk, .brightness(50))
expect("7e 07 05 03 00 ff 00 10 ef", melk, .color(Rgb(0x00FF00)))
expect("7e 04 07 01 ff ff ff 00 ef", melk, .micEnabled(true))
expect("7e 04 06 50 ff ff ff 00 ef", melk, .micSensitivity(80))
expect("7e 07 83 0c 00 00 03 ff ef", melk, .clockSync(hour: 12, minute: 0, second: 0, dayOfWeek: .wednesday))
expect("7e 07 02 32 ff ff ff 00 ef", melk, .speed(50))
expect("7e 07 03 00 06 ff ff 00 ef", melk, .effect(id: 0))
expect("7e 07 03 d4 06 ff ff 00 ef", melk, .effect(id: 212))
expect("7e 07 03 c1 06 ff ff 00 ef | 7e 07 02 64 ff ff ff 00 ef", melk, .effect(id: 193, speed: 100))
expect("7e 05 03 80 04 ff ff 00 ef", melk, .micMode(0))
expect("7e 05 03 87 04 ff ff 00 ef", melk, .micMode(7))
expect("7e 05 03 87 04 ff ff 00 ef", melk, .micMode(12))
for (order, hex) in [
    (ChannelOrder.RGB, "7e 07 81 01 02 03 ff 00 ef"), (.RBG, "7e 07 81 01 03 02 ff 00 ef"),
    (.GRB, "7e 07 81 02 01 03 ff 00 ef"), (.GBR, "7e 07 81 02 03 01 ff 00 ef"),
    (.BRG, "7e 07 81 03 01 02 ff 00 ef"), (.BGR, "7e 07 81 03 02 01 ff 00 ef"),
] { expect(hex, melk, .wireOrder(order)) }
expect("7e 07 31 01 07 ff ff 00 ef", melk, .scene(1))
expect("7e 07 31 9c 07 ff ff 00 ef", melk, .scene(156))
expect("7e 07 21 2c 01 00 ff 00 ef", melk, .pixelCount(300))
expect("7e 07 21 e8 03 00 ff 00 ef", melk, .pixelCount(5000))
expect("7e 07 21 0a 00 00 ff 00 ef", melk, .pixelCount(1))

check(LedFamily.detect("ELK-BLEDOM") == .DOM, "ELK-BLEDOM")
check(LedFamily.detect("ELK-BLEDOM   ") == .DOM, "trailing spaces")
check(LedFamily.detect("ELK-HR01") == .DOM, "ELK-HR")
check(LedFamily.detect("CLK-BLEDDM") == .DOM, "CLK-BLE")
check(LedFamily.detect("ELK-BLEDOB") == .DOB, "DOB")
check(LedFamily.detect("ELK-BLEXYZ") == .DOM, "ELK-BLE")
check(LedFamily.detect("MELK-OA10") == .MELK, "MELK")
check(LedFamily.detect("MELK-OC21") == .MELK_SCENE, "MELK-OC")
check(LedFamily.detect("MELK-OD") == .MELK_SCENE, "MELK-OD")
check(LedFamily.detect("MELK-OF") == .MELK_SCENE, "MELK-OF")
for name in [nil, "", "LEDDMX-00", "Triones:A1", "elk-bledom"] {
    check(LedFamily.detect(name) == nil, "\(String(describing: name)) is not supported")
}
check(LedFamily.DOM.supports(.micEnabled(true)), "DOM mic")
check(!LedFamily.DOB.supports(.micEnabled(true)), "DOB no mic")
check(!LedFamily.DOM.supports(.scene(1)), "DOM no scene")
check(!LedFamily.MELK.supports(.scene(1)), "MELK no scene")
check(LedFamily.MELK_SCENE.supports(.scene(1)), "MELK_SCENE scene")
check(!LedFamily.MELK_SCENE.supports(.scene(200)), "MELK_SCENE no scene 200")
check(LedFamily.MELK.supports(.pixelCount(100)), "MELK pixel count")
check(!LedFamily.DOM.supports(.pixelCount(100)), "DOM no pixel count")
check(LedFamily.MELK.supports(.effect(id: 212)), "MELK effect 212")
check(!LedFamily.MELK.supports(.effect(id: 213)), "MELK no effect 213")
check(DomCatalog.effects.count == 29, "29 DOM effects")
check(DomCatalog.effects.map(\.id).sorted() == Array(0...28), "DOM ids 0...28")
check(MelkCatalog.effects.count == 213, "213 MELK effects")
check(Set(MelkCatalog.effects.map(\.id)).count == 213, "MELK ids unique")
check(MelkCatalog.scenes.count == 33, "33 scenes")
check(DomCatalog.micModes.count == 4 && MelkCatalog.micModes.count == 8, "mic modes")

let c = Rgb(0x123456)
check(c.red == 0x12 && c.green == 0x34 && c.blue == 0x56 && c.toHex() == "#123456", "Rgb parts")
check(Rgb.of(300, -2, 255) == Rgb(0xFF00FF), "Rgb.of clamps")
check(Rgb.fromHsv(0) == Rgb(0xFF0000) && Rgb.fromHsv(120) == Rgb(0x00FF00) && Rgb.fromHsv(240) == Rgb(0x0000FF), "hsv primaries")
check(Rgb.fromHsv(360) == Rgb(0xFF0000) && Rgb.fromHsv(-360) == Rgb(0xFF0000), "hsv wraps")
check(Rgb.fromHsv(42, saturation: 0) == .white && Rgb.fromHsv(42, value: 0) == .black, "hsv grey")
let warm = Rgb.fromKelvin(2700), cool = Rgb.fromKelvin(6500)
check(warm.red == 255 && warm.blue < cool.blue, "kelvin warm")
check(cool.red > 240 && cool.green > 240 && cool.blue > 240, "kelvin cool")

print("\(checks - failures) of \(checks) checks passed")
if failures > 0 { exit(1) }
