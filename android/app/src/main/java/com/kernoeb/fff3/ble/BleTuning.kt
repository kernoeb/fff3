package com.kernoeb.fff3.ble

/** Timing values for the Bluetooth link. Tune them here after measuring on the real strips. */
object BleTuning {

    /**
     * Minimum time between 2 frames to one strip, in milliseconds. 35 ms is about 28 frames a second.
     * Some clones drop frames below 50 ms: raise this if a fast drag leaves the strip behind.
     */
    const val MIN_FRAME_INTERVAL_MS: Long = 35

    /** Wait before retrying a write the Bluetooth stack refused as busy. */
    const val BUSY_RETRY_MS: Long = 8
    const val MAX_BUSY_RETRIES: Int = 60

    /** Wait before retrying a write that failed for another reason. */
    const val FAILED_RETRY_MS: Long = 100
    const val MAX_FAILED_RETRIES: Int = 3

    /** How long to wait for `onCharacteristicWrite` before sending the next frame anyway. */
    const val WRITE_ACK_TIMEOUT_MS: Long = 500

    const val CONNECT_TIMEOUT_MS: Long = 10_000
    const val DISCOVERY_TIMEOUT_MS: Long = 8_000

    /** Reconnect backoff: first retry, doubling each failure, up to the maximum. */
    const val RECONNECT_FIRST_DELAY_MS: Long = 1_000
    const val RECONNECT_MAX_DELAY_MS: Long = 30_000

    /** Close a link this long after its last write when no screen or service holds a session. */
    const val IDLE_DISCONNECT_MS: Long = 20_000

    /** Drop back to balanced connection priority this long after the last write. */
    const val HIGH_PRIORITY_HOLD_MS: Long = 2_000

    /** Most commands waiting for one strip. Older non-drag commands are dropped first. */
    const val MAX_PENDING_COMMANDS: Int = 64

    /** A link nothing holds open (a tile tap, a widget) gives up after this many failed connects in a row. */
    const val UNPINNED_MAX_FAILURES: Int = 3
}
