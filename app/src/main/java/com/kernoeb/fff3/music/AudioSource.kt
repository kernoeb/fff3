package com.kernoeb.fff3.music

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock

/** Mono samples for [MusicEngine]. Opened, read and closed on the engine thread. */
interface AudioSource {
    /** Rate of the samples [read] gives. */
    val sampleRate: Int
    /** True when long digital silence likely means capture is blocked, not a quiet room. */
    val watchesSilence: Boolean
    /** Why music stops when this source fails. */
    val failure: StopReason

    fun open(): Boolean
    /** Fills all of [out] with samples in -1..1. Returns false when the input stopped working. */
    fun read(out: FloatArray): Boolean
    fun close()
}

/** The room, through the microphone. */
class MicSource(private val context: Context) : AudioSource {
    override val sampleRate = SAMPLE_RATE
    override val watchesSilence = false
    override val failure = StopReason.MicUnavailable
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
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
        if (minBuffer <= 0) return false
        for (source in sources) {
            val built = try {
                AudioRecord.Builder()
                    .setContext(context)
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer, BUFFER_BYTES))
                    .build()
            } catch (_: Exception) {
                continue
            }
            if (built.state == AudioRecord.STATE_INITIALIZED && built.tryStart()) {
                record = built
                return true
            }
            built.release()
        }
        return false
    }

    override fun read(out: FloatArray): Boolean {
        val record = record ?: return false
        var filled = 0
        while (filled < out.size) {
            val read = record.read(out, filled, out.size - filled, AudioRecord.READ_BLOCKING)
            if (read < 0) return false
            filled += read
        }
        return true
    }

    override fun close() {
        record?.release()
        record = null
    }
}

/** What the phone plays, captured through a [MediaProjection] the user agreed to. */
class PlaybackSource(private val context: Context, private val projection: MediaProjection) : AudioSource {
    override val sampleRate = SAMPLE_RATE
    override val watchesSilence = true
    override val failure = StopReason.CaptureUnavailable
    private var record: AudioRecord? = null
    private var converter: PcmConverter? = null
    private var floats = FloatArray(0)
    private var shorts: ShortArray? = null
    private var converted = FloatArray(0)
    private var pendingStart = 0
    private var pendingEnd = 0

    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        val capture = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (_: Exception) {
            return false
        }
        // Not every phone captures float or stereo, so try the common formats in turn.
        for ((encoding, mask, rate) in FORMATS) {
            val format = AudioFormat.Builder().setEncoding(encoding).setChannelMask(mask).setSampleRate(rate).build()
            val built = try {
                AudioRecord.Builder()
                    .setContext(context)
                    .setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(rate, mask, encoding), CAPTURE_BUFFER_BYTES))
                    .build()
            } catch (_: Exception) {
                continue
            }
            if (built.state == AudioRecord.STATE_INITIALIZED && built.tryStart()) {
                setUp(built)
                return true
            }
            built.release()
        }
        return false
    }

    private fun setUp(built: AudioRecord) {
        record = built
        val channels = built.channelCount.coerceAtLeast(1)
        val chunk = CHUNK_FRAMES * channels
        converter = PcmConverter(channels, built.sampleRate, SAMPLE_RATE).also {
            converted = FloatArray(it.maxOutput(chunk))
        }
        floats = FloatArray(chunk)
        shorts = if (built.audioFormat == AudioFormat.ENCODING_PCM_16BIT) ShortArray(chunk) else null
    }

    override fun read(out: FloatArray): Boolean {
        val record = record ?: return false
        val converter = converter ?: return false
        var filled = takePending(out, 0)
        val started = SystemClock.elapsedRealtime()
        while (filled < out.size) {
            // Non-blocking, so a capture that delivers nothing still counts as silence and stops fast.
            val shorts = shorts
            val read = if (shorts != null) {
                record.read(shorts, 0, shorts.size, AudioRecord.READ_NON_BLOCKING).also { count ->
                    for (i in 0 until count) floats[i] = shorts[i] / 32_768f
                }
            } else {
                record.read(floats, 0, floats.size, AudioRecord.READ_NON_BLOCKING)
            }
            if (read < 0) return false
            if (read == 0) {
                if (SystemClock.elapsedRealtime() - started > STALL_MS) {
                    out.fill(0f, filled)
                    return true
                }
                SystemClock.sleep(POLL_MS)
                continue
            }
            pendingStart = 0
            pendingEnd = converter.process(floats, read, converted)
            filled = takePending(out, filled)
        }
        return true
    }

    private fun takePending(out: FloatArray, from: Int): Int {
        val count = minOf(out.size - from, pendingEnd - pendingStart)
        converted.copyInto(out, from, pendingStart, pendingStart + count)
        pendingStart += count
        return from + count
    }

    override fun close() {
        record?.release()
        record = null
    }

    private companion object {
        val FORMATS = listOf(
            Triple(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.CHANNEL_IN_STEREO, 48_000),
            Triple(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_IN_STEREO, 48_000),
            Triple(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_IN_STEREO, 44_100),
            Triple(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_IN_MONO, 44_100),
        )
        const val CHUNK_FRAMES = 512
        const val CAPTURE_BUFFER_BYTES = 512 * 8 * 4
        const val STALL_MS = 100L
        const val POLL_MS = 4L
    }
}

private fun AudioRecord.tryStart(): Boolean = try {
    startRecording()
    recordingState == AudioRecord.RECORDSTATE_RECORDING
} catch (_: IllegalStateException) {
    false
}

private const val SAMPLE_RATE = 48_000
// 4 hops of 512 float samples: small enough for low latency, big enough to never overrun.
private const val BUFFER_BYTES = 512 * 4 * 4
