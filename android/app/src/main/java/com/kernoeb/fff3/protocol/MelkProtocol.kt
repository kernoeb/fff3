package com.kernoeb.fff3.protocol

/**
 * MELK family (MELK-OA…, and MELK-OC/OD/OF which add scenes).
 *
 * Same 9-byte frame and GATT layout as [DomProtocol]. MELK changes speed, pattern, mic mode,
 * wire order, and adds scenes and pixel count. Everything else uses the DOM frame.
 * From the protocol notes kept outside this repo. Correct bytes here after testing on a strip.
 */
object MelkProtocol : LedProtocol {

    override val gatt: GattProfile = DomProtocol.gatt

    const val MIC_MODE_COUNT = 8

    override fun encode(command: Command): List<ByteArray> = when (command) {
        is Command.Power,
        is Command.Color,
        is Command.Brightness,
        is Command.MicEnabled,
        is Command.MicSensitivity,
        is Command.ClockSync,
        is Command.Schedule -> DomProtocol.encode(command)

        is Command.Effect -> listOf(pattern(command.id)) + listOfNotNull(command.speed?.let(::speed))
        is Command.Speed -> listOf(speed(command.percent))
        is Command.MicMode -> listOf(DomProtocol.micMode(command.index, MIC_MODE_COUNT))
        is Command.WireOrder -> listOf(wireOrder(command.order))
        is Command.Scene -> listOf(scene(command.id))
        is Command.PixelCount -> listOf(pixelCount(command.count))
    }

    fun speed(percent: Int): ByteArray =
        bytesOf(0x7e, 0x07, 0x02, percent.coerceIn(0, 100), 0xff, 0xff, 0xff, 0x00, 0xef)

    /** [id] is the pattern byte itself, 0..255. */
    fun pattern(id: Int): ByteArray =
        bytesOf(0x7e, 0x07, 0x03, id.coerceIn(0, 255), 0x06, 0xff, 0xff, 0x00, 0xef)

    fun wireOrder(order: ChannelOrder): ByteArray = bytesOf(
        0x7e, 0x07, 0x81, order.first.wireCode, order.second.wireCode, order.third.wireCode, 0xff, 0x00, 0xef,
    )

    fun scene(id: Int): ByteArray =
        bytesOf(0x7e, 0x07, 0x31, id.coerceIn(0, 255), 0x07, 0xff, 0xff, 0x00, 0xef)

    /** Little-endian count, 10..1000. */
    fun pixelCount(count: Int): ByteArray {
        val n = count.coerceIn(10, 1000)
        return bytesOf(0x7e, 0x07, 0x21, n and 0xff, (n shr 8) and 0xff, 0x00, 0xff, 0x00, 0xef)
    }
}
