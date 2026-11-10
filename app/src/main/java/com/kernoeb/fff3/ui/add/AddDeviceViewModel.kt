package com.kernoeb.fff3.ui.add

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.BleScanner
import com.kernoeb.fff3.ble.BluetoothEnvironment
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.ble.ScanFailedException
import com.kernoeb.fff3.ble.ScannedDevice
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.protocol.Command
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AddMessage {
    data class AlreadyAdded(val name: String) : AddMessage
    data object ScanPermissionDenied : AddMessage
    data object BluetoothOff : AddMessage
    data class ScanFailed(val code: Int?) : AddMessage
}

@Immutable
data class AddDeviceUiState(
    val bluetooth: BluetoothStatus = BluetoothStatus.Ready,
    val scanning: Boolean = false,
    /** True once a scan has run, so an empty list means nothing was found. */
    val scanned: Boolean = false,
    val results: List<ScannedDevice> = emptyList(),
    val known: Map<String, String> = emptyMap(),
    /** Strips added since this screen opened, newest last, with their current names. */
    val added: List<KnownDevice> = emptyList(),
    /** The strip just added, waiting for the user to name it. */
    val naming: KnownDevice? = null,
    val message: AddMessage? = null,
)

private data class LocalState(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val results: List<ScannedDevice> = emptyList(),
    val addedAddresses: List<String> = emptyList(),
    val namingAddress: String? = null,
    val message: AddMessage? = null,
)

class AddDeviceViewModel(
    private val repository: LedRepository,
    private val controller: LedController,
    private val scanner: BleScanner,
    private val bluetooth: BluetoothEnvironment,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private var scanJob: Job? = null

    /** A scan was asked for while Bluetooth was off: it starts once Bluetooth is on. */
    private var scanWhenReady = false

    init {
        viewModelScope.launch {
            bluetooth.status.collect { status ->
                if (status == BluetoothStatus.Ready && scanWhenReady) startScan()
            }
        }
    }

    val state: StateFlow<AddDeviceUiState> = combine(
        local,
        repository.snapshot.filterNotNull(),
        bluetooth.status,
    ) { s, data, status ->
        val byAddress = data.devices.associateBy { it.address }
        AddDeviceUiState(
            bluetooth = status,
            scanning = s.scanning,
            scanned = s.scanned,
            results = s.results,
            known = data.devices.associate { it.address to it.displayName },
            added = s.addedAddresses.mapNotNull(byAddress::get),
            naming = s.namingAddress?.let(byAddress::get),
            message = s.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddDeviceUiState())

    /** Saves the strip at once with a numbered name, so it works even if the user skips naming. */
    fun add(scanned: ScannedDevice) {
        // A running scan slows the new connection and makes the "Flash it" blinks uneven.
        stopScan()
        viewModelScope.launch {
            val existing = repository.snapshot.filterNotNull().first().devices
            when (val outcome = addOutcome(scanned.address, existing)) {
                is AddOutcome.AlreadyAdded -> local.update { it.copy(message = AddMessage.AlreadyAdded(outcome.name)) }
                AddOutcome.New -> {
                    val device = KnownDevice(
                        address = scanned.address.uppercase(),
                        advertisedName = scanned.name,
                        family = scanned.family,
                        label = suggestStripName(existing),
                    )
                    repository.saveDevice(device)
                    controller.retryNow()
                    local.update {
                        it.copy(
                            addedAddresses = it.addedAddresses + device.address,
                            namingAddress = device.address,
                            message = null,
                        )
                    }
                }
            }
        }
    }

    fun rename(address: String, name: String) {
        local.update { it.copy(namingAddress = null) }
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameDevice(address, name) }
    }

    fun renameRequest(device: KnownDevice) = local.update { it.copy(namingAddress = device.address) }

    fun skipNaming() = local.update { it.copy(namingAddress = null) }

    /** Blinks the strip twice so you can tell which one you are naming. Ends with it on. */
    fun identify(address: String) {
        val target = ControlTarget.Device(address)
        viewModelScope.launch {
            try {
                // The first command also waits for the link to open, so the blinks that follow are evenly timed.
                controller.sendAndAwait(target, listOf(Command.Power(false)), timeoutMillis = IDENTIFY_CONNECT_MS, record = false)
                repeat(2) {
                    delay(BLINK_MS)
                    controller.send(target, Command.Power(true), record = false)
                    delay(BLINK_MS)
                    controller.send(target, Command.Power(false), record = false)
                }
                delay(BLINK_MS)
            } finally {
                // Also runs when the screen closes mid-blink, so the strip never stays off while shown as on.
                controller.send(target, Command.Power(true))
            }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    fun onScanPermissionResult(granted: Boolean) {
        bluetooth.refresh()
        controller.retryNow()
        if (granted) startScan() else local.update { it.copy(message = AddMessage.ScanPermissionDenied) }
    }

    fun onBluetoothSettingsChanged() {
        bluetooth.refresh()
        controller.retryNow()
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return
        // No message here: the Bluetooth card at the top of the screen already says it is off.
        if (bluetooth.status.value == BluetoothStatus.Off) {
            scanWhenReady = true
            return
        }
        scanWhenReady = false
        local.update { it.copy(scanning = true, scanned = true, results = emptyList(), message = null) }
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(SCAN_DURATION_MS) {
                scanner.scan()
                    .catch { e ->
                        val message = when (e) {
                            is SecurityException -> AddMessage.ScanPermissionDenied
                            is ScanFailedException -> AddMessage.ScanFailed(e.errorCode)
                            is IllegalStateException -> AddMessage.BluetoothOff
                            else -> AddMessage.ScanFailed(null)
                        }
                        // The scan never ran, so do not claim that nothing was found.
                        local.update { it.copy(message = message, scanned = false) }
                    }
                    .collect { found -> local.update { it.copy(results = found) } }
            }
            local.update { it.copy(scanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        local.update { it.copy(scanning = false) }
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
        const val IDENTIFY_CONNECT_MS = 8_000L
        const val BLINK_MS = 350L
    }
}
