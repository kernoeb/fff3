package com.kernoeb.fff3.data

import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.LedFamily
import com.kernoeb.fff3.protocol.Rgb

/** Everything fff3 stores except the per-device last state. */
data class AppData(
    val devices: List<KnownDevice> = emptyList(),
    val groups: List<DeviceGroup> = emptyList(),
    val favourites: List<Favourite> = emptyList(),
    val settings: Settings = Settings(),
)

/** A strip you have added. [address] is the Bluetooth MAC address in upper case. */
data class KnownDevice(
    val address: String,
    val advertisedName: String,
    val family: LedFamily,
    val label: String? = null,
) {
    val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: advertisedName
}

data class DeviceGroup(
    val id: String,
    val name: String,
    val memberAddresses: List<String>,
)

/**
 * A saved colour or scene: the [commands] to send, in order.
 * [family] is set when the commands only make sense for one family (effects, scenes); null means any.
 */
data class Favourite(
    val id: String,
    val name: String,
    val commands: List<Command>,
    val family: LedFamily? = null,
)

data class Settings(
    val accentFollowsStrip: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val musicSource: MusicSource = MusicSource.PhoneAudio,
    /** Beat lock fine-tune in milliseconds: positive makes the lights later. */
    val musicLightDelayMs: Int = 0,
    /** Measured time from captured phone audio to sound out of each kind of output. */
    val outputDelaysMs: Map<AudioOutput, Int> = emptyMap(),
    /** Camera-measured time from a colour write to the strip showing it, by strip address. */
    val lightDelaysMs: Map<String, Int> = emptyMap(),
    val beatColors: BeatColors = BeatColors.Bars,
    val musicStyle: MusicStyle = MusicStyle.Pulse,
    /** 0..1: higher reacts to quieter sound and softer beats. */
    val musicSensitivity: Float = 0.5f,
)

/** How the lights follow the music. */
enum class MusicStyle {
    /** Each beat flashes a new colour, then fades. */
    Pulse,
    /** Colour follows the pitch of the sound: bass is red, bright sound is blue and violet. */
    Spectrum,
    /** One colour, brightness follows loudness. */
    Energy,
    /** Slow colour drift that breathes with the music. */
    Calm,
    /** Follows its own beat clock, so the lights stay on the beat through quiet parts. */
    BeatLock,
}

/** Where Beat lock takes its colour from. */
enum class BeatColors {
    /** A new colour each bar of 4 beats. */
    Bars,
    /** One colour per tempo, so a song keeps its colour until the tempo changes. */
    Tempo,
    /** The strip's colour from before music started. */
    Strip,
}

/** What music sync listens to: what the phone plays, or the room through the microphone. */
enum class MusicSource { PhoneAudio, Microphone }

/** Where the phone plays sound. Each kind has its own delay, measured once and remembered. */
enum class AudioOutput { Speaker, Wired, Bluetooth, Other }

/**
 * What fff3 last sent to a strip. The strips cannot report their state, so this is our best guess.
 * Null fields are unknown.
 */
data class LedState(
    val power: Boolean? = null,
    val color: Rgb? = null,
    val brightness: Int? = null,
    val effectId: Int? = null,
    val sceneId: Int? = null,
    val speed: Int? = null,
    val micEnabled: Boolean? = null,
) {
    /** The state after the strip received [command]. */
    fun after(command: Command): LedState = when (command) {
        is Command.Power -> copy(power = command.on)
        is Command.Color -> copy(color = command.rgb, effectId = null, sceneId = null)
        is Command.Brightness -> copy(brightness = command.percent.coerceIn(0, 100))
        is Command.Effect -> copy(
            effectId = command.id,
            sceneId = null,
            speed = command.speed?.coerceIn(0, 100) ?: speed,
        )
        is Command.Speed -> copy(speed = command.percent.coerceIn(0, 100))
        is Command.Scene -> copy(sceneId = command.id, effectId = null)
        is Command.MicEnabled -> copy(micEnabled = command.on)
        is Command.MicMode, is Command.MicSensitivity, is Command.WireOrder, is Command.PixelCount,
        is Command.ClockSync, is Command.Schedule -> this
    }
}

/** False for commands that leave [LedState] as it was, such as a clock sync or a schedule. */
val Command.changesState: Boolean
    get() = when (this) {
        is Command.Power, is Command.Color, is Command.Brightness, is Command.Effect, is Command.Speed,
        is Command.Scene, is Command.MicEnabled -> true
        is Command.MicMode, is Command.MicSensitivity, is Command.WireOrder, is Command.PixelCount,
        is Command.ClockSync, is Command.Schedule -> false
    }

/**
 * What all these strips agree on. A field is null when any strip does not know it or two strips differ,
 * so a group with one unknown member shows as unknown.
 */
fun List<LedState>.shared(): LedState {
    fun <T> agreed(field: (LedState) -> T?): T? =
        map(field).distinct().singleOrNull()
    return LedState(
        power = agreed { it.power },
        color = agreed { it.color },
        brightness = agreed { it.brightness },
        effectId = agreed { it.effectId },
        sceneId = agreed { it.sceneId },
        speed = agreed { it.speed },
        micEnabled = agreed { it.micEnabled },
    )
}
