package com.kernoeb.fff3.ui.music

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.BeatColors
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.MusicSource
import com.kernoeb.fff3.music.BeatCommand
import com.kernoeb.fff3.music.BeatStatus
import com.kernoeb.fff3.music.LatencyStatus
import com.kernoeb.fff3.music.LightTiming
import com.kernoeb.fff3.music.LiveMusic
import com.kernoeb.fff3.music.MusicSettings
import com.kernoeb.fff3.music.MusicStatus
import com.kernoeb.fff3.music.MusicStyle
import com.kernoeb.fff3.music.MusicSync
import com.kernoeb.fff3.music.StopReason
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.system.addressesOf
import com.kernoeb.fff3.ui.home.wholeSetOf
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
    val beat: BeatStatus = BeatStatus(),
    /** Null while music is off. */
    val latency: LatencyStatus? = null,
    /** Camera-measured light delay of the chosen strips, or null if none is measured. */
    val measuredLightMs: Int? = null,
    /** The strip the camera calibration should open on. */
    val calibrateAddress: String? = null,
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

    private val tempo = combine(music.bpm, music.beat, music.latency, ::Triple)

    val state: StateFlow<MusicUiState> = combine(
        repository.snapshot,
        music.settings,
        music.status,
        tempo,
        music.noAudio,
    ) { data, settings, status, (bpm, beat, latency), noAudio ->
        if (data == null) return@combine MusicUiState(settings = settings, status = status)
        val wholeSet = wholeSetOf(data.devices.map { it.address }, data.groups)
        val options = buildList {
            if (wholeSet.showBuiltIn) add(MusicTargetOption(null, null))
            data.groups.forEach { add(MusicTargetOption(ControlTarget.Group(it.id), it.name)) }
            data.devices.forEach { add(MusicTargetOption(ControlTarget.Device(it.address), it.displayName)) }
        }
        // A removed strip or group falls back to all strips, shown as the group of every strip if there is one.
        val chosen = settings.target.takeIf { target -> options.any { it.target == target } }
            ?: wholeSet.standIn
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
            beat = beat,
            latency = latency,
            measuredLightMs = LightTiming.measuredMs(data.settings.lightDelaysMs, addresses),
            calibrateAddress = addresses.firstOrNull { it !in data.settings.lightDelaysMs } ?: addresses.firstOrNull(),
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

    /** [nanos] is when the finger touched, on the [System.nanoTime] clock. */
    fun tap(nanos: Long) = music.send(BeatCommand.Tap(nanos))

    fun scaleTempo(factor: Double) = music.send(BeatCommand.ScaleTempo(factor))

    fun recalibrate() = music.send(BeatCommand.Recalibrate)

    /** Applies at once while the slider moves; [saveLightDelay] keeps it. */
    fun setLightDelay(millis: Int) = music.updateSettings { it.copy(lightDelayMs = millis) }

    fun selectBeatColors(colors: BeatColors) {
        music.updateSettings { it.copy(beatColors = colors) }
        viewModelScope.launch { repository.updateSettings { it.copy(beatColors = colors) } }
    }

    fun saveLightDelay() {
        val millis = music.settings.value.lightDelayMs
        viewModelScope.launch { repository.updateSettings { it.copy(musicLightDelayMs = millis) } }
    }
}
