package com.kernoeb.fff3.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.kernoeb.fff3.data.AudioOutput

/**
 * Measures how long the phone takes to play the sound we capture: listens to the microphone for
 * a few seconds while phone audio is captured, then finds the lag between the 2 onset envelopes.
 * The engine calls [addPlayback] on its thread; the microphone runs on a thread of its own.
 */
class OutputCalibration(private val context: Context, private val frameRate: Float) {

    private val capacity = (frameRate * (LISTEN_S + 1)).toInt()
    private val playbackTimes = LongArray(capacity)
    private val playbackOnsets = FloatArray(capacity)
    private var playbackCount = 0
    private val micTimes = LongArray(capacity)
    private val micOnsets = FloatArray(capacity)
    private var micCount = 0
    private val early = FloatArray(capacity)
    private val late = FloatArray(capacity)
    private val minLag = (MIN_DELAY_S * frameRate).toInt()
    private val maxLag = (MAX_DELAY_S * frameRate).toInt()
    private val scores = FloatArray(maxLag - minLag + 1)

    @Volatile private var cancelled = false
    @Volatile private var thread: Thread? = null

    /** True once the microphone part is over; then call [result]. */
    @Volatile var finished = false
        private set

    fun start() {
        thread = Thread(::listen, "fff3-calibration").apply { start() }
    }

    fun cancel() {
        cancelled = true
    }

    /** The onset of one hop of captured phone audio and when its last sample was captured. */
    fun addPlayback(timeNanos: Long, onset: Float) {
        if (finished || playbackCount >= capacity) return
        playbackTimes[playbackCount] = timeNanos
        playbackOnsets[playbackCount] = onset
        playbackCount++
    }

    /** The output delay in seconds, or NaN when the microphone did not clearly hear the music. */
    fun result(): Double {
        thread?.join(JOIN_MS)
        if (playbackCount < MIN_FRAMES || micCount < MIN_FRAMES) return Double.NaN
        val start = maxOf(playbackTimes[0], micTimes[0])
        val end = minOf(playbackTimes[playbackCount - 1], micTimes[micCount - 1])
        val step = 1_000_000_000.0 / frameRate
        val count = ((end - start) / step).toInt().coerceAtMost(capacity)
        if (count < MIN_FRAMES) return Double.NaN
        resample(playbackTimes, playbackOnsets, playbackCount, start, step, count, early)
        resample(micTimes, micOnsets, micCount, start, step, count, late)
        val lag = DelayCorrelation.findLag(early, late, count, minLag, maxLag, scores)
        return if (lag.isNaN()) Double.NaN else (lag / frameRate).toDouble().coerceAtLeast(0.0)
    }

    private fun listen() {
        val mic = MicSource(context)
        try {
            if (!mic.open()) return
            val analyzer = MusicAnalyzer(mic.sampleRate)
            val hop = FloatArray(analyzer.hopSize)
            while (!cancelled && micCount < capacity) {
                if (!mic.read(hop)) break
                micOnsets[micCount] = analyzer.process(hop).onset
                micTimes[micCount] = mic.lastSampleNanos
                micCount++
                if (micTimes[micCount - 1] - micTimes[0] >= (LISTEN_S * 1e9).toLong()) break
            }
        } catch (_: Exception) {
            // Another app may hold the microphone: no measurement this time.
        } finally {
            mic.close()
            finished = true
        }
    }

    /** Puts [count] values on an even grid from [start], by linear interpolation between stamped frames. */
    private fun resample(times: LongArray, values: FloatArray, size: Int, start: Long, step: Double, count: Int, out: FloatArray) {
        var j = 0
        for (i in 0 until count) {
            val t = start + i * step
            while (j < size - 2 && times[j + 1] < t) j++
            val span = (times[j + 1] - times[j]).toDouble()
            val f = if (span > 0) ((t - times[j]) / span).coerceIn(0.0, 1.0).toFloat() else 0f
            out[i] = values[j] + (values[j + 1] - values[j]) * f
        }
    }

    private companion object {
        const val LISTEN_S = 5f
        const val MIN_FRAMES = 200
        const val MIN_DELAY_S = -0.05f
        const val MAX_DELAY_S = 0.6f
        const val JOIN_MS = 500L
    }
}

/** The output media plays on now, and a name to show for it. */
data class OutputDevice(val kind: AudioOutput, val name: String?)

internal fun currentOutput(context: Context): OutputDevice {
    val audio = context.getSystemService(AudioManager::class.java) ?: return OutputDevice(AudioOutput.Other, null)
    val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    val device = audio.getAudioDevicesForAttributes(media).firstOrNull() ?: return OutputDevice(AudioOutput.Speaker, null)
    val kind = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioOutput.Speaker
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> AudioOutput.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
        -> AudioOutput.Bluetooth
        else -> AudioOutput.Other
    }
    val name = device.productName?.toString()?.takeIf { it.isNotBlank() && kind != AudioOutput.Speaker }
    return OutputDevice(kind, name)
}
