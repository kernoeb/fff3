package com.kernoeb.fff3.ui.music

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.AudioOutput
import com.kernoeb.fff3.data.MusicSource
import com.kernoeb.fff3.music.LatencyStatus
import com.kernoeb.fff3.music.LiveMusic
import com.kernoeb.fff3.music.MusicStatus
import com.kernoeb.fff3.music.MusicStyle
import com.kernoeb.fff3.music.StopReason
import com.kernoeb.fff3.system.MusicService
import com.kernoeb.fff3.ui.appViewModel
import com.kernoeb.fff3.ui.theme.toColor
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.roundToInt

/** Phone music mode: pick where and how, then start. The lights keep going with the screen off. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MusicScreen(initialTarget: ControlTarget?, onBack: () -> Unit, onCalibrateLight: (address: String?) -> Unit) {
    val viewModel = appViewModel(key = "music") {
        MusicViewModel(initialTarget, it.repository, it.deviceStates, it.music)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current

    var showRationale by rememberSaveable { mutableStateOf(false) }
    var micDenied by rememberSaveable { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MusicService.start(context, state.settings.target, MusicSource.PhoneAudio, result.resultCode, data)
        } else {
            viewModel.onCaptureDenied()
        }
    }

    fun startMusic(source: MusicSource = state.source) {
        if (source == MusicSource.Microphone) {
            MusicService.start(context, state.settings.target, MusicSource.Microphone)
            return
        }
        try {
            consent.launch(MusicService.captureConsentIntent(context))
        } catch (_: ActivityNotFoundException) {
            viewModel.onCaptureDenied()
        }
    }

    fun useMicrophone() {
        viewModel.selectSource(MusicSource.Microphone)
        MusicService.start(context, state.settings.target, MusicSource.Microphone)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The notification is optional: music sync works without it, only the stop shortcut is hidden.
        if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            micDenied = false
            startMusic()
        } else {
            micDenied = true
        }
    }

    fun onStartClick() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filterNot(context::hasPermission)
        when {
            missing.isEmpty() -> startMusic()
            Manifest.permission.RECORD_AUDIO in missing -> showRationale = true
            else -> permissions.launch(missing.toTypedArray())
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            icon = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null) },
            title = { Text(stringResource(R.string.music_permission_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.source == MusicSource.PhoneAudio) R.string.music_permission_body_phone
                        else R.string.music_permission_body,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissions.launch(
                        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                            .filterNot(context::hasPermission).toTypedArray(),
                    )
                }) { Text(stringResource(R.string.music_permission_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val idleColor = state.stripColor?.toColor() ?: MaterialTheme.colorScheme.primary
            Visualiser(
                live = viewModel.live,
                running = state.running,
                idleColor = idleColor,
                background = MaterialTheme.colorScheme.surfaceContainer,
            )

            Text(
                text = statusText(state),
                style = MaterialTheme.typography.titleMedium,
                color = if (state.status.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )

            if (state.noAudio) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
                        Text(
                            stringResource(R.string.music_no_audio),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = ::useMicrophone, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.music_use_microphone))
                        }
                    }
                }
            }

            if (micDenied && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
                Text(stringResource(R.string.music_permission_denied), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { context.openAppSettings() }) {
                    Text(stringResource(R.string.music_permission_settings))
                }
            }

            Button(
                onClick = { if (state.running) MusicService.stop(context) else onStartClick() },
                enabled = state.running || state.hasStrips,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(if (state.running) 20.dp else 32.dp),
                colors = if (state.running) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(
                    painterResource(if (state.running) R.drawable.ic_stop else R.drawable.ic_play_arrow),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(if (state.running) R.string.music_stop else R.string.music_start),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            SectionTitle(R.string.music_source)
            val sources = listOf(MusicSource.PhoneAudio, MusicSource.Microphone)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sources.forEachIndexed { index, source ->
                    SegmentedButton(
                        selected = source == state.source,
                        onClick = {
                            if (source == state.source) return@SegmentedButton
                            viewModel.selectSource(source)
                            val running = state.status as? MusicStatus.Running
                            if (running != null && running.source != source) startMusic(source)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, sources.size),
                        icon = {},
                        label = { Text(stringResource(source.labelRes()), maxLines = 1) },
                    )
                }
            }
            Text(
                stringResource(state.source.bodyRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle(R.string.music_target)
            if (state.loaded && !state.hasStrips) {
                Text(stringResource(R.string.music_no_strips), style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.options.forEach { option ->
                    FilterChip(
                        selected = option.target == state.settings.target,
                        onClick = {
                            if (option.target == state.settings.target) return@FilterChip
                            viewModel.selectTarget(option.target)
                            val running = state.status as? MusicStatus.Running
                            if (running != null) MusicService.start(context, option.target, running.source)
                        },
                        label = { Text(option.label ?: stringResource(R.string.music_all_strips)) },
                    )
                }
            }

            SectionTitle(R.string.music_style)
            val styles = MusicStyle.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                styles.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = style == state.settings.style,
                        onClick = { viewModel.selectStyle(style) },
                        shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                        icon = {},
                        label = { Text(stringResource(style.labelRes()), maxLines = 1) },
                    )
                }
            }
            Text(
                stringResource(state.settings.style.bodyRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.settings.style == MusicStyle.BeatLock) {
                BeatLockPanel(
                    state = state,
                    live = viewModel.live,
                    onTap = viewModel::tap,
                    onScaleTempo = viewModel::scaleTempo,
                    onRecalibrate = viewModel::recalibrate,
                    onLightDelay = viewModel::setLightDelay,
                    onLightDelayDone = viewModel::saveLightDelay,
                    onMeasureLight = { onCalibrateLight(state.calibrateAddress) },
                )
            }

            SectionTitle(R.string.music_sensitivity)
            Slider(value = state.settings.sensitivity, onValueChange = viewModel::setSensitivity)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.music_sensitivity_low), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.music_sensitivity_high), style = MaterialTheme.typography.labelMedium)
            }

            Text(
                stringResource(R.string.music_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

/**
 * Spectrum bars over a glow that pulses on each beat. It reads [live] in the draw phase only,
 * so the animation redraws every frame without recomposing anything.
 */
