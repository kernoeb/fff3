package com.kernoeb.fff3.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class WriteLatencyTest {

    @Test
    fun medianOfRecentWrites() {
        val latency = WriteLatency(size = 5)
        assertEquals(-1, latency.medianMillis)
        listOf(10, 50, 30).forEach { latency.record(it * 1_000_000L) }
        assertEquals(30, latency.medianMillis)
        // A write that waited for a connection is left out.
        latency.record(5_000_000_000L)
        assertEquals(30, latency.medianMillis)
        // Only the last 5 writes count.
        repeat(5) { latency.record(20_000_000L) }
        assertEquals(20, latency.medianMillis)
    }
}
