/// How far ahead the beat clock is read, so a light change shows when its beat is heard.
public enum LightTiming {
    /// Used for a strip with no camera value until the link has measured its own write time.
    public static let defaultLinkS = 0.060

    /// Used until the queue has measured its own wait: half a frame interval.
    public static let defaultQueueWaitMs = 35 / 2

    /// Seconds from a send until the strips show the colour. `cameraMs` holds each target strip's
    /// camera-measured delay, or nil. A camera value starts at the Bluetooth write, so the
    /// `queueWaitMs` before that write is added. A strip with no camera value uses `writeMs`.
    /// Negative times mean not measured yet.
    public static func linkSeconds(cameraMs: [Int?], queueWaitMs: Int, writeMs: Int) -> Double {
        let fallback = writeMs >= 0 ? Double(writeMs) / 1000.0 : defaultLinkS
        let queue = queueWaitMs >= 0 ? queueWaitMs : defaultQueueWaitMs
        let perStrip = cameraMs.map { $0.map { Double($0 + queue) / 1000.0 } ?? fallback }
        // All strips get the same colour at the same time, so one lead serves them all. The median puts
        // 2 strips equally far off, and one odd strip in a bigger group cannot pull it.
        return median(perStrip) ?? fallback
    }

    private static func median(_ values: [Double]) -> Double? {
        if values.isEmpty { return nil }
        let sorted = values.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
    }

    /// All values in seconds. `link` is the time from send until the strip shows the colour,
    /// `capture` how stale the audio is when analysed, `output` how long the computer takes to play
    /// captured sound (lights must wait for it), and `lightDelay` the user's fine-tune (positive makes the lights later).
    public static func lead(link: Double, capture: Double, output: Double, lightDelay: Double) -> Double {
        link + capture - output - lightDelay
    }
}
