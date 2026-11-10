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
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.data.shared
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.EffectInfo
import com.kernoeb.fff3.protocol.LedFamily
import com.kernoeb.fff3.protocol.Rgb
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@Immutable
data class ControlMember(val address: String, val name: String, val connection: ConnectionState)

/** What the screen can show, from the families of every member. Built with [LedFamily.supports] only. */
@Immutable
data class ControlFeatures(
    val power: Boolean = true,
    val color: Boolean = true,
    val brightness: Boolean = true,
    /** Effects every member can play, in the first member's order. */
    val effects: List<EffectInfo> = emptyList(),
    val speed: Boolean = false,
    val mic: Boolean = false,
    val micModes: List<String> = emptyList(),
    val micSensitivity: Boolean = false,
) {
    companion object {
        fun of(families: List<LedFamily>): ControlFeatures {
            if (families.isEmpty()) return ControlFeatures()
            fun any(command: Command) = families.any { it.supports(command) }
            val micFamily = families.firstOrNull { it.supports(Command.MicEnabled(true)) }
            return ControlFeatures(
                power = any(Command.Power(true)),
                color = any(Command.Color(Rgb.White)),
                brightness = any(Command.Brightness(100)),
                effects = families.first().effects.filter { effect ->
                    families.all { it.supports(Command.Effect(effect.id)) }
                },
                speed = any(Command.Speed(50)),
                mic = micFamily != null,
                micModes = micFamily?.micModes.orEmpty()
                    .filterIndexed { index, _ -> micFamily!!.supports(Command.MicMode(index)) },
                micSensitivity = any(Command.MicSensitivity(50)),
            )
        }
    }
}

/**
 * Only values that change on a tap, never on a drag: the screen recomposes when this changes,
 * and drag positions live in the composable.
 */
@Immutable
data class ControlUiState(
    val loaded: Boolean = false,
    /** False when the device or group was removed. */
    val exists: Boolean = true,
    val title: String = "",
    val isGroup: Boolean = false,
    val members: List<ControlMember> = emptyList(),
    val features: ControlFeatures = ControlFeatures(),
    /** What fff3 last sent, shared by every member. Null is unknown, or members differ. */
    val power: Boolean? = null,
    val effectId: Int? = null,
    val micEnabled: Boolean? = null,
) {
    val connectedCount: Int get() = members.count { it.connection.isConnected }
}

/** Where the controls start, from the last state we sent. The screen owns the positions while dragging. */
@Immutable
data class ControlInputs(
    val color: Rgb?,
    val brightness: Float?,
    val speed: Float,
)

class ControlViewModel(
    private val target: ControlTarget,
    private val repository: LedRepository,
    private val controller: LedController,
    private val deviceStates: DeviceStateStore,
) : ViewModel() {

    val state: StateFlow<ControlUiState> = combine(
        repository.snapshot,
        controller.connectionStates,
        deviceStates.states,
    ) { data, connections, states ->
        if (data == null) return@combine ControlUiState()
        val (title, addresses) = resolve(data) ?: return@combine ControlUiState(loaded = true, exists = false)
        val devices = addresses.mapNotNull { address -> data.devices.firstOrNull { it.address == address } }
        val last = devices.map { states[it.address] ?: LedState() }.shared()
        ControlUiState(
            loaded = true,
            title = title,
            isGroup = target is ControlTarget.Group,
            members = devices.map { ControlMember(it.address, it.displayName, connections.of(it.address)) },
            features = featuresOf(devices.map { it.family }.distinct()),
            power = last.power,
            effectId = last.effectId,
            micEnabled = last.micEnabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControlUiState())

    private var features: Pair<List<LedFamily>, ControlFeatures>? = null

    // The state flow runs on every drag event; the families almost never change.
    private fun featuresOf(families: List<LedFamily>): ControlFeatures =
        features?.takeIf { it.first == families }?.second
            ?: ControlFeatures.of(families).also { features = families to it }

    /** Control positions from the latest state we sent. Waits until the saved state is read. */
    suspend fun currentInputs(): ControlInputs {
        val members = state.first { it.loaded }.members
        val last = members.map { deviceStates.states.value[it.address] ?: LedState() }.shared()
        return ControlInputs(
            color = last.color,
            brightness = last.brightness?.toFloat(),
            speed = (last.speed ?: 50).toFloat(),
        )
    }

    fun setPower(on: Boolean) = controller.send(target, Command.Power(on))

    /** Called on every drag event: the controller coalesces them. */
    fun setColor(rgb: Rgb) = controller.send(target, Command.Color(rgb))

    fun setBrightness(percent: Float) = controller.send(target, Command.Brightness(percent.toInt()))

    fun playEffect(id: Int, speed: Float) = controller.send(target, Command.Effect(id, speed.toInt()))

    fun setSpeed(percent: Float) = controller.send(target, Command.Speed(percent.toInt()))

    fun setMicEnabled(on: Boolean) = controller.send(target, Command.MicEnabled(on))

    fun setMicMode(index: Int) = controller.send(target, listOf(Command.MicEnabled(true), Command.MicMode(index)))

    fun setMicSensitivity(percent: Float) = controller.send(target, Command.MicSensitivity(percent.toInt()))

    /** What a favourite of the current look holds. Null when nothing was sent yet. */
    fun currentLook(): Favourite? {
        val first = state.value.members.firstOrNull() ?: return null
        val last = deviceStates.states.value[first.address] ?: return null
        // Effect ids differ between families, so an effect favourite belongs to one family.
        val family = if (last.effectId == null) null
        else repository.snapshot.value?.devices?.firstOrNull { it.address == first.address }?.family
        val commands = favouriteCommands(last) ?: return null
        return Favourite(id = UUID.randomUUID().toString(), name = "", commands = commands, family = family)
    }

    fun saveFavourite(favourite: Favourite, onSaved: () -> Unit) {
        viewModelScope.launch {
            repository.saveFavourite(favourite)
            onSaved()
        }
    }

    private fun resolve(data: AppData): Pair<String, List<String>>? = when (target) {
        is ControlTarget.Device -> data.devices.firstOrNull { it.address == target.address }
            ?.let { it.displayName to listOf(it.address) }
        is ControlTarget.Group -> data.groups.firstOrNull { it.id == target.id }
            ?.let { it.name to it.memberAddresses }
    }
}

/** The commands that bring a strip back to [state]: power on, then the effect or the colour, then brightness. */
internal fun favouriteCommands(state: LedState): List<Command>? {
    val look = when {
        state.effectId != null -> Command.Effect(state.effectId, state.speed)
        state.color != null -> Command.Color(state.color)
        else -> return null
    }
    return listOfNotNull(Command.Power(true), look, state.brightness?.let(Command::Brightness))
}
