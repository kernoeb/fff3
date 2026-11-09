package com.kernoeb.fff3.ui.add

import android.content.Intent
import android.content.IntentSender
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.BleScanner
import com.kernoeb.fff3.ble.CompanionPairing
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.ble.ScannedDevice
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class AddDeviceUiState(
    val pairingAvailable: Boolean = true,
    val scanning: Boolean = false,
    val results: List<ScannedDevice> = emptyList(),
    val knownAddresses: Set<String> = emptySet(),
    val error: String? = null,
)

class AddDeviceViewModel(
    private val repository: LedRepository,
    private val controller: LedController,
    private val pairing: CompanionPairing,
    private val scanner: BleScanner,
) : ViewModel() {

    private val scan = MutableStateFlow(AddDeviceUiState(pairingAvailable = pairing.isAvailable))
    private var scanJob: Job? = null

    val state: StateFlow<AddDeviceUiState> = combine(
        scan,
        repository.devices.map { devices -> devices.map { it.address }.toSet() },
    ) { s, known -> s.copy(knownAddresses = known) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), scan.value)

    fun startPairing(onSheet: (IntentSender) -> Unit) {
        scan.update { it.copy(error = null) }
        pairing.start(onSheet = onSheet, onError = { message -> scan.update { it.copy(error = message) } })
    }

    /** Returns true if a device was added. */
    fun onPairingResult(resultCode: Int, data: Intent?): Boolean {
        val paired = pairing.parseResult(resultCode, data) ?: return false
        save(KnownDevice(paired.address, paired.name, paired.family, associationId = paired.associationId))
        return true
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scan.update { it.copy(scanning = true, error = null) }
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(SCAN_DURATION_MS) {
                scanner.scan()
                    .catch { e -> scan.update { it.copy(error = e.message) } }
                    .collect { found -> scan.update { it.copy(results = found) } }
            }
            scan.update { it.copy(scanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scan.update { it.copy(scanning = false) }
    }

    fun add(device: ScannedDevice) {
        save(KnownDevice(device.address, device.name, device.family))
    }

    private fun save(device: KnownDevice) {
        viewModelScope.launch {
            repository.saveDevice(device)
            controller.retryNow()
        }
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
    }
}
