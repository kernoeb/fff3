package com.kernoeb.fff3

import android.content.Context
import com.kernoeb.fff3.ble.BleLedController
import com.kernoeb.fff3.ble.BleScanner
import com.kernoeb.fff3.ble.BluetoothEnvironment
import com.kernoeb.fff3.ble.ClockSyncer
import com.kernoeb.fff3.ble.FrameWrites
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.ble.WriteLatency
import com.kernoeb.fff3.data.DataStoreLedRepository
import com.kernoeb.fff3.data.DataStoreStripSettingsStore
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.PersistentDeviceStateStore
import com.kernoeb.fff3.data.Stores
import com.kernoeb.fff3.data.StripSettingsStore
import com.kernoeb.fff3.music.MusicSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Every long-lived object, built by hand once per process. Get it with `context.appContainer`.
 * Objects are created on first use, so a tile or widget only pays for what it touches.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Lives as long as the process. For work that must outlive a screen, like saving. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One thread for all Bluetooth work, so links never race each other. */
    private val bleScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1, "ble"))

    val repository: LedRepository by lazy {
        DataStoreLedRepository(Stores.appData(appContext, appScope), appScope)
    }

    val deviceStates: DeviceStateStore by lazy {
        PersistentDeviceStateStore(Stores.deviceStates(appContext, appScope), appScope)
    }

    val bluetooth: BluetoothEnvironment by lazy { BluetoothEnvironment(appContext) }

    val controller: LedController by lazy {
        BleLedController(appContext, bluetooth, repository, deviceStates, bleScope, linkLatency, queueWait, frameWrites).also(clockSync::watch)
    }

    /** How long a colour takes from `controller.send` to the strip, for the music beat clock. */
    val linkLatency: WriteLatency by lazy { WriteLatency() }

    /** How long a command waits in the write queue before its Bluetooth write, for the music beat clock. */
    val queueWait: WriteLatency by lazy { WriteLatency() }

    /** When each frame reaches the Bluetooth stack, for the camera light delay calibration. */
    val frameWrites: FrameWrites by lazy { FrameWrites() }

    /** Sends the time to each strip on connect. Started by [controller]. */
    val clockSync: ClockSyncer by lazy { ClockSyncer(appScope) }

    val stripSettings: StripSettingsStore by lazy { DataStoreStripSettingsStore.create(appContext, appScope) }

    val scanner: BleScanner by lazy { BleScanner(bluetooth) }

    /** Phone music mode state, shared by the music screen and `MusicService`. */
    val music: MusicSync by lazy {
        MusicSync().also { music ->
            appScope.launch {
                repository.settings.map { it.musicLightDelayMs }.distinctUntilChanged().collect { delay ->
                    music.updateSettings { it.copy(lightDelayMs = delay) }
                }
            }
            appScope.launch {
                repository.settings.map { it.beatColors }.distinctUntilChanged().collect { colors ->
                    music.updateSettings { it.copy(beatColors = colors) }
                }
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as Fff3Application).container
