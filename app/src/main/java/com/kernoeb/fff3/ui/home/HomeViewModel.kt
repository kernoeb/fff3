package com.kernoeb.fff3.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.BluetoothEnvironment
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.CompanionPairing
import com.kernoeb.fff3.ble.ConnectionState
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.ble.isConnected
import com.kernoeb.fff3.ble.of
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class DeviceItem(
    val device: KnownDevice,
    val connection: ConnectionState,
    val state: LedState,
)

@Immutable
data class GroupItem(
    val id: String,
    val name: String,
    val memberCount: Int,
    val connectedCount: Int,
    /** True only when every member is known to be on. */
    val allOn: Boolean,
)

@Immutable
data class HomeUiState(
    val loaded: Boolean = false,
    val devices: List<DeviceItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val bluetooth: BluetoothStatus = BluetoothStatus.Ready,
)

class HomeViewModel(
    private val repository: LedRepository,
    private val controller: LedController,
    deviceStates: DeviceStateStore,
    private val bluetooth: BluetoothEnvironment,
    private val pairing: CompanionPairing,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        repository.snapshot,
        controller.connectionStates,
        deviceStates.states,
        bluetooth.status,
    ) { data, connections, states, status ->
        if (data == null) return@combine HomeUiState(bluetooth = status)
        val devices = data.devices.map { device ->
            DeviceItem(device, connections.of(device.address), states[device.address] ?: LedState())
        }
        val byAddress = devices.associateBy { it.device.address }
        val groups = data.groups.map { group ->
            val members = group.memberAddresses.mapNotNull(byAddress::get)
            GroupItem(
                id = group.id,
                name = group.name,
                memberCount = members.size,
                connectedCount = members.count { it.connection.isConnected },
                allOn = members.isNotEmpty() && members.all { it.state.power == true },
            )
        }
        HomeUiState(loaded = true, devices = devices, groups = groups, bluetooth = status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setPower(target: ControlTarget, on: Boolean) = controller.send(target, Command.Power(on))

    fun rename(address: String, label: String) {
        viewModelScope.launch { repository.renameDevice(address, label) }
    }

    fun remove(device: KnownDevice) {
        viewModelScope.launch {
            repository.removeDevice(device.address)
            device.associationId?.let(pairing::forget)
        }
    }

    fun createGroup(name: String, memberAddresses: List<String>) {
        viewModelScope.launch { repository.createGroup(name, memberAddresses) }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch { repository.deleteGroup(id) }
    }

    /** Call after a permission request or the Bluetooth switch returns. */
    fun onBluetoothSettingsChanged() {
        bluetooth.refresh()
        controller.retryNow()
    }
}
