package com.kernoeb.fff3.ui.common

import androidx.annotation.StringRes
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.ConnectionState

@StringRes
fun ConnectionState.labelRes(): Int = when (this) {
    ConnectionState.Idle -> R.string.connection_idle
    is ConnectionState.Connecting -> R.string.connection_connecting
    ConnectionState.Connected -> R.string.connection_connected
    is ConnectionState.WaitingToRetry -> R.string.connection_waiting
    ConnectionState.BluetoothOff -> R.string.connection_bluetooth_off
    ConnectionState.PermissionMissing -> R.string.connection_permission
    ConnectionState.Unsupported -> R.string.connection_unsupported
}
