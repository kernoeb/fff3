import CoreBluetooth
import Foundation
import Fff3Core

enum ConnectionState: Equatable, Sendable {
    case idle
    case connecting(attempt: Int)
    case connected
    case waitingToRetry(failures: Int, retryInMillis: Int)
    case bluetoothOff
    case permissionMissing
    case unsupported
}

enum BluetoothStatus: Sendable { case unknown, ready, off, permissionMissing, unsupported }

struct ScannedStrip: Identifiable, Hashable, Sendable {
    let id: UUID
    let name: String
    let family: LedFamily
    let rssi: Int
}

/// Every strip's link, on one serial queue. CoreBluetooth calls back on that queue too, so the
/// state below is only touched there. Results go to the main actor through the `on…` callbacks.
final class LedController: NSObject, @unchecked Sendable {
    let queue = DispatchQueue(label: "com.kernoeb.fff3.ble", qos: .userInteractive)
    /// Time from send to a finished write, for the music beat clock.
    let linkLatency = WriteLatency()
    /// Time each command waits before its Bluetooth write, for the music beat clock.
    let queueWait = WriteLatency()

    private var central: CBCentralManager!
    private var links: [UUID: StripLink] = [:]
    private var status = BluetoothStatus.unknown
    private var scanning = false
    private var scanned: [UUID: ScannedStrip] = [:]
    private var scanPublishedAt: UInt64 = 0

    private let onStates: @Sendable ([UUID: ConnectionState]) -> Void
    private let onScan: @Sendable ([ScannedStrip]) -> Void
    private let onStatus: @Sendable (BluetoothStatus) -> Void

    init(
        onStates: @escaping @Sendable ([UUID: ConnectionState]) -> Void,
        onScan: @escaping @Sendable ([ScannedStrip]) -> Void,
        onStatus: @escaping @Sendable (BluetoothStatus) -> Void
    ) {
        self.onStates = onStates
        self.onScan = onScan
        self.onStatus = onStatus
        super.init()
        queue.async {
            self.central = CBCentralManager(delegate: self, queue: self.queue, options: [CBCentralManagerOptionShowPowerAlertKey: true])
        }
    }

    /// Keeps one link per known strip, and closes the links of strips that were removed.
    func setDevices(_ devices: [KnownDevice]) {
        queue.async {
            let wanted = Dictionary(uniqueKeysWithValues: devices.map { ($0.id, $0) })
            for (id, link) in self.links where wanted[id] == nil || wanted[id]!.family != link.family {
                link.close()
                self.links[id] = nil
            }
            for device in devices where self.links[device.id] == nil {
                self.links[device.id] = StripLink(id: device.id, family: device.family, controller: self)
            }
            if self.status == .ready { self.links.values.forEach { $0.start() } }
            self.publishStates()
        }
    }

    /// Never blocks. A command a strip's family cannot do is skipped for that strip.
    func send(_ ids: [UUID], _ commands: [Command]) {
        queue.async {
            for id in ids {
                guard let link = self.links[id] else { continue }
                for command in commands { link.send(command) }
            }
        }
    }

    func retryNow() {
        queue.async { self.links.values.forEach { $0.retryNow() } }
    }

    func startScan() {
        queue.async {
            self.scanning = true
            self.scanned = [:]
            self.onScan([])
            self.beginScan()
        }
    }

    func stopScan() {
        queue.async {
            self.scanning = false
            if self.central?.isScanning == true { self.central.stopScan() }
        }
    }

    /// Disconnects every strip, for example when the app quits.
    func closeAll() {
        queue.sync { self.links.values.forEach { $0.close() } }
    }

