package com.kernoeb.fff3.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope

/** Creates the 2 DataStore files. Call once per process. */
object Stores {

    fun appData(context: Context, scope: CoroutineScope): DataStore<AppData> = DataStoreFactory.create(
        serializer = serializer(AppData(), AppDataCodec::read, AppDataCodec::write),
        corruptionHandler = resetOnCorruption("app data", AppData()),
        scope = scope,
        produceFile = { context.dataStoreFile("fff3.bin") },
    )

    fun deviceStates(context: Context, scope: CoroutineScope): DataStore<Map<String, LedState>> = DataStoreFactory.create(
        serializer = serializer(emptyMap(), DeviceStateCodec::read, DeviceStateCodec::write),
        corruptionHandler = resetOnCorruption("device states", emptyMap()),
        scope = scope,
        produceFile = { context.dataStoreFile("device_states.bin") },
    )

    private fun <T> serializer(
        default: T,
        read: (InputStream) -> T,
        write: (T, OutputStream) -> Unit,
    ) = object : Serializer<T> {
        override val defaultValue: T = default

        override suspend fun readFrom(input: InputStream): T = try {
            read(input)
        } catch (e: IOException) {
            throw CorruptionException("Cannot read store", e)
        } catch (e: RuntimeException) {
            // Bad enum names, colours or weekdays (DateTimeException) mean a damaged file too.
            throw CorruptionException("Cannot read store", e)
        }

        override suspend fun writeTo(t: T, output: OutputStream) = write(t, output)
    }

    private fun <T> resetOnCorruption(name: String, default: T) = ReplaceFileCorruptionHandler { e ->
        Log.e("fff3", "The $name file is unreadable, starting empty", e)
        default
    }
}
