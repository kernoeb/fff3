package com.kernoeb.fff3.ui.add

import android.content.Intent
import android.content.IntentSender
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.BleScanner
import com.kernoeb.fff3.ble.BluetoothEnvironment
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.CompanionPairing
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.ble.ScanFailedException
import com.kernoeb.fff3.ble.ScannedDevice
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.LedFamily
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
    data class PairingFailed(val reason: String) : AddMessage
}

@Immutable
data class AddDeviceUiState(
    val pairingAvailable: Boolean = true,
    val bluetooth: BluetoothStatus = BluetoothStatus.Ready,
    val scanning: Boolean = false,
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
    val results: List<ScannedDevice> = emptyList(),
    val addedAddresses: List<String> = emptyList(),
    val namingAddress: String? = null,
    val message: AddMessage? = null,
)

class AddDeviceViewModel(
    private val repository: LedRepository,
    private val controller: LedController,
    private val pairing: CompanionPairing,
    private val scanner: BleScanner,
    private val bluetooth: BluetoothEnvironment,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private var scanJob: Job? = null

    val state: StateFlow<AddDeviceUiState> = combine(
        local,
        repository.snapshot.filterNotNull(),
        bluetooth.status,
    ) { s, data, status ->
        val byAddress = data.devices.associateBy { it.address }
        AddDeviceUiState(
            pairingAvailable = pairing.isAvailable,
            bluetooth = status,
            scanning = s.scanning,
            results = s.results,
            known = data.devices.associate { it.address to it.displayName },
            added = s.addedAddresses.mapNotNull(byAddress::get),
            naming = s.namingAddress?.let(byAddress::get),
            message = s.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddDeviceUiState(pairingAvailable = pairing.isAvailable))

    fun startPairing(onSheet: (IntentSender) -> Unit) {
        stopScan()
        local.update { it.copy(message = null) }
        pairing.start(
            onSheet = onSheet,
            onError = { reason -> local.update { it.copy(message = AddMessage.PairingFailed(reason)) } },
        )
    }

    fun onPairingResult(resultCode: Int, data: Intent?) {
        val paired = pairing.parseResult(resultCode, data) ?: return
        add(paired.address, paired.name, paired.family, paired.associationId)
    }

    fun add(device: ScannedDevice) = add(device.address, device.name, device.family, associationId = null)

    /** Saves the strip at once with a numbered name, so it works even if the user skips naming. */
    private fun add(address: String, advertisedName: String, family: LedFamily, associationId: Int?) {
        viewModelScope.launch {
            val existing = repository.snapshot.filterNotNull().first().devices
            when (val outcome = addOutcome(address, existing)) {
                is AddOutcome.AlreadyAdded -> {
                    // A fresh association from the sheet replaces any old one, so links keep working.
                    val known = existing.first { it.address.equals(address, ignoreCase = true) }
                    if (associationId != null && associationId != known.associationId) {
                        repository.saveDevice(known.copy(associationId = associationId))
                        known.associationId?.let(pairing::forget)
                    }
                    local.update { it.copy(message = AddMessage.AlreadyAdded(outcome.name)) }
                }
                AddOutcome.New -> {
                    val device = KnownDevice(
                        address = address.uppercase(),
                        advertisedName = advertisedName,
                        family = family,
                        label = suggestStripName(existing),
                        associationId = associationId,
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
            // The first command also waits for the link to open, so the blinks that follow are evenly timed.
            controller.sendAndAwait(target, listOf(Command.Power(false)), timeoutMillis = IDENTIFY_CONNECT_MS)
            repeat(2) {
                delay(BLINK_MS)
                controller.send(target, Command.Power(true))
                delay(BLINK_MS)
                controller.send(target, Command.Power(false))
            }
            delay(BLINK_MS)
            controller.send(target, Command.Power(true))
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
        if (bluetooth.status.value == BluetoothStatus.Off) {
            local.update { it.copy(message = AddMessage.BluetoothOff) }
            return
        }
        local.update { it.copy(scanning = true, results = emptyList(), message = null) }
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
                        local.update { it.copy(message = message) }
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
