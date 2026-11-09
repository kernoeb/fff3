package com.kernoeb.fff3.ble

/** The state of the Bluetooth link to one strip. */
sealed interface ConnectionState {

    /** Not connected, and not trying: nothing needs this strip right now. */
    data object Idle : ConnectionState

    /** [attempt] starts at 1 and grows with each failure in a row. */
    data class Connecting(val attempt: Int) : ConnectionState

    data object Connected : ConnectionState

    /** The last attempt failed or the link dropped. Tries again after [retryInMillis]. */
    data class WaitingToRetry(val failures: Int, val retryInMillis: Long) : ConnectionState

    data object BluetoothOff : ConnectionState

    /** The "Nearby devices" permission (`BLUETOOTH_CONNECT`) is not granted. */
    data object PermissionMissing : ConnectionState

    /** The phone has no Bluetooth LE. */
    data object Unsupported : ConnectionState
}

val ConnectionState.isConnected: Boolean get() = this == ConnectionState.Connected

/** Whether a send to this strip will be delivered by itself, without the user fixing something first. */
val ConnectionState.canDeliver: Boolean
    get() = this !is ConnectionState.BluetoothOff &&
        this !is ConnectionState.PermissionMissing &&
        this !is ConnectionState.Unsupported
