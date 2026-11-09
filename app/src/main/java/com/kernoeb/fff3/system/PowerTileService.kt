package com.kernoeb.fff3.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kernoeb.fff3.MainActivity
import com.kernoeb.fff3.R
import com.kernoeb.fff3.appContainer
import com.kernoeb.fff3.ble.BluetoothStatus
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Quick Settings tile: power for the chosen target, every strip by default. */
class PowerTileService : TileService() {

    private var listening: Job? = null
    private var shown: TileModel? = null

    override fun onStartListening() {
        val container = appContainer
        container.bluetooth.refresh()
        val chosen = QuickSettingsTile.target(this)
        listening?.cancel()
        listening = container.appScope.launch(Dispatchers.Main) {
            combine(
                container.repository.snapshot.filterNotNull(),
                container.deviceStates.states,
                container.bluetooth.status,
            ) { data, states, bluetooth ->
                val target = SystemTarget.resolve(data, chosen)
                TileModel(target, target.isOn(states), bluetooth)
            }.distinctUntilChanged().collect(::render)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        val model = shown ?: return
        if (model.target.isEmpty || model.bluetooth != BluetoothStatus.Ready) {
            openApp(model.target.route)
            return
        }
        val on = !model.on
        // Show the new state at once; the recorded state confirms it a moment later.
        render(model.copy(on = on))
        val power = if (on) SystemActionReceiver.POWER_ON else SystemActionReceiver.POWER_OFF
        SystemActionReceiver.sendNow(this, SystemActionReceiver.powerIntent(this, power, QuickSettingsTile.target(this)))
    }

    private fun render(model: TileModel) {
        shown = model
        val tile = qsTile ?: return
        tile.state = when {
            model.bluetooth == BluetoothStatus.Unsupported -> Tile.STATE_UNAVAILABLE
            model.on && model.bluetooth == BluetoothStatus.Ready -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = model.target.name ?: getString(R.string.tile_all_strips)
        tile.subtitle = when {
            model.target.isEmpty -> getString(R.string.tile_no_strips)
            model.bluetooth == BluetoothStatus.Off -> getString(R.string.connection_bluetooth_off)
            model.bluetooth == BluetoothStatus.PermissionMissing -> getString(R.string.connection_permission)
            model.bluetooth == BluetoothStatus.Unsupported -> getString(R.string.connection_unsupported)
            model.on -> getString(R.string.control_on)
            else -> getString(R.string.control_off)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_lightbulb)
        tile.updateTile()
    }

    // Android 13 has only the Intent form; the check below never uses it on 14 and later.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(route: Route) {
        val intent = MainActivity.intentFor(this, route)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private data class TileModel(val target: SystemTarget, val on: Boolean, val bluetooth: BluetoothStatus)
}

/** Hooks for the Settings screen: choose what the tile controls, and ask to add it. */
object QuickSettingsTile {

    private const val PREFS = "quick_settings_tile"
    private const val KEY_TARGET = "target"

    /** The chosen target, or null for every strip. */
    fun target(context: Context): ControlTarget? =
        ControlTarget.decode(prefs(context).getString(KEY_TARGET, null))

    fun setTarget(context: Context, target: ControlTarget?) {
        prefs(context).edit().putString(KEY_TARGET, target?.encode()).apply()
        TileService.requestListeningState(context, component(context))
    }

    /**
     * Shows the system prompt to add the tile. [onResult] gets true if the tile was added or was
     * already there. Android shows the prompt only while the app is in the foreground.
     */
    fun requestAdd(context: Context, onResult: (added: Boolean) -> Unit = {}) {
        val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return onResult(false)
        statusBar.requestAddTileService(
            component(context),
            context.getString(R.string.tile_all_strips),
            Icon.createWithResource(context, R.drawable.ic_lightbulb),
            context.mainExecutor,
        ) { result ->
            onResult(
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
            )
        }
    }

    private fun component(context: Context) = ComponentName(context, PowerTileService::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
