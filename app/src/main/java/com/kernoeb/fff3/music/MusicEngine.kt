package com.kernoeb.fff3.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kernoeb.fff3.ble.BleTuning
import com.kernoeb.fff3.ble.WriteLatency
import com.kernoeb.fff3.data.AudioOutput
import com.kernoeb.fff3.protocol.Rgb
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads [source] on its own thread, analyses it and calls [onColor] about 30 times a second
 * with the colour to show. [onFailure] runs once, on that thread, if the source stops working.
 *
 * Beat lock reads its clock ahead by the measured delays: for the strips, [cameraDelaysMs] (one per
 * target strip, null if not measured) plus [queueWait], else [linkLatency], which only covers the write; for
 * phone audio the output delay, from [outputDelaysMs] or measured with the microphone and given
 * back through [onOutputDelay] to be saved.
 */
class MusicEngine(
    private val context: Context,
    private val sync: MusicSync,
    private val source: AudioSource,
    private val baseColor: Rgb?,
    private val linkLatency: WriteLatency,
    private val queueWait: WriteLatency,
    private val cameraDelaysMs: List<Int?>,
    outputDelaysMs: Map<AudioOutput, Int>,
    private val onOutputDelay: (AudioOutput, Int) -> Unit,
    private val onColor: (Rgb) -> Unit,
    private val onFailure: (StopReason) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    private val knownDelays = IntArray(AudioOutput.entries.size) { -1 }.also { known ->
        outputDelaysMs.forEach { (output, millis) -> known[output.ordinal] = millis }
    }
    @Volatile private var outputChanged = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            outputChanged = true
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            outputChanged = true
        }
    }

    // Engine thread only.
    private var output: OutputDevice? = null
    private var outputDelay = 0.0
    private var outputMeasured = false
    private var needCalibration = false
    private var calibration: OutputCalibration? = null
    private var captureLag = -1.0

    fun start() {
        running = true
        sync.setNoAudio(false)
        if (source.capturesPlayback) audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        thread = Thread(::run, "fff3-music").apply { start() }
    }

    /** Stops and waits for the thread, so no colour is sent after this returns. */
    fun stop() {
        running = false
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        thread?.takeIf { it !== Thread.currentThread() }?.join(STOP_WAIT_MS)
        thread = null
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        // Playback capture needs this permission too, not only the microphone.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(StopReason.MicPermissionMissing)
            return
        }
        val opened = try {
            source.open()
        } catch (_: Exception) {
            false
        }
        try {
            if (!opened) {
                fail(source.failure)
                return
            }
            loop()
        } finally {
            source.close()
        }
    }

    private fun loop() {
        val analyzer = MusicAnalyzer(source.sampleRate, bandCount = MusicSync.BAND_COUNT)
        val tracker = BeatTracker(analyzer.frameRate, analyzer.fftSize / 2.0 / source.sampleRate)
        val hsv = FloatArray(3)
        baseColor?.let { android.graphics.Color.RGBToHSV(it.red, it.green, it.blue, hsv) }
        val renderer = if (baseColor != null && hsv[1] > 0.2f) LightRenderer(hsv[0], hsv[1]) else LightRenderer()
        val silence = SilenceDetector(SILENCE_HINT_MS)
        val hop = FloatArray(analyzer.hopSize)
        val dt = analyzer.hopSize.toFloat() / source.sampleRate
        var lastSent: Rgb? = null
        var lastSentAt = 0L
        var shownLocked = false
        var shownBpm = 0
        var latencyShownAt = Long.MIN_VALUE / 2
        var loggedAt = 0L
        var loggedLocks = 0
        if (source.capturesPlayback) useOutput(currentOutput(context))

        try {
            while (running) {
                if (!source.read(hop)) {
                    fail(source.failure)
                    return
                }
                if (!running) return

                val now = SystemClock.elapsedRealtime()
                if (source.watchesSilence) {
                    var peak = 0f
                    for (sample in hop) peak = maxOf(peak, abs(sample))
                    val silent = silence.update(peak, now)
                    if (silent != sync.noAudio.value) sync.setNoAudio(silent)
                }

                val settings = sync.settings.value
                analyzer.sensitivity = settings.sensitivity
                val frame = analyzer.process(hop)
                tracker.process(frame.onset, frame.rms, frame.rawBass, frame.rawMid, frame.rawTreble)

                val lag = (System.nanoTime() - source.lastSampleNanos) / 1e9
                captureLag = if (captureLag < 0) lag else captureLag + (lag - captureLag) * CAPTURE_SMOOTHING
                val link = LightTiming.linkSeconds(cameraDelaysMs, queueWait.medianMillis, linkLatency.medianMillis)
                var latencyChanged = handleCommands(tracker)
                if (outputChanged) {
                    outputChanged = false
                    val current = currentOutput(context)
                    if (current != output) {
                        useOutput(current)
                        latencyChanged = true
                    }
                }
                latencyChanged = calibrate(analyzer.frameRate, tracker, frame.onset, settings.style) || latencyChanged

                val lead = LightTiming.lead(link, captureLag, outputDelay, settings.lightDelayMs / 1000.0)
                val color = renderer.render(frame, settings.style, dt, tracker, lead, settings.beatColors)

                val live = sync.live
                frame.bands.copyInto(live.bands)
                live.hueColor = renderer.fullColor.packed
                live.flash = renderer.beatFlash
                live.level = frame.level
                live.beatPhase = renderer.beatPhase
                live.beatInBar = renderer.beatInBar
                if (frame.bpm > 0f) sync.setBpm(frame.bpm.roundToInt())

                val locked = tracker.state == BeatLockState.Locked
                val bpm = tracker.displayBpm
                if (locked != shownLocked || bpm != shownBpm) {
                    shownLocked = locked
                    shownBpm = bpm
                    sync.setBeat(BeatStatus(locked, bpm))
                }
                if (latencyChanged || now - latencyShownAt >= LATENCY_SHOW_MS) {
                    latencyShownAt = now
                    sync.setLatency(
                        LatencyStatus(
                            output = output,
                            outputMs = (outputDelay * 1000).roundToInt(),
                            outputMeasured = outputMeasured,
                            captureMs = (captureLag * 1000).roundToInt(),
                            lightsMs = (link * 1000).roundToInt(),
                            calibrating = calibration != null,
                        ),
                    )
                }

                if (now - loggedAt >= LOG_EVERY_MS) {
                    loggedAt = now
                    val relocked = tracker.lockCount != loggedLocks
                    loggedLocks = tracker.lockCount
                    logBeat(tracker, if (relocked) tracker.lastReason else null, link)
                }

                // The link sends one colour per slot: keep it free just before a beat, so the beat goes out at once.
                val holdForBeat = settings.style == MusicStyle.BeatLock && renderer.untilBeat < HOLD_BEFORE_BEAT_S
                val due = renderer.beatEdge || (!holdForBeat && now - lastSentAt >= SEND_INTERVAL_MS)
                if (color != lastSent && due) {
                    onColor(color)
                    lastSent = color
                    lastSentAt = now
                }
            }
        } finally {
            calibration?.cancel()
            calibration = null
        }
    }

    /** One line a second, to read with `adb logcat -s fff3-beat` after playing real music. */
    private fun logBeat(tracker: BeatTracker, relock: RelockReason?, link: Double) {
        Log.d(
            LOG_TAG,
            String.format(
                Locale.ROOT,
                "t=%.0f est=%.1f conf=%.2f short=%.1f locked=%.2f shown=%d state=%s coast=%b novelty=%.1f challenge=%.1f queue=%d link=%.0f%s",
                tracker.time, tracker.heardBpm, tracker.heardConfidence, tracker.shortBpm, tracker.bpm,
                tracker.displayBpm, tracker.state, tracker.coasting, tracker.novelty,
                tracker.challengeSeconds, queueWait.medianMillis, link * 1000, if (relock != null) " relock=$relock" else "",
            ),
        )
    }

    /** Applies what the screen asked. Returns true when the shown delays may have changed. */
    private fun handleCommands(tracker: BeatTracker): Boolean {
        var changed = false
        while (true) {
            when (val command = sync.commands.poll() ?: return changed) {
                // The user taps on what they hear, which is the captured sound played after the output delay.
                is BeatCommand.Tap -> tracker.tap(tracker.time + (command.nanos - System.nanoTime()) / 1e9 + captureLag - outputDelay)
                is BeatCommand.ScaleTempo -> tracker.scaleTempo(command.factor)
                BeatCommand.Recalibrate -> if (source.capturesPlayback) {
                    calibration?.cancel()
                    calibration = null
                    needCalibration = true
                    changed = true
                }
            }
        }
    }

    private fun useOutput(device: OutputDevice) {
        output = device
        val known = knownDelays[device.kind.ordinal]
        outputMeasured = known >= 0
        outputDelay = if (known >= 0) known / 1000.0 else defaultDelay(device.kind)
        calibration?.cancel()
        calibration = null
        needCalibration = true
    }

    /**
     * Measures the output delay once beat lock has a beat to listen for, since the microphone
     * only helps when music plays. Returns true when the shown delays may have changed.
     */
    private fun calibrate(frameRate: Float, tracker: BeatTracker, onset: Float, style: MusicStyle): Boolean {
        val current = calibration
        if (current == null) {
            if (output == null || !needCalibration) return false
            if (style != MusicStyle.BeatLock || tracker.state != BeatLockState.Locked) return false
            calibration = OutputCalibration(context, frameRate).also { it.start() }
            needCalibration = false
            return true
        }
        if (!current.finished) {
            current.addPlayback(source.lastSampleNanos, onset)
            return false
        }
        calibration = null
        val measured = current.result()
        val device = output
        if (!measured.isNaN() && device != null) {
            val millis = (measured * 1000).roundToInt()
            outputDelay = millis / 1000.0
            outputMeasured = true
            knownDelays[device.kind.ordinal] = millis
            onOutputDelay(device.kind, millis)
        }
        return true
    }

    private fun fail(reason: StopReason) {
        if (!running) return
        running = false
        onFailure(reason)
    }

    private companion object {
        // Slightly faster than the link's 35 ms, so the link never waits for a new colour.
        const val SEND_INTERVAL_MS = 30L
        const val HOLD_BEFORE_BEAT_S = BleTuning.MIN_FRAME_INTERVAL_MS / 1000.0
        const val CAPTURE_SMOOTHING = 0.02
        const val LATENCY_SHOW_MS = 1_000L
        const val LOG_EVERY_MS = 1_000L
        const val LOG_TAG = "fff3-beat"

        /** A typical Bluetooth audio delay, used until one is measured: better than none. */
        fun defaultDelay(output: AudioOutput) = if (output == AudioOutput.Bluetooth) 0.18 else 0.0
        const val STOP_WAIT_MS = 1_000L
        const val SILENCE_HINT_MS = 6_000L
    }
}
