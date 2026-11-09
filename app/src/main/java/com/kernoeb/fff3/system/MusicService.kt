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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.kernoeb.fff3.MainActivity
import com.kernoeb.fff3.R
import com.kernoeb.fff3.appContainer
import com.kernoeb.fff3.ble.ConnectionState
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.LedSession
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.data.MusicSource
import com.kernoeb.fff3.music.MicSource
import com.kernoeb.fff3.music.MusicEngine
import com.kernoeb.fff3.music.musicRestoreCommands
import com.kernoeb.fff3.music.PlaybackSource
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Phone music mode: listens to what the phone plays, or to the microphone, and drives the strips
 * while the app is in the background. Start it with [start] while the app is visible: Android does
 * not let a microphone or capture service start from the background. It restores each strip's
 * previous colour when it stops.
 */
class MusicService : Service() {

    private val container by lazy { appContainer }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var run: Job? = null
    private var engine: MusicEngine? = null
    private var session: LedSession? = null
    private var previous: Map<String, LedState?> = emptyMap()
    private var stopReason = StopReason.User
    private var stopping = false
    private var foregroundTypes = 0
    private var target: ControlTarget? = null
    private var projection: MediaProjection? = null

    // We unregister before stopping a projection ourselves, so this only hears outside stops.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            finish(StopReason.CaptureEnded)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val source = intent.getStringExtra(EXTRA_SOURCE)
                    ?.let { name -> MusicSource.entries.firstOrNull { it.name == name } }
                    ?: MusicSource.Microphone
                val consent = intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
                val consentCode = intent.getIntExtra(EXTRA_CONSENT_CODE, 0)
                start(ControlTarget.decode(intent.getStringExtra(EXTRA_TARGET)), source, consent, consentCode)
            }
            ACTION_USE_MIC -> {
                container.appScope.launch {
                    container.repository.updateSettings { it.copy(musicSource = MusicSource.Microphone) }
                }
                if (run != null) start(target, MusicSource.Microphone, null, 0) else finish(StopReason.User)
            }
            else -> finish(StopReason.User)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        endRun()
        stopProjection()
        // A notification posted by a late update must not outlive the service.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        session?.close()
        session = null
        scope.cancel()
        container.music.setStatus(MusicStatus.Stopped(stopReason))
        super.onDestroy()
    }

    /**
     * Phone audio needs a fresh [consent] for each new projection; a restart for another target
     * reuses the projection we hold. The foreground type must include media projection before
     * [MediaProjectionManager.getMediaProjection] is called.
     */
    private fun start(target: ControlTarget?, source: MusicSource, consent: Intent?, consentCode: Int) {
        stopping = false
        // The engine must stop before its projection, or it reads a dead capture and fails.
        endRun()
        if (source == MusicSource.Microphone) stopProjection()
        if (source == MusicSource.PhoneAudio && consent == null && projection == null) {
            finishBeforeForeground(StopReason.CaptureEnded)
            return
        }
        if (!enterForeground(target, source)) return
        if (consent != null) {
            stopProjection()
            projection = obtainProjection(consentCode, consent) ?: run {
                finish(StopReason.CaptureUnavailable)
                return
            }
        }
        begin(target, source)
    }

    private fun enterForeground(target: ControlTarget?, source: MusicSource): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishBeforeForeground(StopReason.MicPermissionMissing)
            return false
        }
        // The microphone type stays for phone audio too, so switching to the microphone later
        // needs no new foreground start from the background.
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (source == MusicSource.PhoneAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        return try {
            startForeground(NOTIFICATION_ID, notification(target, source, noAudio = false), types)
            foregroundTypes = types
            true
        } catch (_: Exception) {
            // Thrown when started from the background, or when a type's permission or consent is missing.
            // A running service that already holds every needed type can go on.
            if (foregroundTypes != 0 && (foregroundTypes and types) == types) return true
            finishBeforeForeground(
                if (source == MusicSource.PhoneAudio) StopReason.CaptureUnavailable else StopReason.MicUnavailable,
            )
            false
        }
    }

    private fun obtainProjection(code: Int, consent: Intent): MediaProjection? = try {
        getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, consent)
            ?.also { it.registerCallback(projectionCallback, Handler(Looper.getMainLooper())) }
    } catch (_: Exception) {
        null
    }

    private fun stopProjection() {
        val current = projection ?: return
        projection = null
        current.unregisterCallback(projectionCallback)
        try {
            current.stop()
        } catch (_: Exception) {
            // Already stopped by the system.
        }
    }

    private fun begin(target: ControlTarget?, source: MusicSource) {
        endRun()
        this.target = target
        if (session == null) session = container.controller.openSession()
        run = scope.launch {
            val data = container.repository.snapshot.filterNotNull().first()
            val addresses = data.addressesOf(target)
            if (addresses.isEmpty()) {
                finish(StopReason.StripsGone)
                return@launch
            }
            val audio = when (source) {
                MusicSource.Microphone -> MicSource(this@MusicService)
                MusicSource.PhoneAudio -> PlaybackSource(this@MusicService, projection ?: run {
                    finish(StopReason.CaptureEnded)
                    return@launch
                })
            }
            val states = container.deviceStates.states.value
            previous = addresses.associateWith { states[it] }
            // Music light is not recorded, so the saved state stays the user's; endRun restores and records it.
            addresses.forEach { container.controller.send(ControlTarget.Device(it), Command.Power(true), record = false) }

            val baseColor = addresses.firstNotNullOfOrNull { states[it]?.color }
            val controller = container.controller
            val engine = MusicEngine(
                context = this@MusicService,
                sync = container.music,
                source = audio,
                baseColor = baseColor,
                linkLatency = container.linkLatency,
                queueWait = container.queueWait,
                cameraDelaysMs = addresses.map { data.settings.lightDelaysMs[it] },
                outputDelaysMs = data.settings.outputDelaysMs,
                onOutputDelay = { output, millis ->
                    container.appScope.launch {
                        container.repository.updateSettings { it.copy(outputDelaysMs = it.outputDelaysMs + (output to millis)) }
                    }
                },
                onColor = { rgb -> addresses.forEach { controller.send(ControlTarget.Device(it), Command.Color(rgb), record = false) } },
                onFailure = { reason -> scope.launch { finish(reason) } },
            )
            this@MusicService.engine = engine
            engine.start()
            container.music.setStatus(MusicStatus.Running(target, source))
            if (source == MusicSource.PhoneAudio) {
                launch {
                    container.music.noAudio.collect { silent -> showNotification(target, source, silent) }
                }
            }
            watchStrips(target)
        }
    }

    /** Stops when the strips are removed, Bluetooth goes off, or no strip is reachable for a while. */
    private suspend fun watchStrips(target: ControlTarget?) {
        combine(container.repository.snapshot.filterNotNull(), container.controller.connectionStates) { data, links ->
            val states = data.addressesOf(target).map { links[it] ?: ConnectionState.Idle }
            when {
                states.isEmpty() -> StopReason.StripsGone to 0L
                states.all { it == ConnectionState.BluetoothOff } -> StopReason.BluetoothOff to 0L
                states.all { it == ConnectionState.PermissionMissing } -> StopReason.BluetoothPermissionMissing to 0L
                states.none { it == ConnectionState.Connected } -> StopReason.StripsGone to UNREACHABLE_STOP_MS
                else -> null
            }
        }.distinctUntilChanged().collectLatest { stop ->
            // Retries change the link state often; only a new outcome restarts the wait.
            if (stop == null) return@collectLatest
            delay(stop.second)
            finish(stop.first)
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
            controller.send(ControlTarget.Device(address), musicRestoreCommands(state))
        }
        previous = emptyMap()
    }

    /** After startForegroundService, stopping without startForeground still crashes the app. */
    private fun finishBeforeForeground(reason: StopReason) {
        if (foregroundTypes == 0) {
            try {
                // This type only needs Bluetooth, which the companion start checks first.
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                startForeground(NOTIFICATION_ID, notification(target, MusicSource.Microphone, noAudio = false), types)
            } catch (_: Exception) {
                // Nothing else to try: stop anyway.
            }
        }
        finish(reason)
    }

    private fun finish(reason: StopReason) {
        stopping = true
        stopReason = reason
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification(target: ControlTarget?, source: MusicSource, noAudio: Boolean) {
        if (stopping || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(target, source, noAudio))
    }

    private fun notification(target: ControlTarget?, source: MusicSource, noAudio: Boolean): Notification {
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
        val body = getString(
            when {
                noAudio -> R.string.music_no_audio
                source == MusicSource.PhoneAudio -> R.string.music_notification_body_phone
                else -> R.string.music_notification_body
            },
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_graphic_eq)
            .setContentTitle(getString(R.string.music_notification_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (noAudio) {
            val useMic = PendingIntent.getService(
                this,
                2,
                Intent(this, MusicService::class.java).setAction(ACTION_USE_MIC),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, getString(R.string.music_use_microphone), useMic).build())
        }
        return builder
            .addAction(Notification.Action.Builder(null, getString(R.string.music_stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "com.kernoeb.fff3.music.START"
        private const val ACTION_STOP = "com.kernoeb.fff3.music.STOP"
        private const val ACTION_USE_MIC = "com.kernoeb.fff3.music.USE_MIC"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_CONSENT = "consent"
        private const val EXTRA_CONSENT_CODE = "consent_code"
        private const val CHANNEL_ID = "music"
        private const val NOTIFICATION_ID = 7301
        private const val UNREACHABLE_STOP_MS = 45_000L

        /**
         * Starts music sync on [target], or on every strip when null. Call while the app is visible.
         * For phone audio, pass the result of the capture consent screen, or null to keep the
         * capture already running (when only the target changes).
         */
        fun start(
            context: Context,
            target: ControlTarget?,
            source: MusicSource,
            consentCode: Int = 0,
            consent: Intent? = null,
        ) {
            // startForeground throws without these, and a service that never reaches it crashes the app.
            val missing = when {
                !context.isGranted(Manifest.permission.RECORD_AUDIO) -> StopReason.MicPermissionMissing
                !context.isGranted(Manifest.permission.BLUETOOTH_CONNECT) -> StopReason.BluetoothPermissionMissing
                else -> null
            }
            if (missing != null) {
                context.appContainer.music.setStatus(MusicStatus.Stopped(missing))
                return
            }
            context.startForegroundService(
                Intent(context, MusicService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TARGET, target?.encode())
                    .putExtra(EXTRA_SOURCE, source.name)
                    .putExtra(EXTRA_CONSENT_CODE, consentCode)
                    .putExtra(EXTRA_CONSENT, consent),
            )
        }

        private fun Context.isGranted(permission: String) =
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        /** The intent for the capture consent screen. It lets the user share one app or the whole screen where Android can. */
        fun captureConsentIntent(context: Context): Intent {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                manager.createScreenCaptureIntent()
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MusicService::class.java).setAction(ACTION_STOP))
        }
    }
}
