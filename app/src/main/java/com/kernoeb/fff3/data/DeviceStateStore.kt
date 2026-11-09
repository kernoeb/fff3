package com.kernoeb.fff3.data

import android.util.Log
import androidx.datastore.core.DataStore
import com.kernoeb.fff3.protocol.Command
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The last state fff3 sent to each strip, so screens can show it.
 * Updated in memory on every command, saved to disk soon after.
 */
interface DeviceStateStore {

    /** Keyed by device address. A device with no entry has an unknown state. */
    val states: StateFlow<Map<String, LedState>>

    /** Address of the device that received the latest command, for the accent colour. */
    val lastControlled: StateFlow<String?>

    /** Commands that do not change [LedState], such as a clock sync, are ignored. */
    fun record(address: String, command: Command)
    fun forget(address: String)

    /** Writes pending changes now, for example when the app goes to the background. */
    fun flush() = Unit
}

class PersistentDeviceStateStore(
    private val store: DataStore<Map<String, LedState>>,
    private val scope: CoroutineScope,
) : DeviceStateStore {

    private val _states = MutableStateFlow<Map<String, LedState>>(emptyMap())
    override val states: StateFlow<Map<String, LedState>> = _states.asStateFlow()

    private val _lastControlled = MutableStateFlow<String?>(null)
    override val lastControlled: StateFlow<String?> = _lastControlled.asStateFlow()

    private val lock = Any()

    /** Changes made before the file was read, replayed on top of it. Null once read. */
    private var early: MutableList<(Map<String, LedState>) -> Map<String, LedState>>? = mutableListOf()

    private val writing = Mutex()
    private var written: Map<String, LedState>? = null

    init {
        scope.launch {
            val saved = store.data.first()
            synchronized(lock) {
                // Replaying keeps each saved field that the early commands did not set.
                val merged = early.orEmpty().fold(saved) { states, change -> change(states) }
                early = null
                written = saved
                _states.value = merged
            }
            // Writes the first change at once, then at most once per interval while a drag goes on.
            _states.collect {
                save()
                delay(SAVE_INTERVAL_MS)
            }
        }
    }

    override fun record(address: String, command: Command) {
        if (!command.changesState) return
        change { all -> all + (address to (all[address] ?: LedState()).after(command)) }
        _lastControlled.value = address
    }

    override fun forget(address: String) {
        change { it - address }
        if (_lastControlled.value == address) _lastControlled.value = null
    }

    override fun flush() {
        scope.launch { save() }
    }

    private fun change(transform: (Map<String, LedState>) -> Map<String, LedState>) = synchronized(lock) {
        early?.add(transform)
        _states.update(transform)
    }

    private suspend fun save() = writing.withLock {
        val current = _states.value
        val last = synchronized(lock) { written } ?: return@withLock
        if (current == last) return@withLock
        try {
            store.updateData { current }
        } catch (e: IOException) {
            // An uncaught error here would crash the app. The next change tries again.
            Log.e("fff3", "Cannot save the strip states", e)
            return@withLock
        }
        synchronized(lock) { written = current }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 300L
    }
}
