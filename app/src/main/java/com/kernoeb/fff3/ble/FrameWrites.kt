package com.kernoeb.fff3.ble

/**
 * Reports each frame as it goes to the Bluetooth stack, timed on the `SystemClock.elapsedRealtimeNanos`
 * clock, so the camera calibration can time the strip's light against it. Thread safe.
 */
class FrameWrites {

    fun interface Listener {
        /** Called on the Bluetooth thread; keep it short. */
        fun onWrite(address: String, frame: ByteArray, elapsedNanos: Long)
    }

    @Volatile var listener: Listener? = null
}
