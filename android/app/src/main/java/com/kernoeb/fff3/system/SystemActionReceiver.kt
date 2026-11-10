package com.kernoeb.fff3.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kernoeb.fff3.appContainer
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.protocol.Command
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends the commands of the tile, widget and shortcuts. A receiver with `goAsync` keeps a cold
 * process alive until the strips have the commands, without showing any UI.
 * Links are not held open: the controller closes them after its idle delay.
 */
class SystemActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.appContainer
        container.appScope.launch {
            try {
                runCatching { withTimeoutOrNull(WORK_TIMEOUT_MS) { perform(context, intent) } }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun perform(context: Context, intent: Intent) {
        val container = context.appContainer
        val data = container.repository.snapshot.filterNotNull().first()
        val target = SystemTarget.resolve(data, ControlTarget.decode(intent.getStringExtra(EXTRA_TARGET)))
        val commands = when (intent.action) {
            ACTION_POWER -> {
                val on = when (intent.getStringExtra(EXTRA_POWER)) {
                    POWER_ON -> true
                    POWER_OFF -> false
                    else -> {
                        // In a cold process the saved states take a moment to load.
                        val states = withTimeoutOrNull(STATES_WAIT_MS) {
                            container.deviceStates.states.first { it.isNotEmpty() }
                        } ?: container.deviceStates.states.value
                        !target.isOn(states)
                    }
                }
                listOf(Command.Power(on))
            }
            ACTION_FAVOURITE -> {
                val id = intent.getStringExtra(EXTRA_FAVOURITE_ID)
                data.favourites.firstOrNull { it.id == id }?.commandsFromOutside() ?: return
            }
            else -> return
        }
        coroutineScope {
            target.targets.map { async { container.controller.sendAndAwait(it, commands, SEND_TIMEOUT_MS) } }.awaitAll()
        }
    }

    companion object {
        const val ACTION_POWER = "com.kernoeb.fff3.action.POWER"
        const val ACTION_FAVOURITE = "com.kernoeb.fff3.action.FAVOURITE"

        /** [POWER_ON], [POWER_OFF] or [POWER_TOGGLE]. */
        const val EXTRA_POWER = "com.kernoeb.fff3.extra.POWER"
        const val EXTRA_FAVOURITE_ID = "com.kernoeb.fff3.extra.FAVOURITE_ID"

        /** A [ControlTarget.encode] string. Without it, the command goes to every strip. */
        const val EXTRA_TARGET = "com.kernoeb.fff3.extra.TARGET"

        const val POWER_ON = "on"
        const val POWER_OFF = "off"
        const val POWER_TOGGLE = "toggle"

        // Under the 10 s a foreground broadcast may run.
        private const val WORK_TIMEOUT_MS = 9_500L
        private const val SEND_TIMEOUT_MS = 8_000L
        private const val STATES_WAIT_MS = 500L

        fun powerIntent(context: Context, power: String, target: ControlTarget? = null): Intent =
            Intent(context, SystemActionReceiver::class.java)
                .setAction(ACTION_POWER)
                .putExtra(EXTRA_POWER, power)
                .apply { if (target != null) putExtra(EXTRA_TARGET, target.encode()) }

        fun favouriteIntent(context: Context, favouriteId: String): Intent =
            Intent(context, SystemActionReceiver::class.java)
                .setAction(ACTION_FAVOURITE)
                .putExtra(EXTRA_FAVOURITE_ID, favouriteId)

        /** Sends from a visible entry point, with foreground priority. */
        fun sendNow(context: Context, intent: Intent) {
            context.sendBroadcast(intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
        }
    }
}
