package com.kernoeb.fff3.data

import com.kernoeb.fff3.protocol.ChannelOrder
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.LedFamily
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.protocol.ScheduleAction
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek

/**
 * A small hand-written binary format for the stores, so we need no serialisation library.
 *
 * Each file starts with a format version. To add a field: write it at the end of its record,
 * bump the version, and read it only when the file version is high enough.
 * Enums are stored by name, so reordering them is safe; renaming them is not.
 */
object AppDataCodec {

    private const val VERSION = 1

    fun write(data: AppData, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(VERSION)
        out.writeList(data.devices) { device ->
            writeUTF(device.address)
            writeUTF(device.advertisedName)
            writeUTF(device.family.name)
            writeNullableUtf(device.label)
        }
        out.writeList(data.groups) { group ->
            writeUTF(group.id)
            writeUTF(group.name)
            writeList(group.memberAddresses) { writeUTF(it) }
        }
        out.writeList(data.favourites) { favourite ->
            writeUTF(favourite.id)
            writeUTF(favourite.name)
            writeNullableUtf(favourite.family?.name)
            writeList(favourite.commands) { CommandCodec.write(it, this) }
        }
        out.writeBoolean(data.settings.accentFollowsStrip)
        out.writeBoolean(data.settings.hapticsEnabled)
        out.writeUTF(data.settings.musicSource.name)
        out.writeInt(data.settings.musicLightDelayMs)
        out.writeList(data.settings.outputDelaysMs.entries.toList()) { (output, millis) ->
            writeUTF(output.name)
            writeInt(millis)
        }
        out.writeList(data.settings.lightDelaysMs.entries.toList()) { (address, millis) ->
            writeUTF(address)
            writeInt(millis)
        }
        out.writeUTF(data.settings.beatColors.name)
        out.writeUTF(data.settings.musicStyle.name)
        out.writeFloat(data.settings.musicSensitivity)
        out.flush()
    }

    fun read(input: InputStream): AppData {
        val inp = DataInputStream(input)
        val version = inp.readInt()
        if (version !in 1..VERSION) throw IOException("Unknown app data version $version")
        val devices = inp.readList {
            KnownDevice(
                address = readUTF(),
                advertisedName = readUTF(),
                family = LedFamily.valueOf(readUTF()),
                label = readNullableUtf(),
            )
        }
        val groups = inp.readList {
            DeviceGroup(id = readUTF(), name = readUTF(), memberAddresses = readList { readUTF() })
        }
        val favourites = inp.readList {
            Favourite(
                id = readUTF(),
                name = readUTF(),
                family = readNullableUtf()?.let(LedFamily::valueOf),
                commands = readList { CommandCodec.read(this) },
            )
        }
        val settings = Settings(
            accentFollowsStrip = inp.readBoolean(),
            hapticsEnabled = inp.readBoolean(),
            musicSource = MusicSource.valueOf(inp.readUTF()),
            musicLightDelayMs = inp.readInt(),
            outputDelaysMs = inp.readList { AudioOutput.valueOf(readUTF()) to readInt() }.toMap(),
            lightDelaysMs = inp.readList { readUTF() to readInt() }.toMap(),
            beatColors = BeatColors.valueOf(inp.readUTF()),
            musicStyle = MusicStyle.valueOf(inp.readUTF()),
            musicSensitivity = inp.readFloat(),
        )
        return AppData(devices, groups, favourites, settings)
    }
}

/** Last known state per device address. Same versioning rules as [AppDataCodec]. */
object DeviceStateCodec {

    private const val VERSION = 1

    fun write(states: Map<String, LedState>, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(VERSION)
        out.writeList(states.entries.toList()) { (address, state) ->
            writeUTF(address)
            writeNullableBoolean(state.power)
            writeNullableInt(state.color?.packed)
            writeNullableInt(state.brightness)
            writeNullableInt(state.effectId)
            writeNullableInt(state.sceneId)
            writeNullableInt(state.speed)
            writeNullableBoolean(state.micEnabled)
        }
        out.flush()
    }

