package com.kernoeb.fff3.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.BluetoothEnvironment
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.ConnectionState
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.ble.isConnected
import com.kernoeb.fff3.ble.of
import com.kernoeb.fff3.data.DeviceGroup
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.data.StripSettingsStore
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.system.commandsFromOutside
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
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
    val group: DeviceGroup,
    val members: List<KnownDevice>,
    val summary: MemberSummary,
)

@Immutable
data class HomeUiState(
    val loaded: Boolean = false,
    val devices: List<DeviceItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val favourites: List<Favourite> = emptyList(),
    /** The built-in "All strips" card. Null with fewer than 2 strips, or when a group holds every strip. */
    val all: MemberSummary? = null,
    /** Groups that hold exactly every strip. Home shows them in place of the "All strips" card. */
    val wholeGroupIds: Set<String> = emptySet(),
    val bluetooth: BluetoothStatus = BluetoothStatus.Ready,
) {
    fun nameOf(target: HomeTarget): String? = when (target) {
        HomeTarget.AllStrips -> null
        is HomeTarget.One -> when (val t = target.target) {
            is ControlTarget.Device -> devices.firstOrNull { it.device.address == t.address }?.device?.displayName
            is ControlTarget.Group -> groups.firstOrNull { it.group.id == t.id }?.group?.name
        }
    }
}

/** One-off messages for a snackbar. */
sealed interface HomeEvent {
    data class Applied(val favourite: String, val count: Int) : HomeEvent
    data class NothingToApply(val favourite: String) : HomeEvent
    data class Forgotten(val device: KnownDevice, val groups: List<DeviceGroup>) : HomeEvent
}

class HomeViewModel(
    private val repository: LedRepository,
    private val controller: LedController,
    private val deviceStates: DeviceStateStore,
    private val bluetooth: BluetoothEnvironment,
    private val stripSettings: StripSettingsStore,
) : ViewModel() {

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

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
            GroupItem(group, members.map { it.device }, summaryOf(members))
        }
        val wholeSet = wholeSetOf(data.devices.map { it.address }, data.groups)
        HomeUiState(
            loaded = true,
            devices = devices,
            groups = groups,
            favourites = data.favourites,
            all = if (wholeSet.showBuiltIn) summaryOf(devices) else null,
            wholeGroupIds = wholeSet.groupIds,
            bluetooth = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun summaryOf(items: List<DeviceItem>) =
        summarise(items.map { it.state }, items.map { it.connection.isConnected })

    fun setPower(target: HomeTarget, on: Boolean) = sendTo(target, Command.Power(on))

    /** Called on every drag event; the controller keeps only the newest value. */
    fun setBrightness(target: HomeTarget, percent: Int) = sendTo(target, Command.Brightness(percent))

    private fun sendTo(target: HomeTarget, command: Command) {
        when (target) {
            is HomeTarget.One -> controller.send(target.target, command)
            HomeTarget.AllStrips -> addressesOf(target, currentDevices(), emptyList())
                .forEach { controller.send(ControlTarget.Device(it), command) }
        }
    }

    fun applyFavourite(favourite: Favourite, target: HomeTarget) {
        val data = repository.snapshot.value ?: return
        val recipients = favouriteRecipients(favourite, target, data.devices, data.groups)
        val commands = favourite.commandsFromOutside()
        recipients.forEach { controller.send(ControlTarget.Device(it), commands) }
        _events.trySend(
            if (recipients.isEmpty()) HomeEvent.NothingToApply(favourite.name)
            else HomeEvent.Applied(favourite.name, recipients.size),
        )
    }

    fun renameFavourite(favourite: Favourite, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.saveFavourite(favourite.copy(name = name.trim())) }
    }

    fun deleteFavourite(id: String) {
        viewModelScope.launch { repository.deleteFavourite(id) }
    }

    fun reorderFavourites(ids: List<String>) {
        viewModelScope.launch { repository.reorderFavourites(ids) }
    }

    fun rename(address: String, label: String) {
        viewModelScope.launch { repository.renameDevice(address, label.takeIf { it.isNotBlank() }) }
    }

    /**
     * Forgets the strip at once but keeps the system association until the snackbar closes,
     * so Undo can bring it back as it was.
     */
    fun forget(device: KnownDevice) {
        val groups = repository.snapshot.value?.groups.orEmpty().filter { device.address in it.memberAddresses }
        viewModelScope.launch {
            repository.removeDevice(device.address)
            _events.send(HomeEvent.Forgotten(device, groups))
        }
    }

    fun undoForget(event: HomeEvent.Forgotten) {
        viewModelScope.launch {
            repository.saveDevice(event.device)
            val current = repository.snapshot.value?.groups.orEmpty().associateBy { it.id }
            for (group in event.groups) {
                val now = current[group.id] ?: continue
                if (event.device.address !in now.memberAddresses) {
                    repository.saveGroup(now.copy(memberAddresses = now.memberAddresses + event.device.address))
                }
            }
            controller.retryNow()
        }
    }

    fun confirmForget(event: HomeEvent.Forgotten) {
        deviceStates.forget(event.device.address)
        viewModelScope.launch { stripSettings.forget(event.device.address) }
    }

    fun saveGroup(id: String?, name: String, memberAddresses: List<String>) {
        viewModelScope.launch {
            if (id == null) {
                repository.createGroup(name, memberAddresses)
            } else {
                val existing = repository.snapshot.value?.groups?.firstOrNull { it.id == id } ?: return@launch
                repository.saveGroup(existing.copy(name = name.trim(), memberAddresses = memberAddresses.distinct()))
            }
        }
    }

    /** Creates the "All" group the Home screen suggests once you have 2 strips. */
    fun createAllGroup(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val group = repository.createGroup(ALL_GROUP_NAME, currentDevices().map { it.address })
            onCreated(group.id)
        }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch { repository.deleteGroup(id) }
    }

    /** Call after a permission request or the Bluetooth switch returns. */
    fun onBluetoothSettingsChanged() {
        bluetooth.refresh()
        controller.retryNow()
    }

    private fun currentDevices(): List<KnownDevice> = repository.snapshot.value?.devices.orEmpty()
}
