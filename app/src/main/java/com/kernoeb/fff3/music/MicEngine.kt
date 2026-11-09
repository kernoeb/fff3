package com.kernoeb.fff3.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.roundToInt

/**
 * Reads the microphone on its own thread, analyses it and calls [onColor] about 30 times a second
 * with the colour to show. [onFailure] runs once, on that thread, if the microphone stops working.
 */
class MicEngine(
    private val context: Context,
    private val sync: MusicSync,
    private val baseColor: Rgb?,
    private val onColor: (Rgb) -> Unit,
    private val onFailure: (StopReason) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread(::run, "fff3-music").apply { start() }
    }

    /** Stops and waits for the thread, so no colour is sent after this returns. */
    fun stop() {
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(STOP_WAIT_MS)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(StopReason.MicPermissionMissing)
            return
        }
        val record = try {
            openRecord()
        } catch (_: Exception) {
            null
        }
        if (record == null) {
            fail(StopReason.MicUnavailable)
            return
        }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail(StopReason.MicUnavailable)
                return
            }
            loop(record)
        } finally {
            record.release()
        }
    }

    private fun loop(record: AudioRecord) {
        val analyzer = MusicAnalyzer(record.sampleRate, bandCount = MusicSync.BAND_COUNT)
        val hsv = FloatArray(3)
        baseColor?.let { android.graphics.Color.RGBToHSV(it.red, it.green, it.blue, hsv) }
        val renderer = if (baseColor != null && hsv[1] > 0.2f) LightRenderer(hsv[0], hsv[1]) else LightRenderer()
        val hop = FloatArray(analyzer.hopSize)
        val dt = analyzer.hopSize.toFloat() / record.sampleRate
        var lastSent: Rgb? = null
        var lastSentAt = 0L

        while (running) {
            var filled = 0
            while (filled < hop.size && running) {
                val read = record.read(hop, filled, hop.size - filled, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    fail(StopReason.MicUnavailable)
                    return
                }
                filled += read
            }
            if (!running) return

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

            val now = SystemClock.elapsedRealtime()
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

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val audio = context.getSystemService(AudioManager::class.java)
        // Unprocessed skips the noise suppression and automatic gain that flatten beats.
        val unprocessed = audio?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = listOfNotNull(
            MediaRecorder.AudioSource.UNPROCESSED.takeIf { unprocessed },
            MediaRecorder.AudioSource.MIC,
        )
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuffer <= 0) return null
        for (source in sources) {
            val record = AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, BUFFER_BYTES))
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        // 4 hops of 512 float samples: small enough for low latency, big enough to never overrun.
        const val BUFFER_BYTES = 512 * 4 * 4
        // Slightly faster than the link's 35 ms, so the link never waits for a new colour.
        const val SEND_INTERVAL_MS = 30L
        const val STOP_WAIT_MS = 1_000L
    }
}
