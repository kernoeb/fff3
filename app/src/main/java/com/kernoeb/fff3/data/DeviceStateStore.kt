package com.kernoeb.fff3.data

import androidx.datastore.core.DataStore
import com.kernoeb.fff3.protocol.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The last state fff3 sent to each strip, so screens can show it.
 * Updated in memory on every command, saved to disk shortly after changes stop.
 */
interface DeviceStateStore {

    /** Keyed by device address. A device with no entry has an unknown state. */
    val states: StateFlow<Map<String, LedState>>

    /** Address of the device that received the latest command, for the accent colour. */
    val lastControlled: StateFlow<String?>

    fun record(address: String, command: Command)
    fun forget(address: String)
}

class PersistentDeviceStateStore(
    private val store: DataStore<Map<String, LedState>>,
    scope: CoroutineScope,
) : DeviceStateStore {

    private val _states = MutableStateFlow<Map<String, LedState>>(emptyMap())
    override val states: StateFlow<Map<String, LedState>> = _states.asStateFlow()

    private val _lastControlled = MutableStateFlow<String?>(null)
    override val lastControlled: StateFlow<String?> = _lastControlled.asStateFlow()

    init {
        scope.launch {
            val saved = store.data.first()
            // Commands sent before the file was read win over the saved values.
            _states.update { current -> saved + current }
            _states.drop(1).collectLatestDebounced(SAVE_DELAY_MS) { snapshot ->
                store.updateData { snapshot }
            }
        }
    }

    override fun record(address: String, command: Command) {
        _states.update { all -> all + (address to (all[address] ?: LedState()).after(command)) }
        _lastControlled.value = address
    }

    override fun forget(address: String) {
        _states.update { it - address }
        if (_lastControlled.value == address) _lastControlled.value = null
    }

    private companion object {
        const val SAVE_DELAY_MS = 1_000L
    }
}

/** Runs [action] with the latest value once no new value came for [delayMs]. */
private suspend fun <T> Flow<T>.collectLatestDebounced(delayMs: Long, action: suspend (T) -> Unit) =
    collectLatest { value ->
        delay(delayMs)
        action(value)
    }