@Composable
private fun Visualiser(live: LiveMusic, running: Boolean, idleColor: Color, background: Color) {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (running) while (isActive) withFrameNanos { tick.longValue = it }
    }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(32.dp))
            .drawBehind {
                tick.longValue
                drawRect(background)
                val color = if (running) Color(0xFF000000.toInt() or live.hueColor) else idleColor
                val flash = if (running) live.flash else 0f
                val level = if (running) live.level else 0f
                val radius = size.minDimension * (0.42f + 0.18f * level + 0.12f * flash)
                drawCircle(color.copy(alpha = 0.08f + 0.18f * flash), radius)
                drawCircle(color.copy(alpha = 0.10f + 0.22f * flash), radius * 0.62f)

                val bands = live.bands
                val pad = 24.dp.toPx()
                val gap = 5.dp.toPx()
                val barWidth = (size.width - 2 * pad - gap * (bands.size - 1)) / bands.size
                val maxHeight = size.height - 2 * pad
                for (i in bands.indices) {
                    val height = (if (running) bands[i] else 0f) * maxHeight
                    val barHeight = height.coerceAtLeast(barWidth)
                    drawRoundRect(
                        color = color.copy(alpha = if (running) 0.9f else 0.35f),
                        topLeft = Offset(pad + i * (barWidth + gap), (size.height - barHeight) / 2),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                }
            },
    )
}

