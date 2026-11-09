package com.kernoeb.fff3.music

import com.kernoeb.fff3.ble.ControlTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What music sync plays on. [target] null means every known strip.
 * [sensitivity] 0..1: higher reacts to quieter sound and softer beats.
 */
data class MusicSettings(
    val target: ControlTarget? = null,
    val style: MusicStyle = MusicStyle.Pulse,
    val sensitivity: Float = 0.5f,
)

sealed interface MusicStatus {
    data object Idle : MusicStatus
    data class Running(val target: ControlTarget?) : MusicStatus
    data class Stopped(val reason: StopReason) : MusicStatus
}

enum class StopReason { User, MicUnavailable, MicPermissionMissing, StripsGone, BluetoothOff, BluetoothPermissionMissing }

/**
 * Live values the engine writes on the audio thread and the visualiser reads in its draw phase.
 * Reads are not atomic as a whole, which is fine for a picture refreshed every frame.
 */
class LiveMusic(bandCount: Int) {
    val bands = FloatArray(bandCount)
    /** `0xRRGGBB` at full brightness, so the visualiser stays readable when the strip is dim. */
    @Volatile var hueColor: Int = 0
    @Volatile var flash: Float = 0f
    @Volatile var level: Float = 0f

    internal fun clear() {
        bands.fill(0f)
        flash = 0f
        level = 0f
    }
}

/** Shared between the music screen and the music service. One per process, in `AppContainer`. */
class MusicSync {

    private val _settings = MutableStateFlow(MusicSettings())
    val settings: StateFlow<MusicSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow<MusicStatus>(MusicStatus.Idle)
    val status: StateFlow<MusicStatus> = _status.asStateFlow()

    private val _bpm = MutableStateFlow(0)
    /** Rounded tempo, or 0 while unknown. Changes a few times a minute at most. */
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    val live = LiveMusic(BAND_COUNT)

    fun updateSettings(transform: (MusicSettings) -> MusicSettings) = _settings.update(transform)

    internal fun setStatus(status: MusicStatus) {
        _status.value = status
        if (status !is MusicStatus.Running) {
            _bpm.value = 0
            live.clear()
        }
    }

    internal fun setBpm(bpm: Int) {
        _bpm.value = bpm
    }

    companion object {
        const val BAND_COUNT = 24
    }
}
