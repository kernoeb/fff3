package com.kernoeb.fff3.protocol

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Something you can ask a strip to do. A [LedProtocol] turns it into bytes.
 *
 * Values outside their range are clamped by the encoder, never rejected.
 * Not every family supports every command: check [LedFamily.supports].
 * No family has a white channel. For white, send [Color] with [Rgb.fromKelvin].
 */
sealed interface Command {

    /**
     * True for values a user drags (colour, brightness, speed, sensitivity).
     * The write queue keeps only the newest pending value of each such command type.
     */
    val coalesces: Boolean get() = false

    data class Power(val on: Boolean) : Command

    data class Color(val rgb: Rgb) : Command {
        override val coalesces: Boolean get() = true
    }

    /** [percent] 0..100. */
    data class Brightness(val percent: Int) : Command {
        override val coalesces: Boolean get() = true
    }

    /**
     * A built-in animation. [id] is an [EffectInfo.id] from the family's catalogue.
     * When [speed] (0..100) is set, a speed frame follows the effect frame.
     */
    data class Effect(val id: Int, val speed: Int? = null) : Command

    /** Effect speed, [percent] 0..100. */
    data class Speed(val percent: Int) : Command {
        override val coalesces: Boolean get() = true
    }

    /** Turns the controller's own microphone mode on or off. */
    data class MicEnabled(val on: Boolean) : Command

    /** [index] into [LedFamily.micModes]. */
    data class MicMode(val index: Int) : Command

    /** [percent] 0..100. */
    data class MicSensitivity(val percent: Int) : Command {
        override val coalesces: Boolean get() = true
    }

    /** A MELK_SCENE preset. [id] is an [EffectInfo.id] from [LedFamily.scenes]. */
    data class Scene(val id: Int) : Command

    /** How the strip's red, green and blue wires are connected. Takes effect after a power cycle. */
    data class WireOrder(val order: ChannelOrder) : Command

    /** Number of LEDs on an addressable strip (MELK only), 10..1000. */
    data class PixelCount(val count: Int) : Command

    /** Sets the controller clock, which runs the on and off [Schedule]s. */
    data class ClockSync(
        val hour: Int,
        val minute: Int,
        val second: Int,
        val dayOfWeek: DayOfWeek,
    ) : Command {
        companion object {
            fun of(time: LocalDateTime): ClockSync =
                ClockSync(time.hour, time.minute, time.second, time.dayOfWeek)
        }
    }

    /**
     * An on or off schedule stored on the controller. A controller holds one of each [action].
     * [enabled] false clears it. An empty [days] set means no day.
     */
    data class Schedule(
        val action: ScheduleAction,
        val hour: Int,
        val minute: Int,
        val days: Set<DayOfWeek>,
        val enabled: Boolean = true,
    ) : Command
}

enum class ScheduleAction { TurnOn, TurnOff }

/** Wire order, named by the colour on the first, second and third wire. */
enum class ChannelOrder(val first: Channel, val second: Channel, val third: Channel) {
    RGB(Channel.Red, Channel.Green, Channel.Blue),
    RBG(Channel.Red, Channel.Blue, Channel.Green),
    GRB(Channel.Green, Channel.Red, Channel.Blue),
    GBR(Channel.Green, Channel.Blue, Channel.Red),
    BRG(Channel.Blue, Channel.Red, Channel.Green),
    BGR(Channel.Blue, Channel.Green, Channel.Red),
}

/** [wireCode] is the byte the controllers use for each colour. */
enum class Channel(val wireCode: Int) { Red(1), Green(2), Blue(3) }
