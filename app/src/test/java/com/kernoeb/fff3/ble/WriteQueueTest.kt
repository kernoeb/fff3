package com.kernoeb.fff3.ble

import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.DomProtocol
import com.kernoeb.fff3.protocol.Rgb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WriteQueueTest {

    private data class Write(val atMs: Long, val frame: ByteArray)

    private class Harness(scope: TestScope, interval: Long = 35, val outcomes: ArrayDeque<WriteOutcome> = ArrayDeque()) {
        val writes = mutableListOf<Write>()
        val queue = WriteQueue(
            scope = scope.backgroundScope,
            minIntervalMs = interval,
            timeSource = scope.testScheduler.timeSource,
            maxPending = 4,
        ) { frame ->
            writes += Write(scope.currentTime, frame)
            outcomes.removeFirstOrNull() ?: WriteOutcome.Sent
        }

        fun send(command: Command, done: CompletableDeferred<Boolean>? = null) =
            queue.enqueue(command, DomProtocol.encode(command), done)

        fun sentCommands(): List<String> = writes.map { describe(it.frame) }
    }

    @Test
    fun burstOfColoursSendsOnlyTheNewest() = runTest {
        val h = Harness(this)
        h.send(color(1))
        h.send(color(2))
        h.send(color(3))
        settle()
        assertEquals(listOf("color 3"), h.sentCommands())
    }

    @Test
    fun dragIsThrottledAndTheFinalValueIsAlwaysSent() = runTest {
        val h = Harness(this)
        // A pointer event every 8 ms for 400 ms, like a 120 Hz drag.
        for (i in 1..50) {
            h.send(color(i))
            delay(8)
        }
        settle()
        val times = h.writes.map { it.atMs }
        times.zipWithNext().forEach { (a, b) -> assertTrue("frames $a and $b too close", b - a >= 35) }
        assertEquals("color 50", h.sentCommands().last())
        assertTrue("expected about 12 frames, got ${times.size}", times.size in 10..14)
    }

    @Test
    fun firstFrameAfterIdleIsNotDelayed() = runTest {
        val h = Harness(this)
        delay(1_000)
        h.send(Command.Power(true))
        settle()
        assertEquals(1_000L, h.writes.single().atMs)
    }

    @Test
    fun discreteCommandsKeepOrderAndAreNeverMerged() = runTest {
        val h = Harness(this)
        h.send(Command.Power(true))
        h.send(Command.Power(false))
        h.send(Command.Power(true))
        settle()
        assertEquals(listOf("power on", "power off", "power on"), h.sentCommands())
    }

    @Test
    fun newerDragValueMovesBehindALaterTap() = runTest {
        val h = Harness(this)
        h.send(color(1))
        h.send(Command.Power(false))
        h.send(color(2))
        settle()
        assertEquals(listOf("power off", "color 2"), h.sentCommands())
    }

    @Test
    fun differentDragTypesDoNotReplaceEachOther() = runTest {
        val h = Harness(this)
        h.send(color(1))
        h.send(Command.Brightness(40))
        h.send(color(2))
        h.send(Command.Brightness(60))
        settle()
        assertEquals(listOf("color 2", "brightness 60"), h.sentCommands())
    }

    @Test
    fun effectWithSpeedSendsBothFramesSpaced() = runTest {
        val h = Harness(this)
        h.send(Command.Effect(3, speed = 50))
        settle()
        assertEquals(2, h.writes.size)
        assertEquals(35L, h.writes[1].atMs - h.writes[0].atMs)
    }

    @Test
    fun busyWriteIsRetried() = runTest {
        val h = Harness(this, outcomes = ArrayDeque(listOf(WriteOutcome.Busy, WriteOutcome.Busy)))
        val done = CompletableDeferred<Boolean>()
        h.send(Command.Power(true), done)
        settle()
        assertEquals(3, h.writes.size)
        assertTrue(done.await())
    }

    @Test
    fun repeatedFailureDropsTheCommand() = runTest {
        val h = Harness(this, outcomes = ArrayDeque(List(10) { WriteOutcome.Failed }))
        val done = CompletableDeferred<Boolean>()
        h.send(Command.Power(true), done)
        settle()
        assertFalse(done.await())
        assertEquals(BleTuning.MAX_FAILED_RETRIES + 1, h.writes.size)
    }

    @Test
    fun replacedValueCompletesWhenTheNewerOneIsSent() = runTest {
        val h = Harness(this)
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        h.send(color(1), first)
        h.send(color(2), second)
        settle()
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf("color 2"), h.sentCommands())
    }

    @Test
    fun overflowDropsOldestTapAndKeepsDragValues() = runTest {
        val h = Harness(this)
        val oldest = CompletableDeferred<Boolean>()
        h.send(Command.Power(true), oldest)
        h.send(color(1))
        h.send(Command.Power(false))
        h.send(Command.Brightness(10))
        h.send(Command.Speed(20))
        settle()
        assertFalse(oldest.await())
        assertEquals(listOf("color 1", "power off", "brightness 10", "speed 20"), h.sentCommands())
    }

    @Test
    fun clearDropsPendingWork() = runTest {
        val h = Harness(this)
        val done = CompletableDeferred<Boolean>()
        h.send(Command.Power(true), done)
        h.queue.clear()
        settle()
        assertFalse(done.await())
        assertTrue(h.writes.isEmpty())
        assertTrue(h.queue.idle.value)
    }

    @Test
    fun clearAbortsACommandWaitingForTheLink() = runTest {
        val link = CompletableDeferred<Unit>()
        val writes = mutableListOf<ByteArray>()
        val queue = WriteQueue(scope = backgroundScope, timeSource = testScheduler.timeSource) { frame ->
            link.await()
            writes += frame
            WriteOutcome.Sent
        }
        val done = CompletableDeferred<Boolean>()
        queue.enqueue(Command.Power(true), DomProtocol.encode(Command.Power(true)), done)
        settle()
        queue.clear()
        settle()
        assertFalse(done.await())
        link.complete(Unit)
        settle()
        assertTrue(writes.isEmpty())
        assertTrue(queue.idle.value)
    }

    // The writer runs in backgroundScope, which advanceUntilIdle does not wait for.
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun color(n: Int) = Command.Color(Rgb(n))

    companion object {
        private fun describe(frame: ByteArray): String {
            val b = frame.map { it.toInt() and 0xFF }
            return when (b[2]) {
                0x04 -> if (b[3] == 1) "power on" else "power off"
                0x05 -> "color ${(b[4] shl 16) or (b[5] shl 8) or b[6]}"
                0x01 -> "brightness ${b[3]}"
                0x02 -> "speed ${b[3]}"
                0x03 -> "effect ${b[3] - 0x80}"
                else -> "other"
            }
        }
    }
}
