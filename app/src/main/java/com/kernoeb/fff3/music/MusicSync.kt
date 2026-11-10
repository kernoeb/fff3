package com.kernoeb.fff3.music

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.BeatColors
import com.kernoeb.fff3.data.MusicSource
import com.kernoeb.fff3.data.MusicStyle
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What music sync plays on. [target] null means every known strip.
 * [sensitivity] 0..1: higher reacts to quieter sound and softer beats.
 * [lightDelayMs] is the saved beat lock fine-tune: positive makes the lights later.
 */
data class MusicSettings(
    val target: ControlTarget? = null,
    val style: MusicStyle = MusicStyle.Pulse,
    val sensitivity: Float = 0.5f,
    val lightDelayMs: Int = 50,
    val beatColors: BeatColors = BeatColors.Bars,
)

/** Beat lock state for the screen. [bpm] is 0 while listening. */
data class BeatStatus(val locked: Boolean = false, val bpm: Int = 0)

/**
 * The delays beat lock compensates, rounded to milliseconds. [output] is null for the microphone,
 * which hears the sound when the room does. [outputMeasured] is false while [outputMs] is a guess.
 */
data class LatencyStatus(
    val output: OutputDevice? = null,
    val outputMs: Int = 0,
    val outputMeasured: Boolean = false,
    val captureMs: Int = 0,
    val lightsMs: Int = 0,
    val calibrating: Boolean = false,
)

/** What the music screen asks the beat clock to do, handled on the audio thread. */
sealed interface BeatCommand {
    /** A tap on the beat, at [nanos] on the [System.nanoTime] clock. */
    data class Tap(val nanos: Long) : BeatCommand
    data class ScaleTempo(val factor: Double) : BeatCommand
    data object Recalibrate : BeatCommand
}

sealed interface MusicStatus {
    data object Idle : MusicStatus
    data class Running(val target: ControlTarget?, val source: MusicSource) : MusicStatus
    data class Stopped(val reason: StopReason) : MusicStatus
}

enum class StopReason {
    User, MicUnavailable, MicPermissionMissing, StripsGone, BluetoothOff, BluetoothPermissionMissing,
    /** The user refused to share phone audio. */
    CaptureDenied,
    /** Sharing was stopped from outside the app, for example from the status bar. */
    CaptureEnded,
    CaptureUnavailable,
}

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
    /** Phase of the beat lock clock, 0..1 from the last beat, or -1 when not locked. */
    @Volatile var beatPhase: Float = -1f
    /** 0 to 3: which beat of the bar [beatPhase] counts from. */
    @Volatile var beatInBar: Int = 0

    internal fun clear() {
        bands.fill(0f)
        flash = 0f
        level = 0f
        beatPhase = -1f
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

    private val _noAudio = MutableStateFlow(false)
    /** True while phone audio capture has heard only digital silence for a few seconds. */
    val noAudio: StateFlow<Boolean> = _noAudio.asStateFlow()

    private val _beat = MutableStateFlow(BeatStatus())
    /** Beat lock state. Changes on lock, re-lock and tempo fixes, not every beat. */
    val beat: StateFlow<BeatStatus> = _beat.asStateFlow()

    private val _latency = MutableStateFlow<LatencyStatus?>(null)
    /** What beat lock compensates, or null while music is off. Changes about once a second at most. */
    val latency: StateFlow<LatencyStatus?> = _latency.asStateFlow()

    internal val commands = ConcurrentLinkedQueue<BeatCommand>()

    val live = LiveMusic(BAND_COUNT)

    /** Ignored while music is off. */
    fun send(command: BeatCommand) {
        if (_status.value is MusicStatus.Running) commands.add(command)
    }

    fun updateSettings(transform: (MusicSettings) -> MusicSettings) = _settings.update(transform)

    internal fun setStatus(status: MusicStatus) {
        _status.value = status
        if (status !is MusicStatus.Running) {
            _bpm.value = 0
            _noAudio.value = false
            _beat.value = BeatStatus()
            _latency.value = null
            commands.clear()
            live.clear()
        }
    }

    internal fun setNoAudio(silent: Boolean) {
        _noAudio.value = silent
    }

    internal fun setBpm(bpm: Int) {
        _bpm.value = bpm
    }

    internal fun setBeat(beat: BeatStatus) {
        _beat.value = beat
    }

    internal fun setLatency(latency: LatencyStatus) {
        _latency.value = latency
    }

    companion object {
        const val BAND_COUNT = 24
    }
}
