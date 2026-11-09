package com.kernoeb.fff3.ui.calibrate

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ble.FrameWrites
import com.kernoeb.fff3.ble.LedController
import com.kernoeb.fff3.calibrate.LightCamera
import com.kernoeb.fff3.calibrate.LightDelayMath
import com.kernoeb.fff3.calibrate.LightDelayMath.Frame
import com.kernoeb.fff3.calibrate.LightDelayMath.Level
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.music.MusicStatus
import com.kernoeb.fff3.music.MusicSync
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface CalibrationPhase {
    data object Idle : CalibrationPhase
    /** [flash] is 0 while the strip and camera get ready. */
    data class Running(val flash: Int, val total: Int) : CalibrationPhase
    data class Done(val address: String, val result: LightDelayMath.Result, val saved: Boolean = false) : CalibrationPhase
    data class Failed(val reason: CalibrationFailure) : CalibrationPhase
}

enum class CalibrationFailure { NotReached, NoChange, TooFew }

@Immutable
data class CalibrateUiState(
    val loaded: Boolean = false,
    val strips: List<KnownDevice> = emptyList(),
    val address: String? = null,
    /** The saved light delay of [address], or null if never measured. */
    val savedMs: Int? = null,
    val phase: CalibrationPhase = CalibrationPhase.Idle,
    /** Music sync also drives the strips, so it must stop first. */
    val musicRunning: Boolean = false,
) {
    val running: Boolean get() = phase is CalibrationPhase.Running
}

/**
 * Flashes one strip between red and blue while the camera watches it, and times each change from
 * the moment the colour went to the Bluetooth stack. The strip gets its previous state back after
 * the run, also when it is cancelled or the screen closes.
 */
