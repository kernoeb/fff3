package com.kernoeb.fff3.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.kernoeb.fff3.protocol.ChannelOrder
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.ScheduleAction
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * What we last sent to one strip's own memory: wire order and the on and off schedules.
 * The strips are write-only, so this is the only record of it.
 */
data class StripSettings(
    val wireOrder: ChannelOrder? = null,
    val turnOn: Command.Schedule? = null,
    val turnOff: Command.Schedule? = null,
) {
    fun schedule(action: ScheduleAction): Command.Schedule? =
        if (action == ScheduleAction.TurnOn) turnOn else turnOff

    fun with(schedule: Command.Schedule): StripSettings =
        if (schedule.action == ScheduleAction.TurnOn) copy(turnOn = schedule) else copy(turnOff = schedule)
}

/** Per-strip settings by device address. */
interface StripSettingsStore {
    val all: StateFlow<Map<String, StripSettings>>
    suspend fun update(address: String, transform: (StripSettings) -> StripSettings)
    suspend fun forget(address: String)
}

class DataStoreStripSettingsStore(
    private val store: DataStore<Map<String, StripSettings>>,
    scope: CoroutineScope,
) : StripSettingsStore {

    override val all: StateFlow<Map<String, StripSettings>> =
        store.data.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun update(address: String, transform: (StripSettings) -> StripSettings) {
        store.updateData { map -> map + (address to transform(map[address] ?: StripSettings())) }
    }

    override suspend fun forget(address: String) {
        store.updateData { it - address }
    }

    companion object {
        fun create(context: Context, scope: CoroutineScope) = DataStoreStripSettingsStore(
            DataStoreFactory.create(
                serializer = StripSettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { e ->
                    Log.e("fff3", "The strip settings file is unreadable, starting empty", e)
                    emptyMap()
                },
                scope = scope,
                produceFile = { context.applicationContext.dataStoreFile("strip_settings.bin") },
            ),
            scope,
        )
    }
}

private object StripSettingsSerializer : Serializer<Map<String, StripSettings>> {
    override val defaultValue: Map<String, StripSettings> = emptyMap()

    override suspend fun readFrom(input: InputStream): Map<String, StripSettings> = try {
        StripSettingsCodec.read(input)
    } catch (e: IOException) {
        throw CorruptionException("Cannot read strip settings", e)
    } catch (e: RuntimeException) {
        throw CorruptionException("Cannot read strip settings", e)
    }

    override suspend fun writeTo(t: Map<String, StripSettings>, output: OutputStream) =
        StripSettingsCodec.write(t, output)
}

/** Same versioning rules as [AppDataCodec]. Weekdays are stored as bit (day - 1), Monday first. */
object StripSettingsCodec {

    private const val VERSION = 1

    fun write(settings: Map<String, StripSettings>, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(VERSION)
        out.writeInt(settings.size)
        for ((address, strip) in settings) {
            out.writeUTF(address)
            out.writeBoolean(strip.wireOrder != null)
            strip.wireOrder?.let { out.writeUTF(it.name) }
            writeSchedule(out, strip.turnOn)
            writeSchedule(out, strip.turnOff)
        }
        out.flush()
    }

    fun read(input: InputStream): Map<String, StripSettings> {
        val inp = DataInputStream(input)
        val version = inp.readInt()
        if (version !in 1..VERSION) throw IOException("Unknown strip settings version $version")
        val size = inp.readInt()
        if (size < 0) throw IOException("Negative size")
        return (0 until size).associate {
            val address = inp.readUTF()
            val wireOrder = if (inp.readBoolean()) ChannelOrder.valueOf(inp.readUTF()) else null
            address to StripSettings(
                wireOrder = wireOrder,
                turnOn = readSchedule(inp, ScheduleAction.TurnOn),
                turnOff = readSchedule(inp, ScheduleAction.TurnOff),
            )
        }
    }

    private fun writeSchedule(out: DataOutputStream, schedule: Command.Schedule?) {
        out.writeBoolean(schedule != null)
        if (schedule == null) return
        out.writeByte(schedule.hour)
        out.writeByte(schedule.minute)
        out.writeByte(schedule.days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) })
        out.writeBoolean(schedule.enabled)
    }

    private fun readSchedule(inp: DataInputStream, action: ScheduleAction): Command.Schedule? {
        if (!inp.readBoolean()) return null
        val hour = inp.readUnsignedByte()
        val minute = inp.readUnsignedByte()
        val mask = inp.readUnsignedByte()
        return Command.Schedule(
            action = action,
            hour = hour,
            minute = minute,
            days = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet(),
            enabled = inp.readBoolean(),
        )
    }
}