    private func beginScan() {
        guard scanning, status == .ready, central.isScanning == false else { return }
        // The strips do not always advertise their service, so scan for everything and filter by name.
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    fileprivate func publishStates() {
        let states = links.mapValues { $0.state }
        onStates(states)
    }

    fileprivate func peripheral(for id: UUID) -> CBPeripheral? {
        central.retrievePeripherals(withIdentifiers: [id]).first
    }

    fileprivate var centralManager: CBCentralManager { central }
    fileprivate var bluetoothStatus: BluetoothStatus { status }
}

extension LedController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        status = switch central.state {
        case .poweredOn: .ready
        case .poweredOff, .resetting: .off
        case .unauthorized: .permissionMissing
        case .unsupported: .unsupported
        default: .unknown
        }
        onStatus(status)
        for link in links.values {
            if status == .ready { link.start() } else { link.bluetoothGone(status) }
        }
        if status == .ready { beginScan() }
        publishStates()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard scanning else { return }
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        guard let family = LedFamily.detect(name), let name else { return }
        scanned[peripheral.identifier] = ScannedStrip(id: peripheral.identifier, name: name, family: family, rssi: rssi.intValue)
        // Duplicates come many times a second: publish at most 4 times a second.
        let now = nowNanos()
        if now - scanPublishedAt > 250_000_000 {
            scanPublishedAt = now
            onScan(scanned.values.sorted { $0.rssi > $1.rssi })
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        links[peripheral.identifier]?.didConnect()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        links[peripheral.identifier]?.didDrop()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        links[peripheral.identifier]?.didDrop()
    }
}

/// The link to one strip. It connects as soon as Bluetooth is ready, reconnects with backoff
/// when the link drops, and sends the Mac's time on each connect, like `ClockSyncer.kt`.
final class StripLink: NSObject, CBPeripheralDelegate, @unchecked Sendable {
    let id: UUID
    let family: LedFamily
    private unowned let controller: LedController
    private(set) var state = ConnectionState.idle {
        didSet { if state != oldValue { controller.publishStates() } }
    }
    private var peripheral: CBPeripheral?
    private var characteristic: CBCharacteristic?
    private var writeType = CBCharacteristicWriteType.withoutResponse
    private var failures = 0
    private var attempt = 0
    private var closed = false
    private var pendingAck: ((WriteOutcome) -> Void)?
    private var writeQueue: WriteQueue!

    init(id: UUID, family: LedFamily, controller: LedController) {
        self.id = id
        self.family = family
        self.controller = controller
        super.init()
        writeQueue = WriteQueue(
            queue: controller.queue,
            onSent: { [latency = controller.linkLatency] in latency.record($0) },
            onWriteStarted: { [wait = controller.queueWait] in wait.record($0) },
            write: { [weak self] frame, done in self?.write(frame, done) ?? done(.failed) }
        )
    }

    func send(_ command: Command) {
        let frames = family.encode(command)
        if frames.isEmpty { return }
        writeQueue.enqueue(command, frames: frames)
    }

    func start() {
        guard !closed, peripheral == nil || state == .idle || isBluetoothState else { return }
        connect()
    }

    private var isBluetoothState: Bool {
        state == .bluetoothOff || state == .permissionMissing || state == .unsupported
    }

    func retryNow() {
        if case .waitingToRetry = state { connect() }
    }

    func bluetoothGone(_ status: BluetoothStatus) {
        attempt += 1
        characteristic = nil
        failPendingAck()
        state = switch status {
        case .permissionMissing: .permissionMissing
        case .unsupported: .unsupported
        default: .bluetoothOff
        }
    }

    func close() {
        closed = true
        attempt += 1
        writeQueue.clear()
        failPendingAck()
        if let peripheral { controller.centralManager.cancelPeripheralConnection(peripheral) }
        peripheral = nil
        characteristic = nil
        state = .idle
    }

