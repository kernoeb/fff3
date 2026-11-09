package com.kernoeb.fff3.ble

/**
 * Rolling median of a write time over every strip, for example from `LedController.send` to a
 * finished write. The music beat clock fires this much early. Thread safe.
 */
class WriteLatency(private val size: Int = 31) {
    private val samples = LongArray(size)
    private val sorted = LongArray(size)
    private var count = 0
    private var next = 0

    /** Median in milliseconds, or -1 before the first write. */
    @Volatile var medianMillis: Int = -1
        private set

    fun record(nanos: Long) {
        // A write that waited for a connection says nothing about the link speed.
        if (nanos > MAX_SAMPLE_NANOS) return
        synchronized(samples) {
            samples[next] = nanos
            next = (next + 1) % size
            if (count < size) count++
            samples.copyInto(sorted, 0, 0, count)
            sorted.sort(0, count)
            medianMillis = (sorted[count / 2] / 1_000_000).toInt()
        }
    }

    private companion object {
        const val MAX_SAMPLE_NANOS = 1_000_000_000L
    }
}
