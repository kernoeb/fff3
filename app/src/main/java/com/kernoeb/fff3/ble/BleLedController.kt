package com.kernoeb.fff3.ble

import android.content.Context
import com.kernoeb.fff3.data.AppData
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.protocol.Command
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** [LedController] over raw `android.bluetooth`, one [LedConnection] per known device. */
class BleLedController(
    context: Context,
    private val environment: BluetoothEnvironment,
    private val repository: LedRepository,
    private val deviceStates: DeviceStateStore,
    private val scope: CoroutineScope,
    /** Write times of every strip, for the music beat clock. */
    val writeLatency: WriteLatency = WriteLatency(),
) : LedController {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val connections = mutableMapOf<String, LedConnection>()
    private var sessions = 0
    private var loaded = false
    private val sentBeforeLoad = mutableListOf<Pair<ControlTarget, List<Command>>>()
    private var statesJob: Job? = null
    private var statesStale = false

    private val _connectionStates = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    override val connectionStates: StateFlow<Map<String, ConnectionState>> = _connectionStates.asStateFlow()

    init {
        scope.launch {
            repository.snapshot.filterNotNull().collect(::onData)
        }
    }

    override fun send(target: ControlTarget, command: Command) = send(target, listOf(command))

    override fun send(target: ControlTarget, commands: List<Command>) {
        synchronized(lock) {
            val data = repository.snapshot.value
            if (!loaded || data == null) {
                sentBeforeLoad += target to commands
                return
            }
            dispatch(data, target, commands, waiters = null)
        }
    }

    override suspend fun sendAndAwait(target: ControlTarget, commands: List<Command>, timeoutMillis: Long): Boolean {
        val data = repository.snapshot.filterNotNull().first()
        val waiters = mutableListOf<CompletableDeferred<Boolean>>()
        synchronized(lock) {
            // Send earlier buffered commands first, so they cannot overtake these ones.
            if (!loaded) onData(data)
            dispatch(data, target, commands, waiters)
        }
        if (waiters.isEmpty()) return false
        return withTimeoutOrNull(timeoutMillis) { waiters.awaitAll().all { it } } ?: false
    }

    override fun openSession(): LedSession {
        synchronized(lock) {
            if (sessions++ == 0) connections.values.forEach { it.setPinned(true) }
        }
        val closed = AtomicBoolean(false)
        return LedSession {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) {
                    if (--sessions == 0) connections.values.forEach { it.setPinned(false) }
                }
            }
        }
    }

    override fun retryNow() {
        environment.refresh()
        synchronized(lock) { connections.values.forEach { it.retryNow() } }
    }

    private fun onData(data: AppData) = synchronized(lock) {
        syncConnections(data.devices)
        if (!loaded) {
            loaded = true
            sentBeforeLoad.forEach { (target, commands) -> dispatch(data, target, commands, waiters = null) }
            sentBeforeLoad.clear()
        }
    }

    /** Must hold [lock]. */
    private fun dispatch(
        data: AppData,
        target: ControlTarget,
        commands: List<Command>,
        waiters: MutableList<CompletableDeferred<Boolean>>?,
    ) {
        val members = when (target) {
            is ControlTarget.Device -> data.devices.filter { it.address == target.address }
            is ControlTarget.Group -> {
                val addresses = data.groups.firstOrNull { it.id == target.id }?.memberAddresses.orEmpty()
                data.devices.filter { it.address in addresses }
            }
        }
        for (device in members) {
            val connection = connectionFor(device)
            for (command in commands) {
                val done = waiters?.let { CompletableDeferred<Boolean>().also(it::add) }
                if (connection.send(command, done)) deviceStates.record(device.address, command)
            }
        }
        if (statesStale) watchStates()
    }

    /** Must hold [lock]. Call [watchStates] after if [statesStale] is set. */
    private fun connectionFor(device: KnownDevice): LedConnection {
        val existing = connections[device.address]
        if (existing != null && existing.family == device.family) return existing
        existing?.close()
        val connection = LedConnection(device.address, device.family, appContext, environment, scope, writeLatency)
        if (sessions > 0) connection.setPinned(true)
        connections[device.address] = connection
        statesStale = true
        return connection
    }

    /** Must hold [lock]. */
    private fun syncConnections(devices: List<KnownDevice>) {
        val wanted = devices.associateBy { it.address }
        val removed = connections.keys - wanted.keys
        for (address in removed) {
            connections.remove(address)?.close()
            deviceStates.forget(address)
            statesStale = true
        }
        devices.forEach(::connectionFor)
        if (statesStale) watchStates()
    }

    /** Must hold [lock]. */
    private fun watchStates() {
        statesStale = false
        statesJob?.cancel()
        val current = connections.values.toList()
        if (current.isEmpty()) {
            _connectionStates.value = emptyMap()
            return
        }
        statesJob = scope.launch {
            combine(current.map { c -> c.state.map { c.address to it } }) { it.toMap() }
                .collect { _connectionStates.value = it }
        }
    }
}
