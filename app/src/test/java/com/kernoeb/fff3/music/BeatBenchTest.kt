package com.kernoeb.fff3.music

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Scores Beat lock tempo on decoded songs. Opt-in: FFF3_BENCH="song.f32=114;other.f32=92",
 * with each song's true tempo; its half and double also count as right.
 * Prints, per song: share of time on the right tempo, on a wrong one, and the first right lock.
 */
class BeatBenchTest {

    @Test
    fun bench() {
        val spec = System.getenv("FFF3_BENCH")
        assumeTrue("set FFF3_BENCH to run the benchmark", spec != null)
        var totalRight = 0.0
        var totalWrong = 0.0
        var total = 0.0
        for (entry in spec!!.split(';')) {
            val (path, bpm) = entry.split('=')
            val score = score(File(path), bpm.toDouble())
            totalRight += score.right
            totalWrong += score.wrong
            total += score.seconds
            println(
                String.format(
                    Locale.ROOT, "BENCH %-24s right %5.1f%%  wrong %5.1f%%  first right %s",
                    File(path).name, 100 * score.right / score.seconds, 100 * score.wrong / score.seconds,
                    score.firstRight?.let { String.format(Locale.ROOT, "%.1f s", it) } ?: "never",
                ),
            )
        }
        println(String.format(Locale.ROOT, "BENCH TOTAL right %5.1f%%  wrong %5.1f%%", 100 * totalRight / total, 100 * totalWrong / total))
    }

    private class Score(val right: Double, val wrong: Double, val seconds: Double, val firstRight: Double?)

    private fun score(file: File, truth: Double): Score {
        val samples = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val analyzer = MusicAnalyzer(48_000, bandCount = MusicSync.BAND_COUNT, whiten = System.getenv("FFF3_WHITEN") != "0")
        val tracker = BeatTracker(analyzer.frameRate, analyzer.fftSize / 2.0 / 48_000)
        val hop = FloatArray(analyzer.hopSize)
        val dt = analyzer.hopSize / 48_000.0
        val length = samples.remaining() / 48_000.0
        var right = 0.0
        var wrong = 0.0
        var counted = 0.0
        var firstRight: Double? = null
        while (samples.remaining() >= hop.size) {
            samples.get(hop)
            val frame = analyzer.process(hop)
            tracker.process(frame.beatOnset, frame.rms, frame.rawBass, frame.rawMid, frame.rawTreble)
            val t = tracker.time
            val locked = tracker.state == BeatLockState.Locked
            val ok = locked && listOf(0.5, 1.0, 2.0).any { abs(tracker.bpm - truth * it) <= truth * it * 0.03 }
            if (ok && firstRight == null) firstRight = t
            // Skip the start, which every song needs to fill its window, and the fade at the end.
            if (t < START_S || t > length - END_S) continue
            counted += dt
            if (ok) right += dt else if (locked) wrong += dt
        }
        return Score(right, wrong, counted, firstRight)
    }

    private companion object {
        const val START_S = 15.0
        const val END_S = 10.0
    }
}