class CalibrateLightViewModel(
    initialAddress: String?,
    private val repository: LedRepository,
    private val controller: LedController,
    private val deviceStates: DeviceStateStore,
    private val frameWrites: FrameWrites,
    music: MusicSync,
    val camera: LightCamera,
) : ViewModel() {

    private val selected = MutableStateFlow(initialAddress)
    private val phase = MutableStateFlow<CalibrationPhase>(CalibrationPhase.Idle)
    private val frames = ArrayList<Frame>()
    @Volatile private var recording = false
    private var job: Job? = null

    init {
        camera.onSample = { frame -> if (recording) synchronized(frames) { frames += frame } }
    }

    val state: StateFlow<CalibrateUiState> = combine(
        repository.snapshot.filterNotNull(),
        selected,
        phase,
        music.status,
    ) { data, chosen, phase, status ->
        val address = chosen?.takeIf { wanted -> data.devices.any { it.address == wanted } } ?: data.devices.firstOrNull()?.address
        CalibrateUiState(
            loaded = true,
            strips = data.devices,
            address = address,
            savedMs = address?.let { data.settings.lightDelaysMs[it] },
            phase = phase,
            musicRunning = status is MusicStatus.Running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalibrateUiState())

    fun select(address: String) {
        if (job?.isActive == true) return
        selected.value = address
        phase.value = CalibrationPhase.Idle
    }

    fun openCamera() = camera.open()

    /** Leaving the screen stops a run: without the camera it cannot finish. */
    fun closeCamera() {
        job?.cancel()
        camera.close()
    }

    fun start() {
        val current = state.value
        val device = current.strips.firstOrNull { it.address == current.address } ?: return
        if (job?.isActive == true || current.musicRunning) return
        job = viewModelScope.launch { run(device) }
    }

    fun cancel() {
        job?.cancel()
    }

    fun save() {
        val done = phase.value as? CalibrationPhase.Done ?: return
        val millis = done.result.delayMs.roundToInt()
        viewModelScope.launch {
            repository.updateSettings { it.copy(lightDelaysMs = it.lightDelaysMs + (done.address to millis)) }
            phase.value = done.copy(saved = true)
        }
    }

    override fun onCleared() {
        job?.cancel()
        frameWrites.listener = null
        camera.onSample = null
        camera.close()
    }

    private suspend fun run(device: KnownDevice) {
        val target = ControlTarget.Device(device.address)
        val previous = deviceStates.states.value[device.address]
        try {
            phase.value = CalibrationPhase.Running(0, FLASHES)
            camera.setMode(LightCamera.Mode.Settle)
            val ready = listOf(Command.Power(true), Command.Brightness(100), Command.Color(COLOURS[0]))
            // Flashes are not recorded, so the saved state stays the user's; restore records it again.
            if (!controller.sendAndAwait(target, ready, record = false)) return fail(CalibrationFailure.NotReached)
            delay(SETTLE_MS)
            camera.setMode(LightCamera.Mode.Measure)
            delay(LOCK_MS)
            recording = true

            delay(LEVEL_MS)
            val first = level() ?: return fail(CalibrationFailure.NoChange)
            if (sendTimed(device, COLOURS[1]) == null) return fail(CalibrationFailure.NotReached)
            delay(LEVEL_MS)
            val second = level() ?: return fail(CalibrationFailure.NoChange)
            if (LightDelayMath.separation(first, second) < MIN_SEPARATION) return fail(CalibrationFailure.NoChange)
            val levels = listOf(first, second)

            val flashes = mutableListOf<Flash>()
            var showing = 1
            for (flash in 1..FLASHES) {
                phase.value = CalibrationPhase.Running(flash, FLASHES)
                val next = 1 - showing
                sendTimed(device, COLOURS[next])?.let { flashes += Flash(it, levels[showing], levels[next]) }
                showing = next
                // Random gaps spread the changes evenly between camera frames, so frame timing averages out.
                delay(Random.nextLong(MIN_GAP_MS, MAX_GAP_MS))
            }
            delay(TAIL_MS)

            val seen = synchronized(frames) { frames.toList() }
            val delays = flashes.mapIndexedNotNull { index, flash ->
                val until = flashes.getOrNull(index + 1)?.sentAt ?: Long.MAX_VALUE
                LightDelayMath.flashDelayMs(seen, flash.sentAt, until, flash.from, flash.to)
            }
            val result = LightDelayMath.summarize(delays)
            phase.value = if (result == null || result.kept < MIN_KEPT) {
                CalibrationPhase.Failed(CalibrationFailure.TooFew)
            } else {
                CalibrationPhase.Done(device.address, result)
            }
        } finally {
            recording = false
            synchronized(frames) { frames.clear() }
            frameWrites.listener = null
            camera.setMode(LightCamera.Mode.Aim)
            restore(target, previous)
            if (phase.value is CalibrationPhase.Running) phase.value = CalibrationPhase.Idle
        }
    }

    private fun fail(reason: CalibrationFailure) {
        phase.value = CalibrationPhase.Failed(reason)
    }

    /** The mean colour of the last frames, or null when the camera gave too few. */
    private fun level(): Level? {
        val since = SystemClock.elapsedRealtimeNanos() - LEVEL_WINDOW_NS
        val recent = synchronized(frames) { frames.filter { it.time >= since } }
        if (recent.size < MIN_LEVEL_FRAMES) return null
        return Level(recent.sumOf { it.u } / recent.size, recent.sumOf { it.v } / recent.size)
    }

    /** Sends [colour] and returns when its frame went to the Bluetooth stack, or null if it never did. */
    private suspend fun sendTimed(device: KnownDevice, colour: Rgb): Long? {
        val command = Command.Color(colour)
        val expected = device.family.encode(command).lastOrNull() ?: return null
        val written = CompletableDeferred<Long>()
        frameWrites.listener = FrameWrites.Listener { address, frame, at ->
            if (address == device.address && frame.contentEquals(expected)) written.complete(at)
        }
        controller.send(ControlTarget.Device(device.address), command, record = false)
        return withTimeoutOrNull(WRITE_WAIT_MS) { written.await() }
    }

    private fun restore(target: ControlTarget, state: LedState?) {
        controller.send(target, restoreCommands(state))
    }

    private class Flash(val sentAt: Long, val from: Level, val to: Level)

    companion object {
        const val FLASHES = 24
        /** Far apart in every camera's colour channels. */
        val COLOURS = listOf(Rgb(0xFF0000), Rgb(0x0000FF))
        private const val SETTLE_MS = 800L
        private const val LOCK_MS = 300L
        private const val LEVEL_MS = 600L
        private const val LEVEL_WINDOW_NS = 300_000_000L
        private const val MIN_LEVEL_FRAMES = 3
        private const val MIN_SEPARATION = 12.0
        private const val MIN_GAP_MS = 450L
        private const val MAX_GAP_MS = 750L
        private const val TAIL_MS = 400L
        private const val WRITE_WAIT_MS = 2_000L
        private const val MIN_KEPT = 12
    }
}

/**
 * Puts a strip back after the flashes, which left it red or blue at full brightness. What fff3 does
 * not know comes back as warm white at full brightness, so the strip is never left on a flash colour.
 */
internal fun restoreCommands(state: LedState?): List<Command> = buildList {
    add(Command.Brightness(state?.brightness ?: 100))
    when {
        state?.effectId != null -> add(Command.Effect(state.effectId, state.speed))
        state?.sceneId != null -> add(Command.Scene(state.sceneId))
        else -> add(Command.Color(state?.color ?: Rgb.fromKelvin(WARM_WHITE_K)))
    }
    when (state?.power) {
        false -> add(Command.Power(false))
        // The calibration turned the strip on, so an unknown power is now known.
        null -> add(Command.Power(true))
        true -> Unit
    }
}

private const val WARM_WHITE_K = 2700
