package com.kernoeb.fff3.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        /** The rounded tempo the screen shows. */
        val shown = mutableListOf<Int>()
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
            tracker.process(frame.beatOnset, frame.rms, frame.rawBass, frame.rawMid, frame.rawTreble)
            result.times += tracker.time
            result.states += tracker.state
            result.bpms += tracker.bpm
            result.shown += tracker.displayBpm
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
        // Same clicks, no gap: only long evidence moves the tempo, so this takes over 20 s.
        val run = run(track(Part(120.0, 12.0), Part(140.0, 45.0)))
        assertEquals(120f, run.bpmAt(11.5), 1f)
        assertTrue("locks ${run.tracker.lockCount}", run.tracker.lockCount >= 2)
        assertEquals(RelockReason.NewTempo, run.tracker.lastReason)
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

    private fun music(samples: FloatArray) = Track(samples, emptyList())

    /** Locks within 8 s, then keeps showing [bpm] without ever re-locking. */
    private fun assertSteady(run: Run, bpm: Int) {
        val lock = run.firstLock()
        assertTrue("first lock at $lock s", lock <= 8.0)
        val from = run.times.indexOfFirst { it >= lock }
        val states = run.states.drop(from)
        assertTrue("lost the lock at ${run.times[from + states.indexOfFirst { it != BeatLockState.Locked }]} s", states.all { it == BeatLockState.Locked })
        val shown = run.shown.drop(from)
        val wrong = shown.indexOfFirst { it != bpm }
        assertTrue("showed ${shown.getOrNull(wrong)} at ${run.times.getOrNull(from + wrong)} s", wrong < 0)
        assertEquals(1, run.tracker.lockCount)
    }

    /** From [songStart] + 10 s to the end, shows [bpm] and stays locked. */
    private fun assertRelockedWithin10s(run: Run, songStart: Double, bpm: Int) {
        val from = run.times.indexOfFirst { it >= songStart + 10.0 }
        val shown = run.shown.drop(from)
        val wrong = shown.indexOfFirst { it != bpm }
        assertTrue("showed ${shown.getOrNull(wrong)} at ${run.times.getOrNull(from + wrong)} s", wrong < 0)
        assertTrue(run.states.drop(from).all { it == BeatLockState.Locked })
    }

    @Test
    fun threeMinuteSongKeepsItsTempoThroughBreakdownAndFills() {
        // 93 bars at 124 BPM is 3 minutes, with a 16-bar breakdown without drums.
        val run = run(music(SyntheticMusic.song(124.0, bars = 93, SyntheticMusic.POP, breakdown = 40..55)))
        assertSteady(run, 124)
    }

    @Test
    fun halfTimeSectionKeepsTheTempo() {
        val run = run(music(SyntheticMusic.song(124.0, bars = 64, SyntheticMusic.POP, halfTime = 24..39)))
        assertSteady(run, 124)
    }

    @Test
    fun playlistWithAGapRelocksOnTheSecondSong() {
        val first = SyntheticMusic.song(124.0, bars = 31, SyntheticMusic.POP)
        val second = SyntheticMusic.song(98.0, bars = 24, SyntheticMusic.BALLAD, seed = 2)
        val run = run(music(SyntheticMusic.withGap(first, 2.0, second)))
        val secondStart = first.size.toDouble() / sampleRate + 2.0
        assertEquals(124, run.shown[run.times.indexOfFirst { it >= secondStart - 3.0 }])
        assertRelockedWithin10s(run, secondStart, 98)
        assertEquals(RelockReason.Gap, run.tracker.lastReason)
    }

    @Test
    fun playlistWithACrossfadeRelocksOnTheSecondSong() {
        val first = SyntheticMusic.song(124.0, bars = 31, SyntheticMusic.POP)
        val second = SyntheticMusic.song(98.0, bars = 24, SyntheticMusic.BALLAD, seed = 2)
        val run = run(music(SyntheticMusic.crossfade(first, second, 4.0)))
        val secondStart = (first.size.toDouble() - 4.0 * sampleRate) / sampleRate
        assertEquals(124, run.shown[run.times.indexOfFirst { it >= secondStart - 1.0 }])
        assertRelockedWithin10s(run, secondStart, 98)
        assertEquals(RelockReason.SongChange, run.tracker.lastReason)
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

    /**
     * Feeds the tracker an onset signal directly: a one-frame pulse on each beat of every tempo in
     * [pulses] (tempo to strength), plus random noise up to [noise]. Returns (time, bpm) after each frame.
     */
    private fun feed(
        tracker: BeatTracker,
        seconds: Double,
        noise: Float,
        pulses: (Double) -> Map<Double, Float>,
    ): List<Pair<Double, Float>> {
        val random = Random(7)
        val frameRate = 93.75
        val out = mutableListOf<Pair<Double, Float>>()
        for (frame in 0 until (seconds * frameRate).toInt()) {
            val t = frame / frameRate
            var strength = random.nextFloat() * noise
            for ((bpm, level) in pulses(t)) {
                val beatFrames = frameRate * 60.0 / bpm
                if (frame % beatFrames < 1.0) strength += level
            }
            tracker.process(strength, 0.1f)
            out += tracker.time to tracker.bpm
        }
        return out
    }

    @Test
    fun weakButSteadyBeatLocks() {
        // Every reading is below the quick-lock confidence, but they all agree.
        val tracker = BeatTracker(93.75f)
        val bpms = feed(tracker, 20.0, noise = 1.0f) { mapOf(120.0 to 1f) }
        assertTrue("heard confidence ${tracker.heardConfidence}", tracker.heardConfidence < 0.2f)
        assertEquals(BeatLockState.Locked, tracker.state)
        assertEquals(120f, bpms.last().second, 1f)
        assertEquals(1, tracker.lockCount)
    }

    @Test
    fun introWithTwoTemposWaitsForTheBeat() {
        // An intro where a 144 BPM figure is a little stronger than the 120 BPM beat.
        val tracker = BeatTracker(93.75f)
        val bpms = feed(tracker, 25.0, noise = 0.3f) { t -> if (t < 5.0) mapOf(144.0 to 1f, 120.0 to 0.9f) else mapOf(120.0 to 1f) }
        val first = bpms.first { it.second > 0f }
        assertEquals("first lock at ${first.first} s", 120f, first.second, 1f)
        assertEquals(1, tracker.lockCount)
    }

    @Test
    fun weakLockGivesWayToAClearBeat() {
        // A faint 120 BPM pulse locks from weak readings; then a clear 150 BPM beat starts.
        // Without the give-way rule, the young-lock challenger takes until about 22 s.
        val tracker = BeatTracker(93.75f)
        val bpms = feed(tracker, 30.0, noise = 1.0f) { t -> if (t < 14.0) mapOf(120.0 to 1f) else mapOf(150.0 to 3f) }
        assertEquals(120f, bpms.first { it.first >= 13.0 }.second, 1f)
        val switched = bpms.first { abs(it.second - 150f) < 1f }.first
        assertTrue("150 BPM at $switched s", switched < 20.0)
        assertEquals(RelockReason.NewTempo, tracker.lastReason)
    }

    @Test
    fun weakLockGivesWayToASteadyWeakTempo() {
        // A faint 120 BPM pulse locks from weak readings, then an equally faint 150 BPM beat takes over.
        // 150 is never clear, but it comes back reading after reading and the window hears it better.
        val tracker = BeatTracker(93.75f)
        val bpms = feed(tracker, 40.0, noise = 1.0f) { t -> if (t < 14.0) mapOf(120.0 to 1f) else mapOf(150.0 to 1f) }
        assertEquals(120f, bpms.first { it.first >= 13.0 }.second, 1f)
        val switched = bpms.first { abs(it.second - 150f) < 1f }.first
        assertTrue("150 BPM at $switched s", switched < 29.0)
        assertEquals(RelockReason.NewTempo, tracker.lastReason)
        assertEquals(2, tracker.lockCount)
    }

    @Test
    fun readingsThatSwapBetweenTwoEqualTemposAreTorn() {
        // As in an intro where 131 and 163 BPM are as strong as each other and the readings swap.
        val tracker = BeatTracker(93.75f)
        val readings = FloatArray(60)
        fun torn(rival: Float, vararg heard: Float): Boolean {
            heard.copyInto(readings)
            return tracker.torn(131f, 0.42f, rival, readings, heard.size)
        }
        assertTrue(torn(0.43f, 163f, 163f, 131f, 131f, 131f))
        // A clearly weaker rival is no coin toss.
        assertFalse(torn(0.3f, 163f, 163f, 131f, 131f, 131f))
        // Half, double and 3:2 readings are the same music, and a silent reading is no tempo.
        assertFalse(torn(0.43f, 65.5f, 262f, 196.5f, 0f, 131f))
        // The swap counts for 8 readings, then it is forgotten.
        assertTrue(torn(0.43f, 163f, 131f, 131f, 131f, 131f, 131f, 131f, 131f))
        assertFalse(torn(0.43f, 163f, 131f, 131f, 131f, 131f, 131f, 131f, 131f, 131f))
    }

    @Test
    fun introTempoGivesWayToTheSongsSteadyBeat() {
        // A short, clear 160 BPM intro, then a faint 120 BPM beat for the rest of the song. 160 is 4:3 of
        // 120, so once the lock is old the challenger ignores it, and 120 is never clear enough.
        val tracker = BeatTracker(93.75f)
        val bpms = feed(tracker, 90.0, noise = 1.0f) { t -> if (t < 6.0) mapOf(160.0 to 2f) else mapOf(120.0 to 1.3f) }
        assertEquals(160f, bpms.first { it.first >= 6.0 }.second, 1f)
        assertEquals(120f, bpms.last().second, 1f)
        val switched = bpms.first { abs(it.second - 120f) < 1f }.first
        assertTrue("120 BPM at $switched s", switched < 60.0)
        assertEquals(RelockReason.NewTempo, tracker.lastReason)
    }

    @Test
    fun introTempoGivesWayToASteadyBeatHeardInPatches() {
        // As above, but the song's 120 BPM beat comes and goes, 12 s on and 12 s of noise, as in a quiet
        // song. Half the readings hear no tempo, but those that hear one almost all hear 120.
        val tracker = BeatTracker(93.75f)
        val bpms = feed(tracker, 120.0, noise = 1.0f) { t ->
            when {
                t < 6.0 -> mapOf(160.0 to 2f)
                (t - 6.0) % 24.0 < 12.0 -> mapOf(120.0 to 1.3f)
                else -> emptyMap()
            }
        }
        assertEquals(160f, bpms.first { it.first >= 6.0 }.second, 1f)
        val switched = bpms.firstOrNull { abs(it.second - 120f) < 1f }?.first
        assertTrue("120 BPM at $switched s", switched != null && switched < 60.0)
        assertEquals(120f, bpms.last().second, 1f)
        assertEquals(RelockReason.NewTempo, tracker.lastReason)
    }

    @Test
    fun fadedIntroTempoSoonGivesWayToARelatedBeat() {
        // A clear 112.5 BPM intro locks, then the song plays 168.75 BPM, 3:2 of it, with loose timing.
        // Once the intro tempo fades, the song's beat wins in about 20 s. Without that rule, the
        // lock is heard too well for the young-lock challenger and stays wrong past 60 s.
        val tracker = BeatTracker(93.75f)
        val random = Random(3)
        val songBeats = mutableSetOf<Int>()
        var at = 10 * 93.75
        while (at < 60 * 93.75) {
            songBeats += Math.round(at).toInt()
            at += 93.75 * 60 / 168.75 + random.nextDouble(-1.0, 1.0) * 0.85
        }
        var introBpm = 0f
        var switched = Double.NaN
        for (frame in 0 until 60 * 94) {
            var strength = random.nextFloat() * 0.5f
            if (frame < 10 * 93.75 && frame % 50 == 0) strength += 2f
            if (frame in songBeats) strength += 1f
            tracker.process(strength, 0.1f)
            if (frame == 10 * 94) introBpm = tracker.bpm
            val song = abs(tracker.bpm - 168.75f) < 5f || abs(tracker.bpm - 84.4f) < 2.5f
            if (switched.isNaN() && song) switched = tracker.time
        }
        assertEquals(112.5f, introBpm, 1f)
        assertTrue("song tempo at $switched s", switched < 35.0)
        assertEquals(RelockReason.NewTempo, tracker.lastReason)
        assertEquals(2, tracker.lockCount)
    }

    @Test
    fun tripletHatsGiveWayToTheSlowerKick() {
        // Hi-hats at 125 BPM lead the onsets, while the kick plays 83.3 BPM, 2:3 of it, as in slow trap.
        // The lock takes the hats first; the bass then says the beat is the kick, and the lock follows.
        val tracker = BeatTracker(93.75f)
        val random = Random(5)
        var bass = 0f
        var switched = Double.NaN
        for (frame in 0 until 60 * 94) {
            var strength = random.nextFloat() * 0.3f
            if (frame % 45 == 0) strength += 1.5f
            val kick = frame % 67.5 < 1.0
            if (kick) strength += 0.8f
            bass = if (kick) 0.05f else bass * 0.9f
            tracker.process(strength, 0.1f, bass + random.nextFloat() * 1e-4f)
            if (frame == 10 * 94) assertEquals(125f, tracker.bpm, 1f)
            if (switched.isNaN() && abs(tracker.bpm - 83.3f) < 1.5f) switched = tracker.time
        }
        assertTrue("kick tempo at $switched s", switched < 30.0)
        assertEquals(83.3f, tracker.bpm, 1.5f)
        assertEquals(RelockReason.NewTempo, tracker.lastReason)
    }

    @Test
    fun tappedTempoOverTripletHatsKeepsFineTuning() {
        // The same music, tapped at 126.5 on the hats: the readings must still pull it towards 125.
        val tracker = BeatTracker(93.75f)
        val random = Random(5)
        var bass = 0f
        for (frame in 0 until 60 * 94) {
            var strength = random.nextFloat() * 0.3f
            if (frame % 45 == 0) strength += 1.5f
            val kick = frame % 67.5 < 1.0
            if (kick) strength += 0.8f
            bass = if (kick) 0.05f else bass * 0.9f
            tracker.process(strength, 0.1f, bass + random.nextFloat() * 1e-4f)
            if (frame == 5 * 94) for (tap in 0 until 6) tracker.tap(tracker.time + tap * 60.0 / 126.5)
        }
        assertEquals(125f, tracker.bpm, 0.75f)
        // Only the lock before the taps: the kick never replaces a tempo the user set.
        assertEquals(1, tracker.lockCount)
    }

    @Test
    fun bassAloneDoesNotSilenceTheReadings() {
        // The kick is in the bass but not in the onsets: the window never hears the slower tempo.
        val tracker = BeatTracker(93.75f)
        val random = Random(5)
        var bass = 0f
        for (frame in 0 until 60 * 94) {
            var strength = random.nextFloat() * 0.3f
            if (frame % 45 == 0) strength += 1.5f
            val kick = frame % 67.5 < 1.0
            bass = if (kick) 0.05f else bass * 0.9f
            tracker.process(strength, 0.1f, bass + random.nextFloat() * 1e-4f)
        }
        assertEquals(125f, tracker.bpm, 1f)
        assertEquals(125f, tracker.heardBpm, 1f)
    }

    @Test
    fun heardLockKeepsItsTempoWhenAnotherTempoTakesOver() {
        // 40 s of a steady 120 BPM beat proves the lock. A long section that only hears 160 BPM
        // must not move it: within a song, 4:3 is the same music counted another way.
        val tracker = BeatTracker(93.75f)
        val bpms = feed(tracker, 120.0, noise = 1.0f) { t -> if (t < 40.0) mapOf(120.0 to 1.5f) else mapOf(160.0 to 1f) }
        assertEquals(120f, bpms.first { it.first >= 39.0 }.second, 1f)
        assertEquals(120f, bpms.last().second, 1f)
        assertEquals(1, tracker.lockCount)
    }

    @Test
    fun sidesOfAFastPeakAreNotARivalTempo() {
        // 175 BPM is 32 frames a beat: 2 lags off is only 6 %, still the same peak.
        val tracker = BeatTracker(93.75f)
        assertTrue(tracker.relatedLag(34, 32))
        assertTrue(tracker.relatedLag(30, 32))
        assertTrue(tracker.relatedLag(43, 32))
        assertTrue(tracker.relatedLag(66, 64))
        assertFalse(tracker.relatedLag(36, 32))
        assertFalse(tracker.relatedLag(80, 32))
        assertFalse(tracker.relatedLag(56, 64))
    }

    @Test
    fun syncopatedHitsDoNotPullTheBeatLate() {
        // 112.5 BPM is exactly 50 frames a beat. Every other beat, a louder hit comes 7 frames
        // (75 ms) late. The per-beat loop alone sits about 55 ms late; the window's grid pulls it back.
        val tracker = BeatTracker(93.75f)
        val random = Random(7)
        val errors = mutableListOf<Double>()
        for (frame in 0 until 20 * 94) {
            var strength = random.nextFloat() * 0.1f
            if (frame % 50 == 0) strength += 1f
            if (frame % 100 == 7) strength += 1.5f
            tracker.process(strength, 0.1f)
            val t = tracker.time
            if (frame % 50 == 0 && t > 12.0) {
                val beats = tracker.beatsAt(t)
                errors += abs(beats - Math.round(beats)) * tracker.period
            }
        }
        assertEquals(112.5f, tracker.bpm, 1f)
        val median = errors.sorted()[errors.size / 2]
        assertTrue("median phase error ${median * 1000} ms", median < 0.040)
    }

    @Test
    fun steadyToneDoesNotLock() {
        val samples = FloatArray(sampleRate * 10) { (0.3 * sin(2 * PI * 440 * it / sampleRate)).toFloat() }
        val run = run(Track(samples, emptyList()))
        assertTrue(run.states.all { it == BeatLockState.Acquiring })
    }
}