    private func connect() {
        guard !closed, controller.bluetoothStatus == .ready else { return }
        guard let found = controller.peripheral(for: id) else {
            // This Mac has never seen the strip: it must be added again from a scan.
            failures += 1
            scheduleRetry()
            return
        }
        attempt += 1
        let token = attempt
        peripheral = found
        found.delegate = self
        state = .connecting(attempt: failures + 1)
        controller.centralManager.connect(found)
        controller.queue.asyncAfter(deadline: .now() + BleTuning.connectTimeout + BleTuning.discoveryTimeout) { [weak self] in
            guard let self, token == self.attempt, self.characteristic == nil, !self.closed else { return }
            // Connect or discovery took too long: CoreBluetooth never gives up on its own.
            if let peripheral = self.peripheral { self.controller.centralManager.cancelPeripheralConnection(peripheral) }
            self.didDrop()
        }
    }

    func didConnect() {
        peripheral?.discoverServices([CBUUID(string: family.proto.gatt.service)])
    }

    func didDrop() {
        guard !closed else { return }
        let wasConnected = state == .connected
        attempt += 1
        characteristic = nil
        failPendingAck()
        failures = wasConnected ? 0 : failures + 1
        scheduleRetry()
    }

    private func scheduleRetry() {
        let wait = backoff(failures)
        state = .waitingToRetry(failures: failures, retryInMillis: wait)
        let token = attempt
        controller.queue.asyncAfter(deadline: .now() + .milliseconds(wait)) { [weak self] in
            guard let self, token == self.attempt, !self.closed else { return }
            self.connect()
        }
    }

    private func backoff(_ failures: Int) -> Int {
        if failures == 0 { return 500 }
        let base = min(BleTuning.reconnectFirstDelayMs << min(failures - 1, 10), BleTuning.reconnectMaxDelayMs)
        return base + Int.random(in: 0...(base / 4))
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let gatt = family.proto.gatt
        guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: gatt.service) }) else {
            controller.centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([CBUUID(string: gatt.writeCharacteristic)], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let gatt = family.proto.gatt
        guard let found = service.characteristics?.first(where: { $0.uuid == CBUUID(string: gatt.writeCharacteristic) }) else {
            NSLog("fff3: %@ has no %@ in %@", id.uuidString, gatt.writeCharacteristic, gatt.service)
            controller.centralManager.cancelPeripheralConnection(peripheral)
            return
        }
        let canSkipResponse = found.properties.contains(.writeWithoutResponse)
        writeType = gatt.writeWithoutResponse && canSkipResponse ? .withoutResponse : .withResponse
        characteristic = found
        failures = 0
        state = .connected
        send(.clockSync(at: Date()))
        writeQueue.wake()
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        let ack = pendingAck
        pendingAck = nil
        ack?(error == nil ? .sent : .failed)
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        writeQueue.wake()
    }

    private func write(_ frame: [UInt8], _ done: @escaping (WriteOutcome) -> Void) {
        guard let peripheral, let characteristic, state == .connected else {
            done(.waiting)
            return
        }
        let data = Data(frame)
        if writeType == .withoutResponse {
            guard peripheral.canSendWriteWithoutResponse else {
                done(.busy)
                return
            }
            peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
            logFrame(frame)
            done(.sent)
            return
        }
        pendingAck = done
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
        logFrame(frame)
        let token = attempt
        controller.queue.asyncAfter(deadline: .now() + BleTuning.writeAckTimeout) { [weak self] in
            // No acknowledgement in time: send the next frame anyway.
            guard let self, token == self.attempt, let ack = self.pendingAck else { return }
            self.pendingAck = nil
            ack(.sent)
        }
    }

    /// FFF3_BLE_LOG=1 prints each frame as it goes to the strip, with the time in milliseconds.
    private func logFrame(_ frame: [UInt8]) {
        guard Self.logFrames else { return }
        let line = String(format: "fff3-ble %.1f %@ %@\n", Double(nowNanos()) / 1e6, String(id.uuidString.prefix(4)), frame.hex)
        FileHandle.standardError.write(line.data(using: .utf8)!)
    }

    private static let logFrames = ProcessInfo.processInfo.environment["FFF3_BLE_LOG"] != nil

    private func failPendingAck() {
        let ack = pendingAck
        pendingAck = nil
        ack?(.waiting)
    }
}
