package com.kernoeb.fff3.ui.music

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.MusicSource
import com.kernoeb.fff3.music.LiveMusic
import com.kernoeb.fff3.music.MusicSettings
import com.kernoeb.fff3.music.MusicStatus
import com.kernoeb.fff3.music.MusicStyle
import com.kernoeb.fff3.music.MusicSync
import com.kernoeb.fff3.music.StopReason
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.system.addressesOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** The saved choice; the running source is in [status]. */
    val source: MusicSource = MusicSource.PhoneAudio,
    /** Phone audio has heard only silence for a while: capture is likely blocked. */
    val noAudio: Boolean = false,
) {
    val running: Boolean get() = status is MusicStatus.Running
    val hasStrips: Boolean get() = options.isNotEmpty()
}

class MusicViewModel(
    initialTarget: ControlTarget?,
    private val repository: LedRepository,
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
        music.noAudio,
    ) { data, settings, status, bpm, noAudio ->
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
            source = data.settings.musicSource,
            noAudio = noAudio && status is MusicStatus.Running && status.source == MusicSource.PhoneAudio,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    fun selectTarget(target: ControlTarget?) = music.updateSettings { it.copy(target = target) }

    fun selectSource(source: MusicSource) {
        viewModelScope.launch { repository.updateSettings { it.copy(musicSource = source) } }
    }

    /** The user closed the capture consent screen without sharing. A running source keeps going. */
    fun onCaptureDenied() {
        val status = music.status.value
        if (status is MusicStatus.Running) selectSource(status.source)
        else music.setStatus(MusicStatus.Stopped(StopReason.CaptureDenied))
    }

    fun selectStyle(style: MusicStyle) = music.updateSettings { it.copy(style = style) }

    fun setSensitivity(value: Float) = music.updateSettings { it.copy(sensitivity = value) }
}
