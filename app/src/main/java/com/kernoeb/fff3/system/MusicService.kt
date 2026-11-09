package com.kernoeb.fff3.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.kernoeb.fff3.MainActivity
import com.kernoeb.fff3.R
import com.kernoeb.fff3.appContainer
import com.kernoeb.fff3.ble.ConnectionState
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedSession
import com.kernoeb.fff3.data.AppData
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.music.MicEngine
import com.kernoeb.fff3.music.MusicStatus
import com.kernoeb.fff3.music.StopReason
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.ui.navigation.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Phone music mode: listens to the microphone and drives the strips while the app is in the background.
 * Start it with [start] while the app is visible: Android does not let a microphone service start
 * from the background. It restores each strip's previous colour when it stops.
 */
class MusicService : Service() {

    private val container by lazy { appContainer }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var run: Job? = null
    private var engine: MicEngine? = null
    private var session: LedSession? = null
    private var previous: Map<String, LedState?> = emptyMap()
    private var stopReason = StopReason.User

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val target = ControlTarget.decode(intent.getStringExtra(EXTRA_TARGET))
                if (!enterForeground(target)) return START_NOT_STICKY
                begin(target)
            }
            else -> finish(StopReason.User)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        endRun()
        session?.close()
        session = null
        scope.cancel()
        container.music.setStatus(MusicStatus.Stopped(stopReason))
        super.onDestroy()
    }

    private fun enterForeground(target: ControlTarget?): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish(StopReason.MicPermissionMissing)
            return false
        }
        val notification = notification(target)
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (_: Exception) {
            // Thrown when started from the background, or when a type's permission is missing.
            finish(StopReason.MicUnavailable)
            false
        }
    }

    private fun begin(target: ControlTarget?) {
        endRun()
        if (session == null) session = container.controller.openSession()
        run = scope.launch {
            val data = container.repository.snapshot.filterNotNull().first()
            val addresses = addressesOf(data, target)
            if (addresses.isEmpty()) {
                finish(StopReason.StripsGone)
                return@launch
            }
            val states = container.deviceStates.states.value
            previous = addresses.associateWith { states[it] }
            addresses.forEach { container.controller.send(ControlTarget.Device(it), Command.Power(true)) }

            val baseColor = addresses.firstNotNullOfOrNull { states[it]?.color }
            val controller = container.controller
            val mic = MicEngine(
                context = this@MusicService,
                sync = container.music,
                baseColor = baseColor,
                onColor = { rgb -> addresses.forEach { controller.send(ControlTarget.Device(it), Command.Color(rgb)) } },
                onFailure = { reason -> scope.launch { finish(reason) } },
            )
            engine = mic
            mic.start()
            container.music.setStatus(MusicStatus.Running(target))
            watchStrips(target)
        }
    }

    /** Stops when the strips are removed, Bluetooth goes off, or no strip is reachable for a while. */
    private suspend fun watchStrips(target: ControlTarget?) {
        combine(container.repository.snapshot.filterNotNull(), container.controller.connectionStates) { data, links ->
            addressesOf(data, target).map { links[it] ?: ConnectionState.Idle }
        }.collectLatest { links ->
            when {
                links.isEmpty() -> finish(StopReason.StripsGone)
                links.all { it == ConnectionState.BluetoothOff } -> finish(StopReason.BluetoothOff)
                links.all { it == ConnectionState.PermissionMissing } -> finish(StopReason.BluetoothPermissionMissing)
                links.none { it == ConnectionState.Connected } -> {
                    delay(UNREACHABLE_STOP_MS)
                    finish(StopReason.StripsGone)
                }
            }
        }
    }

    /** Stops the engine and puts every strip back as it was before music started. */
    private fun endRun() {
        run?.cancel()
        run = null
        engine?.stop()
        engine = null
        val controller = container.controller
        for ((address, state) in previous) {
            val restore = buildList {
                when {
                    state?.effectId != null -> add(Command.Effect(state.effectId, state.speed))
                    state?.sceneId != null -> add(Command.Scene(state.sceneId))
                    state?.color != null -> add(Command.Color(state.color))
                }
                if (state?.power == false) add(Command.Power(false))
            }
            if (restore.isNotEmpty()) controller.send(ControlTarget.Device(address), restore)
        }
        previous = emptyMap()
    }

    private fun finish(reason: StopReason) {
        stopReason = reason
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun addressesOf(data: AppData, target: ControlTarget?): List<String> {
        val known = data.devices.map { it.address }
        return when (target) {
            null -> known
            is ControlTarget.Device -> listOf(target.address).filter { it in known }
            is ControlTarget.Group -> data.groups.firstOrNull { it.id == target.id }
                ?.memberAddresses.orEmpty().filter { it in known }
        }
    }

    private fun notification(target: ControlTarget?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.music_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.music_channel_body)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.intentFor(this, Route.Music(target)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MusicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_graphic_eq)
            .setContentTitle(getString(R.string.music_notification_title))
            .setContentText(getString(R.string.music_notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.music_stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "com.kernoeb.fff3.music.START"
        private const val ACTION_STOP = "com.kernoeb.fff3.music.STOP"
        private const val EXTRA_TARGET = "target"
        private const val CHANNEL_ID = "music"
        private const val NOTIFICATION_ID = 7301
        private const val UNREACHABLE_STOP_MS = 45_000L

        /** Starts music sync on [target], or on every strip when null. Call while the app is visible. */
        fun start(context: Context, target: ControlTarget?) {
            context.startForegroundService(
                Intent(context, MusicService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TARGET, target?.encode()),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MusicService::class.java).setAction(ACTION_STOP))
        }
    }
}