/** Tempo, tap tempo, delays and the light delay fine-tune, shown while Beat lock is the style. */
@Composable
private fun BeatLockPanel(
    state: MusicUiState,
    live: LiveMusic,
    onTap: (Long) -> Unit,
    onScaleTempo: (Double) -> Unit,
    onRecalibrate: () -> Unit,
    onLightDelay: (Int) -> Unit,
    onLightDelayDone: () -> Unit,
    onMeasureLight: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatIndicator(live, state.running, on = colors.primary, off = colors.outlineVariant)
                Spacer(Modifier.size(12.dp))
                Text(beatText(state), style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TapButton(enabled = state.running, onTap = onTap, modifier = Modifier.weight(1f))
                val canScale = state.running && state.beat.locked
                val double = stringResource(R.string.music_beat_double_description)
                val half = stringResource(R.string.music_beat_half_description)
                OutlinedButton(
                    onClick = { onScaleTempo(2.0) },
                    enabled = canScale,
                    modifier = Modifier.height(56.dp).semantics { contentDescription = double },
                ) { Text(stringResource(R.string.music_beat_double), style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(
                    onClick = { onScaleTempo(0.5) },
                    enabled = canScale,
                    modifier = Modifier.height(56.dp).semantics { contentDescription = half },
                ) { Text(stringResource(R.string.music_beat_half), style = MaterialTheme.typography.titleMedium) }
            }
            Text(
                stringResource(R.string.music_beat_tap_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            state.latency?.let { latency ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        latencyText(latency),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (latency.output != null) {
                        TextButton(onClick = onRecalibrate, enabled = !latency.calibrating) {
                            Text(stringResource(R.string.music_recalibrate))
                        }
                    }
                }
            }
            val delay = state.lightDelayMs
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.music_light_delay), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.music_light_delay_value, if (delay > 0) "+$delay" else "$delay"),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            val delayState = remember {
                SliderState(
                    value = delay.toFloat(),
                    trackRange = LIGHT_DELAY_MIN.toFloat()..LIGHT_DELAY_MAX.toFloat(),
                )
            }
            LaunchedEffect(delay) { delayState.value = delay.toFloat() }
            Slider(
                state = delayState,
                onValueChange = {
                    delayState.value = it
                    onLightDelay((it / LIGHT_DELAY_STEP).roundToInt() * LIGHT_DELAY_STEP)
                },
                onValueChangeFinished = onLightDelayDone,
            )
            Text(
                stringResource(R.string.music_light_delay_body),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.measuredLightMs?.let { stringResource(R.string.music_light_measured, it) }
                        ?: stringResource(R.string.music_light_not_measured),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onMeasureLight) {
                    Text(stringResource(R.string.music_light_measure))
                }
            }
        }
    }
}

/**
 * Reacts on touch down, not on release, so the tap lands on the beat. The time comes from the
 * touch event, so a busy frame does not shift it.
 */