    fun read(input: InputStream): Map<String, LedState> {
        val inp = DataInputStream(input)
        val version = inp.readInt()
        if (version !in 1..VERSION) throw IOException("Unknown device state version $version")
        return inp.readList {
            readUTF() to LedState(
                power = readNullableBoolean(),
                color = readNullableInt()?.let(::Rgb),
                brightness = readNullableInt(),
                effectId = readNullableInt(),
                sceneId = readNullableInt(),
                speed = readNullableInt(),
                micEnabled = readNullableBoolean(),
            )
        }.toMap()
    }
}

/** Commands inside favourites. Each starts with a tag byte; never reuse a tag. */
internal object CommandCodec {

    fun write(command: Command, out: DataOutputStream) = with(out) {
        when (command) {
            is Command.Power -> { writeByte(1); writeBoolean(command.on) }
            is Command.Color -> { writeByte(2); writeInt(command.rgb.packed) }
            is Command.Brightness -> { writeByte(3); writeInt(command.percent) }
            is Command.Effect -> { writeByte(4); writeInt(command.id); writeNullableInt(command.speed) }
            is Command.Speed -> { writeByte(5); writeInt(command.percent) }
            is Command.MicEnabled -> { writeByte(6); writeBoolean(command.on) }
            is Command.MicMode -> { writeByte(7); writeInt(command.index) }
            is Command.MicSensitivity -> { writeByte(8); writeInt(command.percent) }
            is Command.Scene -> { writeByte(9); writeInt(command.id) }
            is Command.WireOrder -> { writeByte(10); writeUTF(command.order.name) }
            is Command.PixelCount -> { writeByte(11); writeInt(command.count) }
            is Command.ClockSync -> {
                writeByte(12); writeInt(command.hour); writeInt(command.minute); writeInt(command.second)
                writeInt(command.dayOfWeek.value)
            }
            is Command.Schedule -> {
                writeByte(13); writeUTF(command.action.name); writeInt(command.hour); writeInt(command.minute)
                writeInt(command.days.fold(0) { mask, day -> mask or (1 shl day.value) })
                writeBoolean(command.enabled)
            }
        }
    }

    fun read(inp: DataInputStream): Command = with(inp) {
        when (val tag = readByte().toInt()) {
            1 -> Command.Power(readBoolean())
            2 -> Command.Color(Rgb(readInt()))
            3 -> Command.Brightness(readInt())
            4 -> Command.Effect(readInt(), readNullableInt())
            5 -> Command.Speed(readInt())
            6 -> Command.MicEnabled(readBoolean())
            7 -> Command.MicMode(readInt())
            8 -> Command.MicSensitivity(readInt())
            9 -> Command.Scene(readInt())
            10 -> Command.WireOrder(ChannelOrder.valueOf(readUTF()))
            11 -> Command.PixelCount(readInt())
            12 -> Command.ClockSync(readInt(), readInt(), readInt(), DayOfWeek.of(readInt()))
            13 -> {
                val action = ScheduleAction.valueOf(readUTF())
                val hour = readInt()
                val minute = readInt()
                val mask = readInt()
                Command.Schedule(
                    action, hour, minute,
                    days = DayOfWeek.entries.filter { mask and (1 shl it.value) != 0 }.toSet(),
                    enabled = readBoolean(),
                )
            }
            else -> throw IOException("Unknown command tag $tag")
        }
    }
}

private inline fun <T> DataOutputStream.writeList(items: List<T>, writeItem: DataOutputStream.(T) -> Unit) {
    writeInt(items.size)
    items.forEach { writeItem(it) }
}

private inline fun <T> DataInputStream.readList(readItem: DataInputStream.() -> T): List<T> {
    val size = readInt()
    if (size < 0) throw IOException("Negative list size")
    return List(size) { readItem() }
}

private fun DataOutputStream.writeNullableUtf(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null

private fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    if (value != null) writeInt(value)
}

private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

private fun DataOutputStream.writeNullableBoolean(value: Boolean?) =
    writeByte(when (value) { null -> 0; false -> 1; true -> 2 })

private fun DataInputStream.readNullableBoolean(): Boolean? =
    when (readByte().toInt()) { 0 -> null; 1 -> false; else -> true }
