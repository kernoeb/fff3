package com.kernoeb.fff3.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

class BeatTrackerTest {

    private val sampleRate = 48_000

    /** One part of a synthetic track: clicks at [bpm] for [seconds], or silence when [bpm] is 0. */
    private class Part(val bpm: Double, val seconds: Double, val skipEvery: Int = 0)

    /** The track and the true time of every click in it. */
    private class Track(val samples: FloatArray, val clicks: List<Double>)

    private fun track(vararg parts: Part): Track {
        val random = Random(42)
        val total = parts.sumOf { it.seconds }
        val samples = FloatArray((total * sampleRate).toInt())
        val clicks = mutableListOf<Double>()
        var start = 0.0
        for (part in parts) {
            val from = (start * sampleRate).toInt()
            val to = ((start + part.seconds) * sampleRate).toInt().coerceAtMost(samples.size)
            if (part.bpm > 0) {
                for (i in from until to) samples[i] = (random.nextFloat() * 2 - 1) * 0.002f
                var t = start + 0.25
                var index = 0
                while (t < start + part.seconds) {
                    val skipped = part.skipEvery > 0 && index % part.skipEvery == part.skipEvery - 1
                    if (!skipped) {
                        clicks += t
                        val at = (t * sampleRate).toInt()
                        for (i in 0 until sampleRate / 10) {
                            if (at + i >= to) break
                            val time = i.toDouble() / sampleRate
                            samples[at + i] += (0.6 * exp(-time * 400) * (random.nextFloat() * 2 - 1)).toFloat()
                        }
                    }
                    index++
                    t += 60.0 / part.bpm
                }
            }
            start += part.seconds
        }
        return Track(samples, clicks)
    }

    /** What the tracker did, sampled after each frame. */
    private class Run(val tracker: BeatTracker) {
        val times = mutableListOf<Double>()
        val states = mutableListOf<BeatLockState>()
        val bpms = mutableListOf<Float>()
        /** Times of the beats the clock gave, the way the lights see them. */
        val beats = mutableListOf<Double>()

        fun stateAt(time: Double) = states[times.indexOfFirst { it >= time }]
        fun bpmAt(time: Double) = bpms[times.indexOfFirst { it >= time }]
        fun firstLock() = times[states.indexOfFirst { it == BeatLockState.Locked }]
    }

    private fun run(track: Track, before: (BeatTracker) -> Unit = {}): Run {
        val analyzer = MusicAnalyzer(sampleRate)
        val tracker = BeatTracker(analyzer.frameRate, analyzer.fftSize / 2.0 / sampleRate)
        before(tracker)
        val result = Run(tracker)
        val hop = FloatArray(analyzer.hopSize)
        var offset = 0
        var lastBeat = Long.MIN_VALUE
        var epoch = -1
        while (offset + hop.size <= track.samples.size) {
            track.samples.copyInto(hop, 0, offset, offset + hop.size)
            offset += hop.size
            val frame = analyzer.process(hop)
            tracker.process(frame.onset, frame.rms)
            result.times += tracker.time
            result.states += tracker.state
            result.bpms += tracker.bpm
            if (tracker.state == BeatLockState.Locked) {
                val beats = tracker.beatsAt(tracker.time)
                val whole = floor(beats).toLong()
                if (tracker.epoch != epoch) {
                    epoch = tracker.epoch
                    lastBeat = whole
                } else if (whole > lastBeat) {
                    lastBeat = whole
                    result.beats += tracker.time - (beats - whole) * tracker.period
                }
            }
        }
        return result
    }

    private fun phaseErrors(run: Run, clicks: List<Double>, from: Double, to: Double): List<Double> =
        run.beats.filter { it in from..to }.map { beat -> clicks.minOf { abs(it - beat) } }

    private fun assertLocksOn(bpm: Double) {
        val track = track(Part(bpm, 16.0))
        val run = run(track)
        assertTrue("first lock at ${run.firstLock()} s", run.firstLock() <= 8.0)
        assertEquals(BeatLockState.Locked, run.stateAt(8.0))
        assertEquals(bpm.toFloat(), run.bpmAt(8.0), 1f)
        assertEquals(bpm.toFloat(), run.bpms.last(), 1f)
        val errors = phaseErrors(run, track.clicks, run.firstLock() + 0.5, 16.0)
        assertTrue("beats ${errors.size}", errors.size >= 14)
        assertTrue("worst phase error ${errors.max() * 1000} ms", errors.max() < 0.030)
        assertEquals(1, run.tracker.lockCount)
    }

