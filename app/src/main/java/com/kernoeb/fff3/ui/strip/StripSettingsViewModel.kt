package com.kernoeb.fff3.ui.strip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.ClockSyncer
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.StripSettings
import com.kernoeb.fff3.data.StripSettingsStore
import com.kernoeb.fff3.protocol.ChannelOrder
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.protocol.ScheduleAction
import java.time.DayOfWeek
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StripSettingsUiState(
    val device: KnownDevice? = null,
    val settings: StripSettings = StripSettings(),
    val clockSyncedAt: Long? = null,
    val color: Rgb? = null,
    val testing: Rgb? = null,
)

enum class StripMessage { NotReached }

class StripSettingsViewModel(
    private val address: String,
    private val repository: LedRepository,
    private val controller: LedController,
    private val stripSettings: StripSettingsStore,
    private val clockSync: ClockSyncer,
    private val deviceStates: DeviceStateStore,
) : ViewModel() {

    private val target = ControlTarget.Device(address)
    private val testing = MutableStateFlow<Rgb?>(null)
    private var testJob: Job? = null

    private val _messages = MutableSharedFlow<StripMessage>(extraBufferCapacity = 1)
    val messages: SharedFlow<StripMessage> = _messages

    val state: StateFlow<StripSettingsUiState> = combine(
        repository.devices,
        stripSettings.all,
        clockSync.syncedAt,
        deviceStates.states,
        testing.asStateFlow(),
    ) { devices, settings, synced, states, test ->
        StripSettingsUiState(
            device = devices.firstOrNull { it.address == address },
            settings = settings[address] ?: StripSettings(),
            clockSyncedAt = synced[address],
            color = states[address]?.color,
            testing = test,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StripSettingsUiState())

    fun rename(label: String) {
        viewModelScope.launch { repository.renameDevice(address, label.ifBlank { null }) }
    }

    fun syncClock() = clockSync.sync(controller, address)

    /** Saves first so the screen answers at once, and puts the old value back if the strip never got it. */
    fun saveSchedule(schedule: Command.Schedule) {
        val previous = state.value.settings.schedule(schedule.action)
        viewModelScope.launch {
            stripSettings.update(address) { it.with(schedule) }
            if (!controller.sendAndAwait(target, listOf(schedule))) {
                stripSettings.update(address) { current ->
                    if (previous == null) {
                        if (schedule.action == ScheduleAction.TurnOn) current.copy(turnOn = null) else current.copy(turnOff = null)
                    } else {
                        current.with(previous)
                    }
                }
                _messages.tryEmit(StripMessage.NotReached)
            }
        }
    }

    fun setWireOrder(order: ChannelOrder) {
        viewModelScope.launch {
            stripSettings.update(address) { it.copy(wireOrder = order) }
            if (!controller.sendAndAwait(target, listOf(Command.WireOrder(order)))) {
                _messages.tryEmit(StripMessage.NotReached)
            }
        }
    }

    /**
     * Applies the wire order, power cycles so it takes effect, then shows red, green and blue in turn.
     * The user compares the strip with the colour on screen.
     */
    fun testWireOrder(order: ChannelOrder) {
        testJob?.cancel()
        val restore = state.value.color
        testJob = viewModelScope.launch {
            try {
                stripSettings.update(address) { it.copy(wireOrder = order) }
                val reached = controller.sendAndAwait(
                    target,
                    listOf(Command.WireOrder(order), Command.Power(false), Command.Power(true)),
                )
                if (!reached) {
                    _messages.tryEmit(StripMessage.NotReached)
                    return@launch
                }
                for (colour in TEST_COLOURS) {
                    testing.value = colour
                    controller.send(target, Command.Color(colour))
                    delay(TEST_STEP_MS)
                }
                restore?.let { controller.send(target, Command.Color(it)) }
            } finally {
                testing.value = null
            }
        }
    }

    fun forget(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.removeDevice(address)
            stripSettings.forget(address)
            onDone()
        }
    }

    companion object {
        val TEST_COLOURS = listOf(Rgb(0xFF0000), Rgb(0x00FF00), Rgb(0x0000FF))
        const val TEST_STEP_MS = 1_500L

        fun defaultSchedule(action: ScheduleAction) = Command.Schedule(
            action = action,
            hour = if (action == ScheduleAction.TurnOn) 7 else 23,
            minute = 0,
            days = DayOfWeek.entries.toSet(),
            enabled = false,
        )
    }
}
