package com.kernoeb.fff3.ble

import com.kernoeb.fff3.protocol.Command
import kotlinx.coroutines.flow.StateFlow

/**
 * The one entry point to send commands to strips. Screens, the tile, the widget, shortcuts and
 * the music service all go through it.
 *
 * Sending never blocks and never fails loudly: it connects on demand, queues, coalesces drag values
 * and records the new state in `DeviceStateStore`. A command a device's family cannot do is skipped
 * for that device.
 */
interface LedController {

    /** Link state of every known device, keyed by address. */
    val connectionStates: StateFlow<Map<String, ConnectionState>>

    /** Sends [command] to the device, or to every member of the group. Safe to call on every drag event. */
    fun send(target: ControlTarget, command: Command)

    /** Sends [commands] in order. */
    fun send(target: ControlTarget, commands: List<Command>)

    /**
     * Like [send], but suspends until every member has the commands, or [timeoutMillis] passes.
     * Returns true only if every member got every command. For the tile, widget and shortcuts,
     * which must finish their work before the system stops them.
     */
    suspend fun sendAndAwait(target: ControlTarget, commands: List<Command>, timeoutMillis: Long = 10_000): Boolean

    /**
     * Keeps every known device connected until the session is closed, so the first command is instant.
     * The activity holds one while visible; the music service holds one while running.
     * After the last session closes, links close [BleTuning.IDLE_DISCONNECT_MS] after their last write.
     */
    fun openSession(): LedSession

    /** Retries waiting links now, for example after the user turned Bluetooth on or granted permission. */
    fun retryNow()
}

/** Close it when you no longer need the strips connected. Closing twice is harmless. */
fun interface LedSession : AutoCloseable {
    override fun close()
}

/** The state of one device's link, or [ConnectionState.Idle] if it is unknown. */
fun Map<String, ConnectionState>.of(address: String): ConnectionState = this[address] ?: ConnectionState.Idle
