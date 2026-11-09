package com.kernoeb.fff3.ble

import com.kernoeb.fff3.protocol.Command
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClockSyncerTest {

    private class FakeController : LedController {
        val states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
        val sent = mutableListOf<Pair<ControlTarget, List<Command>>>()
        override val connectionStates: StateFlow<Map<String, ConnectionState>> = states
        override fun send(target: ControlTarget, command: Command) = send(target, listOf(command))
        override fun send(target: ControlTarget, commands: List<Command>) { sent += target to commands }
        override suspend fun sendAndAwait(target: ControlTarget, commands: List<Command>, timeoutMillis: Long): Boolean {
            sent += target to commands
            return true
        }
        override fun openSession() = LedSession {}
        override fun retryNow() = Unit
    }

    @Test
    fun syncsOncePerConnection() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        val time = LocalDateTime.of(2026, 11, 8, 21, 4, 9) // a Sunday
        val syncer = ClockSyncer(backgroundScope, now = { time }, clockMillis = { 42L })
        syncer.watch(controller)

        controller.states.value = mapOf("A" to ConnectionState.Connecting(1))
        controller.states.value = mapOf("A" to ConnectionState.Connected)
        controller.states.value = mapOf("A" to ConnectionState.Connected, "B" to ConnectionState.Idle)
        controller.states.value = mapOf("A" to ConnectionState.Idle)
        controller.states.value = mapOf("A" to ConnectionState.Connected)

        val clock = Command.ClockSync(21, 4, 9, DayOfWeek.SUNDAY)
        assertEquals(List(2) { ControlTarget.Device("A") to listOf<Command>(clock) }, controller.sent)
        assertEquals(mapOf("A" to 42L), syncer.syncedAt.value)
    }
}
