package com.kernoeb.fff3.ui.music

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.music.LiveMusic
import com.kernoeb.fff3.music.MusicSettings
import com.kernoeb.fff3.music.MusicStatus
import com.kernoeb.fff3.music.MusicStyle
import com.kernoeb.fff3.music.MusicSync
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.system.addressesOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A place music can play on. [target] null is every strip; [label] null means "All strips". */
@Immutable
data class MusicTargetOption(val target: ControlTarget?, val label: String?)

@Immutable
data class MusicUiState(
    val loaded: Boolean = false,
    val options: List<MusicTargetOption> = emptyList(),
    val settings: MusicSettings = MusicSettings(),
    val status: MusicStatus = MusicStatus.Idle,
    val bpm: Int = 0,
    /** Colour of the chosen strip before music, to tint the idle visualiser. */
    val stripColor: Rgb? = null,
) {
    val running: Boolean get() = status is MusicStatus.Running
    val hasStrips: Boolean get() = options.isNotEmpty()
}

class MusicViewModel(
    initialTarget: ControlTarget?,
    repository: LedRepository,
    private val deviceStates: DeviceStateStore,
    private val music: MusicSync,
) : ViewModel() {

    val live: LiveMusic get() = music.live

    init {
        // A link from another screen picks the target, unless music already plays somewhere.
        if (initialTarget != null && music.status.value !is MusicStatus.Running) {
            music.updateSettings { it.copy(target = initialTarget) }
        }
    }

    val state: StateFlow<MusicUiState> = combine(
        repository.snapshot,
        music.settings,
        music.status,
        music.bpm,
    ) { data, settings, status, bpm ->
        if (data == null) return@combine MusicUiState(settings = settings, status = status)
        val options = buildList {
            if (data.devices.isNotEmpty()) add(MusicTargetOption(null, null))
            data.groups.forEach { add(MusicTargetOption(ControlTarget.Group(it.id), it.name)) }
            data.devices.forEach { add(MusicTargetOption(ControlTarget.Device(it.address), it.displayName)) }
        }
        // A removed strip or group falls back to all strips.
        val chosen = settings.target.takeIf { target -> options.any { it.target == target } }
        // Read once, not collected: while music plays the colours change 30 times a second.
        val states = deviceStates.states.value
        val addresses = data.addressesOf(chosen)
        MusicUiState(
            loaded = true,
            options = options,
            settings = settings.copy(target = chosen),
            status = status,
            bpm = bpm,
            stripColor = addresses.firstNotNullOfOrNull { states[it]?.color },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    fun selectTarget(target: ControlTarget?) = music.updateSettings { it.copy(target = target) }

    fun selectStyle(style: MusicStyle) = music.updateSettings { it.copy(style = style) }

    fun setSensitivity(value: Float) = music.updateSettings { it.copy(sensitivity = value) }
}
