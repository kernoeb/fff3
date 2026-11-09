package com.kernoeb.fff3.protocol

import java.time.DayOfWeek
import java.util.UUID

/**
 * ELK-BLEDOM family (also ELK-BLEDOB, ELK-HR, CLK-BLEDDM).
 *
 * Every frame is 9 bytes: `7e LL CC … ef`. From the protocol notes kept outside this repo.
 * Correct bytes here after testing on a strip.
 */
object DomProtocol : LedProtocol {

    override val gatt = GattProfile(
        service = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
        writeCharacteristic = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb"),
        writeWithoutResponse = true,
    )

    /** Byte 7 of the colour frame. Strips accept `10` and `00`. Meaning unknown. */
    const val COLOR_TRAILER = 0x10

    const val EFFECT_COUNT = 29
    const val MIC_MODE_COUNT = 4

    override fun encode(command: Command): List<ByteArray> = when (command) {
        is Command.Power -> listOf(power(command.on))
        is Command.Color -> listOf(color(command.rgb))
        is Command.Brightness -> listOf(brightness(command.percent))
        is Command.Effect -> effect(command.id)?.let { listOfNotNull(it, command.speed?.let(::speed)) }.orEmpty()
        is Command.Speed -> listOf(speed(command.percent))
        is Command.MicEnabled -> listOf(micEnabled(command.on))
        is Command.MicMode -> listOf(micMode(command.index, MIC_MODE_COUNT))
        is Command.MicSensitivity -> listOf(micSensitivity(command.percent))
        is Command.WireOrder -> listOf(wireOrder(command.order))
        is Command.ClockSync -> listOf(clockSync(command))
        is Command.Schedule -> listOf(schedule(command))
        is Command.Scene, is Command.PixelCount -> emptyList()
    }

    fun power(on: Boolean): ByteArray {
        val p = if (on) 0x01 else 0x00
        return bytesOf(0x7e, 0x04, 0x04, p, 0x00, p, 0xff, 0x00, 0xef)
    }

    fun color(rgb: Rgb): ByteArray =
        bytesOf(0x7e, 0x07, 0x05, 0x03, rgb.red, rgb.green, rgb.blue, COLOR_TRAILER, 0xef)

    fun brightness(percent: Int): ByteArray =
        bytesOf(0x7e, 0x04, 0x01, percent.coerceIn(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)

    /** [index] 0..28, sent as `0x80 + index`. Returns null outside the catalogue. */
    fun effect(index: Int): ByteArray? {
        if (index !in 0 until EFFECT_COUNT) return null
        return bytesOf(0x7e, 0x05, 0x03, 0x80 + index, 0x03, 0xff, 0xff, 0x00, 0xef)
    }

    fun speed(percent: Int): ByteArray =
        bytesOf(0x7e, 0x04, 0x02, percent.coerceIn(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)

    fun micEnabled(on: Boolean): ByteArray =
        bytesOf(0x7e, 0x04, 0x07, if (on) 0x01 else 0x00, 0xff, 0xff, 0xff, 0x00, 0xef)

    fun micSensitivity(percent: Int): ByteArray =
        bytesOf(0x7e, 0x04, 0x06, percent.coerceIn(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)

    /** Shared with MELK, which has 8 modes instead of 4. */
    fun micMode(index: Int, modeCount: Int): ByteArray =
        bytesOf(0x7e, 0x05, 0x03, 0x80 + index.coerceIn(0, modeCount - 1), 0x04, 0xff, 0xff, 0x00, 0xef)

    fun wireOrder(order: ChannelOrder): ByteArray = bytesOf(
        0x7e, 0x06, 0x81, order.first.wireCode, order.second.wireCode, order.third.wireCode, 0xff, 0x00, 0xef,
    )

    /** The weekday byte is 0 for Sunday to 6 for Saturday. */
    fun clockSync(clock: Command.ClockSync): ByteArray = bytesOf(
        0x7e, 0x07, 0x83,
        clock.hour.coerceIn(0, 23), clock.minute.coerceIn(0, 59), clock.second.coerceIn(0, 59),
        clock.dayOfWeek.value % 7, 0xff, 0xef,
    )

    /**
     * Byte 6 is 00 for the on schedule, 01 for the off schedule.
     * Byte 7 is the weekday mask (bit 0 Monday … bit 6 Sunday), with bit 7 set while enabled.
     * The weekday bit order is not confirmed on hardware.
     */
    fun schedule(schedule: Command.Schedule): ByteArray {
        val action = if (schedule.action == ScheduleAction.TurnOn) 0x00 else 0x01
        val days = weekdayMask(schedule.days) or (if (schedule.enabled) 0x80 else 0x00)
        return bytesOf(
            0x7e, 0x08, 0x82,
            schedule.hour.coerceIn(0, 23), schedule.minute.coerceIn(0, 59), 0x00, action, days, 0xef,
        )
    }

    fun weekdayMask(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }
}
