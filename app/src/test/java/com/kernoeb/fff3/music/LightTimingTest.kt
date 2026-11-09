package com.kernoeb.fff3.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightTimingTest {

    @Test
    fun measuredDelayIsTheAverageOfTheMeasuredStrips() {
        val delays = mapOf("A" to 40, "B" to 61)
        assertEquals(51, LightTiming.measuredMs(delays, listOf("A", "B", "C")))
        assertEquals(40, LightTiming.measuredMs(delays, listOf("A")))
        assertNull(LightTiming.measuredMs(delays, listOf("C")))
    }
}
