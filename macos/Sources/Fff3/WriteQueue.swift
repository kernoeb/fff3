import Foundation
import Fff3Core

/// Timing values for the Bluetooth link, the same as `BleTuning.kt`.
enum BleTuning {
    /// Minimum time between 2 frames to one strip. 35 ms is about 28 frames a second.
    static let minFrameIntervalNanos: UInt64 = 35_000_000
    /// Wait before retrying a write the Bluetooth stack refused as busy.
    static let busyRetryNanos: UInt64 = 8_000_000
    static let maxBusyRetries = 60
    /// Wait before retrying a write that failed for another reason.
    static let failedRetryNanos: UInt64 = 100_000_000
    static let maxFailedRetries = 3
    /// How long to wait for a write acknowledgement before sending the next frame anyway.
    static let writeAckTimeout = 0.5
    static let connectTimeout = 10.0
    static let discoveryTimeout = 8.0
    static let reconnectFirstDelayMs = 1_000
    static let reconnectMaxDelayMs = 30_000
    /// Most commands waiting for one strip. Older non-drag commands are dropped first.
    static let maxPendingCommands = 64
}

func nowNanos() -> UInt64 { DispatchTime.now().uptimeNanoseconds }

/// Rolling median of a write time over every strip. The music beat clock fires this much early. Thread safe.
final class WriteLatency: @unchecked Sendable {
    private let lock = NSLock()
    private var samples = [UInt64](repeating: 0, count: 31)
    private var count = 0
    private var next = 0
    private var median = -1

    /// Median in milliseconds, or -1 before the first write.
    var medianMillis: Int { lock.withLock { median } }

    func record(_ nanos: UInt64) {
        // A write that waited for a connection says nothing about the link speed.
        if nanos > 1_000_000_000 { return }
        lock.withLock {
            samples[next] = nanos
            next = (next + 1) % samples.count
            if count < samples.count { count += 1 }
            let sorted = samples[0..<count].sorted()
            median = Int(sorted[count / 2] / 1_000_000)
        }
    }
}

enum WriteOutcome { case sent, busy, failed, waiting }

/// Sends frames to one strip, one at a time, at most one every 35 ms. A port of `WriteQueue.kt`
/// on a serial dispatch queue instead of coroutines. Use it only on that queue.
///
/// A command that coalesces replaces any pending command of the same kind and moves to the back,
/// so a fast drag sends only the newest value, the final value is always sent, and a tap made after
/// a drag is not overtaken by an older drag value. Other commands are sent in order and never merged.
final class WriteQueue: @unchecked Sendable {
    private struct Item {
        let key: Int?
        let frames: [[UInt8]]
        let enqueuedAt: UInt64
    }

    private let queue: DispatchQueue
    /// Writes one frame. `.waiting` means the link is not up: the queue tries again on `wake`.
    private let write: ([UInt8], @escaping (WriteOutcome) -> Void) -> Void
    private let onSent: (UInt64) -> Void
    private let onWriteStarted: (UInt64) -> Void

    private var pending: [Item] = []
    private var current: Item?
    private var frameIndex = 0
    private var busy = 0
    private var failed = 0
    private var lastStart: UInt64 = 0
    private var lastWriteStart: UInt64?
    private var running = false
    private var generation = 0

    init(
        queue: DispatchQueue,
        onSent: @escaping (UInt64) -> Void,
        onWriteStarted: @escaping (UInt64) -> Void,
        write: @escaping ([UInt8], @escaping (WriteOutcome) -> Void) -> Void
    ) {
        self.queue = queue
        self.onSent = onSent
        self.onWriteStarted = onWriteStarted
        self.write = write
    }

    var isIdle: Bool { pending.isEmpty && current == nil }

    /// Must run on `queue`.
    func enqueue(_ command: Command, frames: [[UInt8]]) {
        if frames.isEmpty { return }
        let key = command.coalesceKey
        if let key, let older = pending.firstIndex(where: { $0.key == key }) { pending.remove(at: older) }
        pending.append(Item(key: key, frames: frames, enqueuedAt: nowNanos()))
        while pending.count > BleTuning.maxPendingCommands {
            pending.remove(at: pending.firstIndex(where: { $0.key == nil }) ?? 0)
        }
        wake()
    }

    /// Drops everything pending and aborts the command being written.
    func clear() {
        pending.removeAll()
        current = nil
        running = false
        generation += 1
    }

    /// Tries again, for example once the link is up.
    func wake() {
        if running { return }
        step()
    }

    private func step() {
        if current == nil {
            guard !pending.isEmpty else { running = false; return }
            current = pending.removeFirst()
            frameIndex = 0
            busy = 0
            failed = 0
            lastStart = current!.enqueuedAt
        }
        guard let item = current else { return }
        running = true
        // A newer value of the same kind is waiting: it will be sent instead.
        if let key = item.key, pending.contains(where: { $0.key == key }) {
            finish(sent: false)
            return
        }
        if let last = lastWriteStart {
            let ready = last + BleTuning.minFrameIntervalNanos
            let now = nowNanos()
            if ready > now {
                later(ready - now)
                return
            }
        }
        let start = nowNanos()
        let token = generation
        write(item.frames[frameIndex]) { [weak self] outcome in
            guard let self, token == self.generation, self.current != nil else { return }
            switch outcome {
            case .sent:
                self.lastWriteStart = start
                self.lastStart = start
                self.frameIndex += 1
                self.busy = 0
                self.failed = 0
                if self.frameIndex >= item.frames.count { self.finish(sent: true) } else { self.step() }
            case .busy:
                self.busy += 1
                if self.busy > BleTuning.maxBusyRetries { self.finish(sent: false) } else { self.later(BleTuning.busyRetryNanos) }
            case .failed:
                self.failed += 1
                if self.failed > BleTuning.maxFailedRetries { self.finish(sent: false) } else { self.later(BleTuning.failedRetryNanos) }
            case .waiting:
                self.running = false
            }
        }
    }

    private func finish(sent: Bool) {
        if sent, let item = current {
            onWriteStarted(lastStart - item.enqueuedAt)
            onSent(nowNanos() - item.enqueuedAt)
        }
        current = nil
        step()
    }

    private func later(_ nanos: UInt64) {
        let token = generation
        queue.asyncAfter(deadline: .now() + .nanoseconds(Int(nanos))) { [weak self] in
            guard let self, token == self.generation else { return }
            self.step()
        }
    }
}
