package com.kernoeb.fff3.music

import com.kernoeb.fff3.data.BeatColors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Plays a real song through Beat lock, as the phone would, and prints one line per second.
 * Opt-in: `ffmpeg -i song.mp3 -ac 1 -ar 48000 -f f32le song.f32`, then run with FFF3_SONG=song.f32.
 * Several files separated by commas play back to back, as a playlist would.
 */
class SongReplayTest {

    @Test
    fun replay() {
        val path = System.getenv("FFF3_SONG")
        assumeTrue("set FFF3_SONG to replay a song", path != null)
        val bytes = path!!.split(',').map { File(it).readBytes() }.reduce { a, b -> a + b }
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val analyzer = MusicAnalyzer(48_000, bandCount = MusicSync.BAND_COUNT)
        val tracker = BeatTracker(analyzer.frameRate, analyzer.fftSize / 2.0 / 48_000)
        val renderer = LightRenderer()
        val hop = FloatArray(analyzer.hopSize)
        val dt = analyzer.hopSize / 48_000f
        var second = 0
        var peak = 0
        var low = 255
        var edges = 0
        var locks = 0
        println("time bpm state energy peak low beats")
        while (samples.remaining() >= hop.size) {
            samples.get(hop)
            val frame = analyzer.process(hop)
            tracker.process(frame.onset, frame.rms, frame.rawBass, frame.rawMid, frame.rawTreble)
            val color = renderer.render(frame, MusicStyle.BeatLock, dt, tracker, 0.0, BeatColors.Tempo)
            val bright = maxOf(color.red, color.green, color.blue)
            peak = maxOf(peak, bright)
            low = minOf(low, bright)
            if (renderer.beatEdge) edges++
            if (tracker.lockCount != locks) {
                locks = tracker.lockCount
                println(String.format(Locale.ROOT, "  relock at %.1f s: %s", tracker.time, tracker.lastReason))
            }
            if (tracker.time >= second + 1) {
                println(
                    String.format(
                        Locale.ROOT, "%4d %5d %-9s %.2f %3d %3d %d",
                        second, tracker.displayBpm, tracker.state, renderer.songEnergy, peak, low, edges,
                    ),
                )
                second++
                peak = 0
                low = 255
                edges = 0
            }
        }
    }
}