    @Test
    fun locksOn120Bpm() = assertLocksOn(120.0)

    @Test
    fun locksOn128Bpm() = assertLocksOn(128.0)

    @Test
    fun staysLockedWhenEveryThirdBeatIsMissing() {
        val track = track(Part(120.0, 20.0, skipEvery = 3))
        val run = run(track)
        assertTrue("first lock at ${run.firstLock()} s", run.firstLock() <= 8.0)
        assertEquals(1, run.tracker.lockCount)
        assertEquals(BeatLockState.Locked, run.states.last())
        assertEquals(120f, run.bpms.last(), 1f)
        // The clock fills the missing beats: one beat every 0.5 s, on the grid of the clicks.
        val beats = run.beats.filter { it in 8.0..20.0 }
        assertTrue("beats ${beats.size}", beats.size >= 23)
        beats.zipWithNext { a, b -> assertEquals(0.5, b - a, 0.02) }
        for (beat in beats) {
            val grid = 0.25 + Math.round((beat - 0.25) / 0.5) * 0.5
            assertEquals(grid, beat, 0.030)
        }
    }

    @Test
    fun tempoChangeRelocksOnTheNewTempo() {
        val run = run(track(Part(120.0, 12.0), Part(140.0, 18.0)))
        assertEquals(120f, run.bpmAt(11.5), 1f)
        assertTrue("locks ${run.tracker.lockCount}", run.tracker.lockCount >= 2)
        assertEquals(BeatLockState.Locked, run.states.last())
        assertEquals(140f, run.bpms.last(), 1f)
    }

    @Test
    fun silenceBetweenTracksStartsOver() {
        val run = run(track(Part(120.0, 10.0), Part(0.0, 2.5), Part(128.0, 12.0)))
        assertEquals(BeatLockState.Locked, run.stateAt(9.5))
        assertEquals(BeatLockState.Acquiring, run.stateAt(12.3))
        assertEquals(2, run.tracker.lockCount)
        assertEquals(BeatLockState.Locked, run.states.last())
        assertEquals(128f, run.bpms.last(), 1f)
    }

    @Test
    fun fourTapsSetTheTempo() {
        val tracker = BeatTracker(93.75f)
        listOf(10.0, 10.6, 11.2, 11.8).forEach(tracker::tap)
        assertEquals(BeatLockState.Locked, tracker.state)
        assertEquals(100f, tracker.bpm, 0.1f)
        // The last tap is on a beat.
        assertEquals(3.0, tracker.beatsAt(11.8), 1e-6)
    }

    @Test
    fun threeTapsAreNotEnough() {
        val tracker = BeatTracker(93.75f)
        listOf(10.0, 10.6, 11.2).forEach(tracker::tap)
        assertEquals(BeatLockState.Acquiring, tracker.state)
    }

    @Test
    fun doubleAndHalfFixTheTempo() {
        val tracker = BeatTracker(93.75f)
        listOf(0.0, 1.0, 2.0, 3.0).forEach(tracker::tap)
        tracker.scaleTempo(2.0)
        assertEquals(120f, tracker.bpm, 0.01f)
        tracker.scaleTempo(0.5)
        assertEquals(60f, tracker.bpm, 0.01f)
        // 30 BPM is out of range, so the tempo stays.
        tracker.scaleTempo(0.5)
        assertEquals(60f, tracker.bpm, 0.01f)
    }

    @Test
    fun silenceDoesNotLock() {
        val run = run(track(Part(0.0, 10.0)))
        assertTrue(run.states.all { it == BeatLockState.Acquiring })
    }

    @Test
    fun steadyToneDoesNotLock() {
        val samples = FloatArray(sampleRate * 10) { (0.3 * sin(2 * PI * 440 * it / sampleRate)).toFloat() }
        val run = run(Track(samples, emptyList()))
        assertTrue(run.states.all { it == BeatLockState.Acquiring })
    }
}
