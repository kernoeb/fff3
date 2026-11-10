package com.kernoeb.fff3.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BluetoothStatus { Ready, Off, PermissionMissing, Unsupported }

/** Watches whether Bluetooth is usable: adapter present and on, and permission granted. */
class BluetoothEnvironment(context: Context) {

    private val appContext = context.applicationContext

    val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) }

    private val _status = MutableStateFlow(compute())
    val status: StateFlow<BluetoothStatus> = _status.asStateFlow()

    init {
        if (adapter != null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh()
            }
            appContext.registerReceiver(
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Checks again. Call after a permission request returns. */
    fun refresh() {
        _status.value = compute()
    }

    fun hasConnectPermission(): Boolean = granted(Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScanPermission(): Boolean = granted(Manifest.permission.BLUETOOTH_SCAN)

    private fun granted(permission: String) =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun compute(): BluetoothStatus = when {
        adapter == null -> BluetoothStatus.Unsupported
        !hasConnectPermission() -> BluetoothStatus.PermissionMissing
        !adapter.isEnabled -> BluetoothStatus.Off
        else -> BluetoothStatus.Ready
    }
}

internal fun BluetoothStatus.toConnectionState(): ConnectionState = when (this) {
    BluetoothStatus.Ready -> ConnectionState.Idle
    BluetoothStatus.Off -> ConnectionState.BluetoothOff
    BluetoothStatus.PermissionMissing -> ConnectionState.PermissionMissing
    BluetoothStatus.Unsupported -> ConnectionState.Unsupported
}
