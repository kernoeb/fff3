package com.kernoeb.fff3.ui.control

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.ConnectionState
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.ble.isConnected
import com.kernoeb.fff3.ble.of
import com.kernoeb.fff3.data.AppData
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

@Immutable
data class ControlMember(val address: String, val name: String, val connection: ConnectionState)

@Immutable
data class ControlUiState(
    val loaded: Boolean = false,
    /** False when the device or group was removed. */
    val exists: Boolean = true,
    val title: String = "",
    val isGroup: Boolean = false,
    val members: List<ControlMember> = emptyList(),
    /** State of the first member, as the strips cannot report theirs. */
    val last: LedState = LedState(),
) {
    val connectedCount: Int get() = members.count { it.connection.isConnected }
}

/** Where the sliders start, from the last state we sent. The screen owns the positions while dragging. */
@Immutable
data class ControlInputs(val hue: Float, val brightness: Float)

class ControlViewModel(
    private val target: ControlTarget,
    repository: LedRepository,
    private val controller: LedController,
    deviceStates: DeviceStateStore,
) : ViewModel() {

    val state: StateFlow<ControlUiState> = combine(
        repository.snapshot,
        controller.connectionStates,
        deviceStates.states,
    ) { data, connections, states ->
        if (data == null) return@combine ControlUiState()
        val (title, addresses) = resolve(data) ?: return@combine ControlUiState(loaded = true, exists = false)
        val members = addresses.mapNotNull { address ->
            data.devices.firstOrNull { it.address == address }?.let {
                ControlMember(address, it.displayName, connections.of(address))
            }
        }
        ControlUiState(
            loaded = true,
            title = title,
            isGroup = target is ControlTarget.Group,
            members = members,
            last = members.firstOrNull()?.let { states[it.address] } ?: LedState(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControlUiState())

    /** Slider positions from the latest state we sent. Waits until the saved state is read. */
    suspend fun currentInputs(): ControlInputs {
        val last = state.first { it.loaded }.last
        val hsv = FloatArray(3)
        last.color?.let { android.graphics.Color.RGBToHSV(it.red, it.green, it.blue, hsv) }
        return ControlInputs(hue = hsv[0], brightness = (last.brightness ?: 100).toFloat())
    }

    fun setPower(on: Boolean) = controller.send(target, Command.Power(on))

    /** Called on every drag event: the controller coalesces them. */
    fun setHue(hue: Float) = controller.send(target, Command.Color(Rgb.fromHsv(hue)))

    fun setBrightness(percent: Float) = controller.send(target, Command.Brightness(percent.toInt()))

    fun setColor(rgb: Rgb) = controller.send(target, Command.Color(rgb))

    private fun resolve(data: AppData): Pair<String, List<String>>? = when (target) {
        is ControlTarget.Device -> data.devices.firstOrNull { it.address == target.address }
            ?.let { it.displayName to listOf(it.address) }
        is ControlTarget.Group -> data.groups.firstOrNull { it.id == target.id }
            ?.let { it.name to it.memberAddresses }
    }
}
