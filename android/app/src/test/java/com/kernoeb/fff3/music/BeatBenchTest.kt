package com.kernoeb.fff3.music

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Scores Beat lock tempo on decoded songs. Opt-in: FFF3_BENCH="song.f32=114;other.f32=92",
 * with each song's true tempo; its half and double also count as right. "song.f32=114@40"
 * starts listening 40 s into the song, as when the app starts mid-song.
 * Prints, per song: share of time on the right tempo, on a wrong one, the first right lock, and
 * how often the long window itself hears the right or a wrong tempo.
 * FFF3_BENCH_LOCKS=1 also prints every lock; FFF3_BEATS_DIR=dir writes each song's beat times there.
 */
class BeatBenchTest {

    @Test
    fun bench() {
        val spec = System.getenv("FFF3_BENCH")
        assumeTrue("set FFF3_BENCH to run the benchmark", spec != null)
        var totalRight = 0.0
        var totalWrong = 0.0
        var total = 0.0
        // Songs are independent, so score them in parallel; print in the given order.
        val entries = spec!!.split(';').map { it.split('=') }
        val scores = entries.parallelStream().map { (path, truth) ->
            score(File(path), truth.substringBefore('@').toDouble(), truth.substringAfter('@', "0").toDouble())
        }.toList()
        for ((entry, score) in entries.zip(scores)) {
            totalRight += score.right
            totalWrong += score.wrong
            total += score.seconds
            val start = entry[1].substringAfter('@', "").let { if (it.isEmpty()) "" else "@$it" }
            println(
                String.format(
                    Locale.ROOT, "BENCH %-24s right %5.1f%%  wrong %5.1f%%  seconds %5.1f  first right %s  heard %5.1f%% %5.1f%%",
                    File(entry[0]).name + start, 100 * score.right / score.seconds, 100 * score.wrong / score.seconds, score.seconds,
                    score.firstRight?.let { String.format(Locale.ROOT, "%.1f s", it) } ?: "never",
                    100 * score.heardRight / score.seconds, 100 * score.heardWrong / score.seconds,
                ),
            )
        }
        println(String.format(Locale.ROOT, "BENCH TOTAL right %5.1f%%  wrong %5.1f%%", 100 * totalRight / total, 100 * totalWrong / total))
    }

    private class Score(
        val right: Double,
        val wrong: Double,
        val seconds: Double,
        val firstRight: Double?,
        val heardRight: Double,
        val heardWrong: Double,
    )

    private fun score(file: File, truth: Double, startAt: Double): Score {
        val samples = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        samples.position((startAt * 48_000).toInt() / 512 * 512)
        // The start snaps to a whole hop, so beat times count from where listening really began.
        val startSeconds = samples.position() / 48_000.0
        val analyzer = MusicAnalyzer(48_000, bandCount = MusicSync.BAND_COUNT, whiten = System.getenv("FFF3_WHITEN") != "0")
        val tracker = BeatTracker(analyzer.frameRate, analyzer.fftSize / 2.0 / 48_000)
        val hop = FloatArray(analyzer.hopSize)
        val dt = analyzer.hopSize / 48_000.0
        val length = samples.remaining() / 48_000.0
        fun isRight(bpm: Double) = listOf(0.5, 1.0, 2.0).any { abs(bpm - truth * it) <= truth * it * 0.03 }
        var right = 0.0
        var wrong = 0.0
        var counted = 0.0
        var heardRight = 0.0
        var heardWrong = 0.0
        var firstRight: Double? = null
        val printLocks = System.getenv("FFF3_BENCH_LOCKS") != null
        var locks = 0
        val beatsOut = System.getenv("FFF3_BEATS_DIR")?.let { File(it, "${file.name}@${startAt.toInt()}.txt").printWriter() }
        var lastBeat = Long.MIN_VALUE
        while (samples.remaining() >= hop.size) {
            samples.get(hop)
            val frame = analyzer.process(hop)
            tracker.process(frame.beatOnset, frame.rms, frame.rawBass, frame.rawMid, frame.rawTreble)
            val t = tracker.time
            val locked = tracker.state == BeatLockState.Locked
            val ok = locked && isRight(tracker.bpm.toDouble())
            if (printLocks && tracker.lockCount != locks) {
                locks = tracker.lockCount
                println(
                    String.format(
                        Locale.ROOT, "LOCK %-22s %6.1f s %-10s %6.1f conf %.2f %s",
                        "${file.name}@${startAt.toInt()}", t, tracker.lastReason, tracker.bpm, tracker.heardConfidence, if (ok) "RIGHT" else "WRONG",
                    ),
                )
            }
            if (beatsOut != null && locked) {
                // Song time of each beat the clock gives, with its tempo, to check the phase offline.
                val beats = tracker.beatsAt(t)
                val whole = floor(beats).toLong()
                if (whole != lastBeat) {
                    lastBeat = whole
                    beatsOut.println(String.format(Locale.ROOT, "%.5f %.3f", startSeconds + t - (beats - whole) * tracker.period, tracker.bpm))
                }
            }
            if (ok && firstRight == null) firstRight = t
            // Skip the start, which every song needs to fill its window, and the fade at the end.
            if (t < START_S || t > length - END_S) continue
            counted += dt
            if (ok) right += dt else if (locked) wrong += dt
            val heard = tracker.heardBpm.toDouble()
            if (heard > 0) {
                if (isRight(heard)) heardRight += dt else heardWrong += dt
            }
        }
        beatsOut?.close()
        return Score(right, wrong, counted, firstRight, heardRight, heardWrong)
    }

    private companion object {
        const val START_S = 15.0
        const val END_S = 10.0
    }
}
