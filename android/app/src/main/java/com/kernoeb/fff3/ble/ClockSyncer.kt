package com.kernoeb.fff3.ble

import com.kernoeb.fff3.protocol.Command
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sends the phone's time to each strip when its link comes up, so on and off schedules fire on time.
 * [syncedAt] holds, by address, when the strip last acknowledged the time (epoch millis).
 */
class ClockSyncer(
    private val scope: CoroutineScope,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val _syncedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val syncedAt: StateFlow<Map<String, Long>> = _syncedAt.asStateFlow()

    fun watch(controller: LedController) {
        scope.launch {
            var previous = emptyMap<String, ConnectionState>()
            controller.connectionStates.collect { states ->
                for ((address, state) in states) {
                    if (state is ConnectionState.Connected && previous[address] !is ConnectionState.Connected) {
                        sync(controller, address)
                    }
                }
                previous = states
            }
        }
    }

    fun sync(controller: LedController, address: String) {
        scope.launch {
            val target = ControlTarget.Device(address)
            if (controller.sendAndAwait(target, listOf(Command.ClockSync.of(now())))) {
                _syncedAt.update { it + (address to clockMillis()) }
            }
        }
    }
}
