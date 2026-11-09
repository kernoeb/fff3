package com.kernoeb.fff3

import android.content.Context
import com.kernoeb.fff3.ble.BleLedController
import com.kernoeb.fff3.ble.BleScanner
import com.kernoeb.fff3.ble.BluetoothEnvironment
import com.kernoeb.fff3.ble.ClockSyncer
import com.kernoeb.fff3.ble.CompanionPairing
import com.kernoeb.fff3.ble.LedController
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
        BleLedController(appContext, bluetooth, repository, deviceStates, bleScope).also(clockSync::watch)
    }

    /** Sends the time to each strip on connect. Started by [controller]. */
    val clockSync: ClockSyncer by lazy { ClockSyncer(appScope) }

    val stripSettings: StripSettingsStore by lazy { DataStoreStripSettingsStore.create(appContext, appScope) }

    val pairing: CompanionPairing by lazy { CompanionPairing(appContext) }

    val scanner: BleScanner by lazy { BleScanner(bluetooth) }

    /** Phone music mode state, shared by the music screen and `MusicService`. */
    val music: MusicSync by lazy { MusicSync() }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as Fff3Application).container
