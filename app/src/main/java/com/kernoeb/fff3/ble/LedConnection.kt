package com.kernoeb.fff3.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.LedFamily
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The link to one strip. It connects when a command arrives or while [setPinned] is true,
 * reconnects with backoff when the link drops, and closes after [BleTuning.IDLE_DISCONNECT_MS]
 * without commands once unpinned. Use it through [LedController].
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is guarded by BluetoothEnvironment.status or a SecurityException catch.
class LedConnection internal constructor(
    val address: String,
    val family: LedFamily,
    private val context: Context,
    private val environment: BluetoothEnvironment,
    parentScope: CoroutineScope,
    latency: WriteLatency? = null,
    private val frameWrites: FrameWrites? = null,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val link = MutableStateFlow<GattLink?>(null)
    private val queue = WriteQueue(scope, onSent = { latency?.record(it) }) { frame -> link.filterNotNull().first().write(frame) }
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    private val lock = Any()
    private var linkJob: Job? = null
    private var idleJob: Job? = null
    @Volatile
    private var lastUse = TimeSource.Monotonic.markNow()
    @Volatile
    private var pinned = false

    /** Queues [command]. Returns false, and completes [done] with false, if this family cannot do it. */
    fun send(command: Command, done: CompletableDeferred<Boolean>? = null): Boolean {
        val frames = family.encode(command)
        if (frames.isEmpty()) {
            done?.complete(false)
            return false
        }
        synchronized(lock) {
            queue.enqueue(command, frames, done)
            startLink()
            scheduleIdleStop()
        }
        return true
    }

    /** While pinned, the link stays open even with no commands. */
    fun setPinned(value: Boolean) = synchronized(lock) {
        pinned = value
        if (value) {
            idleJob?.cancel()
            idleJob = null
            startLink()
        } else {
            scheduleIdleStop()
        }
    }

    /** Skips the current backoff wait. */
    fun retryNow() {
        retrySignal.trySend(Unit)
    }

    /** Stops for good. The connection cannot be used after this. */
    fun close() {
        queue.clear()
        scope.cancel()
        _state.value = ConnectionState.Idle
    }

    private fun startLink() {
        if (linkJob?.isActive != true) linkJob = scope.launch { runLink() }
    }

    /** Must hold [lock]. One timer per idle period: a drag only moves [lastUse]. */
    private fun scheduleIdleStop() {
        if (pinned) return
        lastUse = TimeSource.Monotonic.markNow()
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            val idleFor = BleTuning.IDLE_DISCONNECT_MS.milliseconds
            while (true) {
                val left = idleFor - lastUse.elapsedNow()
                if (left.isPositive()) {
                    delay(left)
                    continue
                }
                queue.idle.first { it }
                synchronized(lock) {
                    if (!pinned && lastUse.elapsedNow() >= idleFor) {
                        linkJob?.cancel()
                        linkJob = null
                        idleJob = null
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun runLink() {
        var failures = 0
        try {
            while (true) {
                val status = environment.status.value
                if (status != BluetoothStatus.Ready) {
                    _state.value = status.toConnectionState()
                    // Nothing holds this link open, so nobody is waiting for it: drop the commands.
                    if (!pinned) return queue.clear()
                    environment.status.first { it == BluetoothStatus.Ready }
                    failures = 0
                    continue
                }
                _state.value = ConnectionState.Connecting(failures + 1)
                val wasConnected = connectOnce()
                // A retry asked while connected is stale: keep the backoff after a drop.
                if (wasConnected) retrySignal.tryReceive()
                failures = if (wasConnected) 0 else failures + 1
                if (!pinned && failures >= BleTuning.UNPINNED_MAX_FAILURES) return queue.clear()
                val wait = backoff(failures)
                _state.value = ConnectionState.WaitingToRetry(failures, wait)
                withTimeoutOrNull(wait) { retrySignal.receive() }
            }
        } finally {
            _state.value = ConnectionState.Idle
        }
    }

    /** Connects, holds the link until it drops, then closes it. Returns true if it got connected. */
    private suspend fun connectOnce(): Boolean {
        val adapter = environment.adapter ?: return false
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid address $address", e)
            return false
        }
        val events = Channel<GattEvent>(Channel.UNLIMITED)
        val acks = Channel<Int>(Channel.CONFLATED)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(GattEvent.StateChanged(status, newState))
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                events.trySend(GattEvent.ServicesDiscovered(status))
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                acks.trySend(status)
            }
        }

        val gatt = try {
            openGatt(device, callback)
        } catch (e: SecurityException) {
            environment.refresh()
            return false
        } ?: return false

        try {
            val up = withTimeoutOrNull(BleTuning.CONNECT_TIMEOUT_MS) {
                val change = events.receiveUntil<GattEvent.StateChanged>()
                change.status == BluetoothGatt.GATT_SUCCESS && change.newState == BluetoothProfile.STATE_CONNECTED
            } ?: false
            if (!up) return false

            if (!gatt.discoverServices()) return false
            val discovered = withTimeoutOrNull(BleTuning.DISCOVERY_TIMEOUT_MS) { awaitDiscovery(events) } ?: false
            if (!discovered) return false

            val profile = family.protocol.gatt
            val characteristic = gatt.getService(profile.service)?.getCharacteristic(profile.writeCharacteristic)
            if (characteristic == null) {
                Log.w(TAG, "$address has no ${profile.writeCharacteristic} in ${profile.service}")
                return false
            }
            val canSkipResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val writeType = if (profile.writeWithoutResponse && canSkipResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            link.value = GattLink(gatt, characteristic, writeType, acks, scope) { frame, at ->
                frameWrites?.listener?.onWrite(address, frame, at)
            }
            _state.value = ConnectionState.Connected
            awaitLinkLost(events)
            return true
        } finally {
            link.value?.close()
            link.value = null
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot close link to $address", e)
            }
        }
    }

    private fun openGatt(device: BluetoothDevice, callback: BluetoothGattCallback): BluetoothGatt? {
        if (Build.VERSION.SDK_INT >= 37) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .setAutoConnectEnabled(false)
                .setAutomaticMtuEnabled(false)
                .build()
            return device.connectGatt(settings, context.mainExecutor, callback)
        }
        @Suppress("DEPRECATION") // Replaced in API 37 by the settings overload above.
        return device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private suspend fun awaitDiscovery(events: Channel<GattEvent>): Boolean {
        while (true) {
            when (val event = events.receive()) {
                is GattEvent.ServicesDiscovered -> return event.status == BluetoothGatt.GATT_SUCCESS
                is GattEvent.StateChanged -> if (event.newState == BluetoothProfile.STATE_DISCONNECTED) return false
            }
        }
    }

    private suspend fun awaitLinkLost(events: Channel<GattEvent>) = coroutineScope {
        val bluetoothGone = launch { environment.status.first { it != BluetoothStatus.Ready } }
        val dropped = launch {
            while (true) {
                val event = events.receive()
                if (event is GattEvent.StateChanged && event.newState == BluetoothProfile.STATE_DISCONNECTED) break
            }
        }
        select {
            bluetoothGone.onJoin {}
            dropped.onJoin {}
        }
        bluetoothGone.cancel()
        dropped.cancel()
    }

    private fun backoff(failures: Int): Long {
        if (failures == 0) return 500
        val base = (BleTuning.RECONNECT_FIRST_DELAY_MS shl (failures - 1).coerceAtMost(10))
            .coerceAtMost(BleTuning.RECONNECT_MAX_DELAY_MS)
        return base + Random.nextLong(base / 4 + 1)
    }

    private sealed interface GattEvent {
        data class StateChanged(val status: Int, val newState: Int) : GattEvent
        data class ServicesDiscovered(val status: Int) : GattEvent
    }

    private suspend inline fun <reified T : GattEvent> Channel<GattEvent>.receiveUntil(): T {
        while (true) {
            val event = receive()
            if (event is T) return event
        }
    }

    private companion object {
        const val TAG = "LedConnection"
    }
}

/** An open, discovered link. Writes one frame at a time; the [WriteQueue] guarantees that. */
@SuppressLint("MissingPermission")
private class GattLink(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    private val writeType: Int,
    private val acks: Channel<Int>,
    private val scope: CoroutineScope,
    private val onWritten: (ByteArray, Long) -> Unit,
) {
    @Volatile
    private var lastWrite = TimeSource.Monotonic.markNow()
    private var priorityJob: Job? = null

    suspend fun write(frame: ByteArray): WriteOutcome {
        raisePriority()
        // Drop late acks from earlier writes.
        while (acks.tryReceive().isSuccess) continue
        val handedAt = SystemClock.elapsedRealtimeNanos()
        val status = try {
            gatt.writeCharacteristic(characteristic, frame, writeType)
        } catch (e: SecurityException) {
            return WriteOutcome.Failed
        }
        return when (status) {
            BluetoothStatusCodes.SUCCESS -> {
                onWritten(frame, handedAt)
                val ack = withTimeoutOrNull(BleTuning.WRITE_ACK_TIMEOUT_MS) { acks.receive() }
                if (ack == null || ack == BluetoothGatt.GATT_SUCCESS) WriteOutcome.Sent else WriteOutcome.Failed
            }
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> WriteOutcome.Busy
            else -> WriteOutcome.Failed
        }
    }

    fun close() {
        priorityJob?.cancel()
    }

    /** High priority (shorter connection interval) while frames flow, balanced once they stop. */
    private fun raisePriority() {
        lastWrite = TimeSource.Monotonic.markNow()
        if (priorityJob?.isActive == true) return
        requestPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        priorityJob = scope.launch {
            while (true) {
                val left = BleTuning.HIGH_PRIORITY_HOLD_MS.milliseconds - lastWrite.elapsedNow()
                if (!left.isPositive()) break
                delay(left)
            }
            requestPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        }
    }

    private fun requestPriority(priority: Int) {
        try {
            gatt.requestConnectionPriority(priority)
        } catch (e: SecurityException) {
            Log.w("LedConnection", "Cannot change connection priority", e)
        }
    }
}
