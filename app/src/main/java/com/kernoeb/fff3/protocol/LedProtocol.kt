package com.kernoeb.fff3.protocol

import java.util.UUID

/** Turns [Command]s into the bytes one controller family understands. Pure and stateless. */
interface LedProtocol {

    /** Where to write the frames. */
    val gatt: GattProfile

    /**
     * The frames to write, in order, one GATT write each.
     * An empty list means this protocol has no frame for [command].
     */
    fun encode(command: Command): List<ByteArray>
}

/**
 * The GATT service and characteristic a family writes to.
 * [writeWithoutResponse] is the preferred write type; the link falls back to a normal write
 * if the characteristic does not allow it.
 */
data class GattProfile(
    val service: UUID,
    val writeCharacteristic: UUID,
    val writeWithoutResponse: Boolean,
)

/** One entry of an effect or scene catalogue. [id] is the value [Command.Effect] or [Command.Scene] takes. */
data class EffectInfo(val id: Int, val name: String, val group: String)

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** Lower-case hex with no separator, for logs and tests: `7e0404...`. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
