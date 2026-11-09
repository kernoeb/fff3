package com.kernoeb.fff3.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads [source] on its own thread, analyses it and calls [onColor] about 30 times a second
 * with the colour to show. [onFailure] runs once, on that thread, if the source stops working.
 */
class MusicEngine(
    private val context: Context,
    private val sync: MusicSync,
    private val source: AudioSource,
    private val baseColor: Rgb?,
    private val onColor: (Rgb) -> Unit,
    private val onFailure: (StopReason) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        sync.setNoAudio(false)
        thread = Thread(::run, "fff3-music").apply { start() }
    }

    /** Stops and waits for the thread, so no colour is sent after this returns. */
    fun stop() {
        running = false
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
        val hsv = FloatArray(3)
        baseColor?.let { android.graphics.Color.RGBToHSV(it.red, it.green, it.blue, hsv) }
        val renderer = if (baseColor != null && hsv[1] > 0.2f) LightRenderer(hsv[0], hsv[1]) else LightRenderer()
        val silence = SilenceDetector(SILENCE_HINT_MS)
        val hop = FloatArray(analyzer.hopSize)
        val dt = analyzer.hopSize.toFloat() / source.sampleRate
        var lastSent: Rgb? = null
        var lastSentAt = 0L

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
            val color = renderer.render(frame, settings.style, dt)

            val live = sync.live
            frame.bands.copyInto(live.bands)
            live.hueColor = renderer.fullColor.packed
            live.flash = renderer.beatFlash
            live.level = frame.level
            if (frame.bpm > 0f) sync.setBpm(frame.bpm.roundToInt())

            if (color != lastSent && now - lastSentAt >= SEND_INTERVAL_MS) {
                onColor(color)
                lastSent = color
                lastSentAt = now
            }
        }
    }

    private fun fail(reason: StopReason) {
        if (!running) return
        running = false
        onFailure(reason)
    }

    private companion object {
        // Slightly faster than the link's 35 ms, so the link never waits for a new colour.
        const val SEND_INTERVAL_MS = 30L
        const val STOP_WAIT_MS = 1_000L
        const val SILENCE_HINT_MS = 6_000L
    }
}
