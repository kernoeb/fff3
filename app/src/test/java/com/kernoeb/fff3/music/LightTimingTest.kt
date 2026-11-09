package com.kernoeb.fff3.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightTimingTest {

    @Test
    fun cameraDelayIncludesTheQueueWait() {
        // The camera times from the Bluetooth write, so the 17 ms in the queue comes on top.
        assertEquals(0.057, LightTiming.linkSeconds(listOf(40), queueWaitMs = 17, writeMs = 25), 1e-9)
        // Before the queue has a measure, half a frame interval is assumed.
        assertEquals(0.057, LightTiming.linkSeconds(listOf(40), queueWaitMs = -1, writeMs = 25), 1e-9)
    }

    @Test
    fun unmeasuredStripsUseTheWriteTime() {
        assertEquals(0.025, LightTiming.linkSeconds(listOf(null), queueWaitMs = 17, writeMs = 25), 1e-9)
        assertEquals(LightTiming.DEFAULT_LINK_S, LightTiming.linkSeconds(listOf(null), 17, writeMs = -1), 1e-9)
        assertEquals(0.025, LightTiming.linkSeconds(emptyList(), 17, 25), 1e-9)
    }

    @Test
    fun oneMeasuredStripDoesNotSetTheWholeGroup() {
        // A at 40 + 10 ms, B unmeasured at 20 ms: the median is between them, not A alone.
        assertEquals(0.035, LightTiming.linkSeconds(listOf(40, null), queueWaitMs = 10, writeMs = 20), 1e-9)
        // With 3 strips, one slow strip does not pull the lead.
        assertEquals(0.060, LightTiming.linkSeconds(listOf(50, 55, 300), queueWaitMs = 5, writeMs = 20), 1e-9)
    }

    @Test
    fun shownDelayNeedsEveryStripMeasured() {
        val delays = mapOf("A" to 40, "B" to 61)
        assertEquals(51, LightTiming.measuredMs(delays, listOf("A", "B")))
        assertEquals(40, LightTiming.measuredMs(delays, listOf("A")))
        assertNull(LightTiming.measuredMs(delays, listOf("A", "C")))
        assertNull(LightTiming.measuredMs(delays, emptyList()))
    }
}