@Composable
private fun TapButton(enabled: Boolean, onTap: (Long) -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.music_beat_tap_description)
    Surface(
        color = when {
            !enabled -> colors.surfaceContainerHighest
            pressed -> colors.primary
            else -> colors.primaryContainer
        },
        contentColor = when {
            !enabled -> colors.onSurfaceVariant
            pressed -> colors.onPrimary
            else -> colors.onPrimaryContainer
        },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .height(56.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick { onTap(System.nanoTime()); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onTap(System.nanoTime() - (SystemClock.uptimeMillis() - down.uptimeMillis) * 1_000_000)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.music_beat_tap), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 4 dots, one per beat of the bar; the current one pulses on the beat clock. Redraws without recomposing. */
@Composable
private fun BeatIndicator(live: LiveMusic, running: Boolean, on: Color, off: Color) {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (running) while (isActive) withFrameNanos { tick.longValue = it }
    }
    Spacer(
        Modifier
            .size(width = 72.dp, height = 24.dp)
            .drawBehind {
                tick.longValue
                val phase = if (running) live.beatPhase else -1f
                val current = live.beatInBar
                val step = size.width / 4
                val radius = size.height * 0.24f
                for (i in 0 until 4) {
                    val center = Offset(step * (i + 0.5f), size.height / 2)
                    if (phase >= 0f && i == current) {
                        val pulse = exp(-phase * 3.5f)
                        val accent = if (i == 0) 1.2f else 1f
                        drawCircle(on, radius * (1f + 0.6f * pulse) * accent, center)
                    } else {
                        drawCircle(if (phase >= 0f) on.copy(alpha = 0.3f) else off, radius, center)
                    }
                }
            },
    )
}

@Composable
private fun beatText(state: MusicUiState): String = when {
    !state.running -> stringResource(R.string.music_beat_off)
    state.beat.locked -> stringResource(R.string.music_beat_locked, state.beat.bpm)
    else -> stringResource(R.string.music_beat_listening)
}

@Composable
private fun latencyText(latency: LatencyStatus): String {
    if (latency.calibrating) return stringResource(R.string.music_delay_measuring)
    val output = latency.output ?: return stringResource(R.string.music_delay_mic, latency.captureMs, latency.lightsMs)
    val name = output.name ?: stringResource(
        when (output.kind) {
            AudioOutput.Speaker -> R.string.music_output_speaker
            AudioOutput.Wired -> R.string.music_output_wired
            AudioOutput.Bluetooth -> R.string.music_output_bluetooth
            AudioOutput.Other -> R.string.music_output_other
        },
    )
    return stringResource(
        if (latency.outputMeasured) R.string.music_delay_output else R.string.music_delay_output_guess,
        name,
        latency.outputMs,
        latency.lightsMs,
    )
}

private const val LIGHT_DELAY_MIN = -100
private const val LIGHT_DELAY_MAX = 200
private const val LIGHT_DELAY_STEP = 5

@Composable
private fun SectionTitle(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun statusText(state: MusicUiState): String = when (val status = state.status) {
    is MusicStatus.Running -> when {
        state.settings.style == MusicStyle.BeatLock -> beatText(state)
        state.bpm > 0 -> stringResource(R.string.music_listening_bpm, state.bpm)
        else -> stringResource(R.string.music_listening)
    }
    is MusicStatus.Stopped -> when (status.reason) {
        StopReason.User -> idleText(state.source)
        StopReason.CaptureDenied -> stringResource(R.string.music_stopped_capture_denied)
        StopReason.CaptureEnded -> stringResource(R.string.music_stopped_capture_ended)
        StopReason.CaptureUnavailable -> stringResource(R.string.music_stopped_capture)
        StopReason.MicUnavailable -> stringResource(R.string.music_stopped_mic)
        StopReason.MicPermissionMissing -> stringResource(R.string.music_stopped_mic_permission)
        StopReason.StripsGone -> stringResource(R.string.music_stopped_strips)
        StopReason.BluetoothOff -> stringResource(R.string.music_stopped_bluetooth)
        StopReason.BluetoothPermissionMissing -> stringResource(R.string.music_stopped_bluetooth_permission)
    }
    MusicStatus.Idle -> idleText(state.source)
}

@Composable
private fun idleText(source: MusicSource): String =
    stringResource(if (source == MusicSource.PhoneAudio) R.string.music_idle_phone else R.string.music_idle)

private fun MusicSource.labelRes(): Int = when (this) {
    MusicSource.PhoneAudio -> R.string.music_source_phone
    MusicSource.Microphone -> R.string.music_source_mic
}

private fun MusicSource.bodyRes(): Int = when (this) {
    MusicSource.PhoneAudio -> R.string.music_source_phone_body
    MusicSource.Microphone -> R.string.music_source_mic_body
}

private val MusicStatus.isFailure: Boolean
    get() = this is MusicStatus.Stopped && reason != StopReason.User

private fun MusicStyle.labelRes(): Int = when (this) {
    MusicStyle.Pulse -> R.string.music_style_pulse
    MusicStyle.Spectrum -> R.string.music_style_spectrum
    MusicStyle.Energy -> R.string.music_style_energy
    MusicStyle.Calm -> R.string.music_style_calm
    MusicStyle.BeatLock -> R.string.music_style_beat_lock
}

private fun MusicStyle.bodyRes(): Int = when (this) {
    MusicStyle.Pulse -> R.string.music_style_pulse_body
    MusicStyle.Spectrum -> R.string.music_style_spectrum_body
    MusicStyle.Energy -> R.string.music_style_energy_body
    MusicStyle.Calm -> R.string.music_style_calm_body
    MusicStyle.BeatLock -> R.string.music_style_beat_lock_body
}

private fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
