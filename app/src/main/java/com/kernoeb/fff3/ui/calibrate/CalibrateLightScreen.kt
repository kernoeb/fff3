package com.kernoeb.fff3.ui.calibrate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.appContainer
import com.kernoeb.fff3.calibrate.LightCamera
import com.kernoeb.fff3.ui.appViewModel
import kotlin.math.roundToInt

/** Measures the light delay of one strip with the camera: from a colour write to the strip showing it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalibrateLightScreen(initialAddress: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel = appViewModel(key = "calibrate-light") {
        CalibrateLightViewModel(
            initialAddress, it.repository, it.controller, it.deviceStates, it.frameWrites, it.music,
            LightCamera(context.applicationContext),
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cameraState by viewModel.camera.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    var granted by remember { mutableStateOf(hasCamera(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }

    // The preview and the box follow the phone's natural orientation; turning mid-run would break both.
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        onDispose { if (before != null) activity.requestedOrientation = before }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, granted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // The user may have allowed the camera in the app settings meanwhile.
                Lifecycle.Event.ON_START -> if (granted) viewModel.openCamera() else granted = hasCamera(context)
                Lifecycle.Event.ON_STOP -> if (granted) viewModel.closeCamera()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (granted) viewModel.closeCamera()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calibrate_title)) },
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
            Text(
                stringResource(R.string.calibrate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (granted) {
                CameraPreview(viewModel.camera, Modifier.align(Alignment.CenterHorizontally))
                Text(
                    cameraText(cameraState),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cameraState is LightCamera.State.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PermissionCard(
                    denied = denied && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false,
                    onAllow = { permission.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }

            if (state.strips.size > 1) {
                Text(stringResource(R.string.calibrate_strip), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.strips.forEach { strip ->
                        FilterChip(
                            selected = strip.address == state.address,
                            onClick = { viewModel.select(strip.address) },
                            enabled = !state.running,
                            label = { Text(strip.displayName) },
                        )
                    }
                }
            }
            if (state.loaded && state.strips.isEmpty()) {
                Text(stringResource(R.string.music_no_strips), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                state.savedMs?.let { stringResource(R.string.calibrate_saved_value, it) }
                    ?: stringResource(R.string.calibrate_not_measured),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.musicRunning) {
                Text(
                    stringResource(R.string.calibrate_music_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            PhaseSection(
                state = state,
                cameraReady = granted && cameraState is LightCamera.State.Ready,
                onStart = viewModel::start,
                onCancel = viewModel::cancel,
                onSave = viewModel::save,
            )
        }
    }
}

@Composable
private fun PhaseSection(
    state: CalibrateUiState,
    cameraReady: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val canStart = cameraReady && !state.musicRunning && state.address != null
    when (val phase = state.phase) {
        CalibrationPhase.Idle -> StartButton(R.string.calibrate_start, canStart, onStart)
        is CalibrationPhase.Running -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (phase.flash == 0) stringResource(R.string.calibrate_preparing)
                else stringResource(R.string.calibrate_progress, phase.flash, phase.total),
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = { phase.flash.toFloat() / phase.total },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        is CalibrationPhase.Done -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(
                    R.string.calibrate_result,
                    phase.result.delayMs.roundToInt(),
                    phase.result.spreadMs.roundToInt(),
                    phase.result.kept,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (phase.result.kept < phase.result.total) {
                Text(
                    stringResource(R.string.calibrate_rejected, phase.result.total - phase.result.kept),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !phase.saved, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (phase.saved) R.string.calibrate_saved else R.string.action_save))
                }
                OutlinedButton(onClick = onStart, enabled = canStart, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calibrate_retry))
                }
            }
        }
        is CalibrationPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(
                    when (phase.reason) {
                        CalibrationFailure.NotReached -> R.string.calibrate_error_not_reached
                        CalibrationFailure.NoChange -> R.string.calibrate_error_no_change
                        CalibrationFailure.TooFew -> R.string.calibrate_error_too_few
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            StartButton(R.string.calibrate_retry, canStart, onStart)
        }
    }
}

@Composable
private fun StartButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) }
}

/** The camera image with the target box. Tap to move the box onto the strip. */
@Composable
private fun CameraPreview(camera: LightCamera, modifier: Modifier = Modifier) {
    val bitmap by camera.preview.collectAsStateWithLifecycle()
    val box by camera.box.collectAsStateWithLifecycle()
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: (3f / 4f)
    val background = MaterialTheme.colorScheme.surfaceContainer
    val outline = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .heightIn(max = 440.dp)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .pointerInput(camera) {
                detectTapGestures { camera.moveBox(it.x / size.width, it.y / size.height) }
            }
            .drawBehind {
                image?.let {
                    drawImage(
                        it,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                }
                val boxSize = Size(size.width * LightCamera.Box.WIDTH, size.height * LightCamera.Box.HEIGHT)
                val topLeft = Offset(size.width * box.x - boxSize.width / 2, size.height * box.y - boxSize.height / 2)
                val stroke = 3.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = 0.5f), topLeft, boxSize, CornerRadius(12.dp.toPx()), style = Stroke(stroke * 2))
                drawRoundRect(outline, topLeft, boxSize, CornerRadius(12.dp.toPx()), style = Stroke(stroke))
            },
    )
}

@Composable
private fun PermissionCard(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(if (denied) R.string.calibrate_camera_denied else R.string.calibrate_camera_why),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (denied) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.music_permission_settings)) }
        } else {
            Button(onClick = onAllow) { Text(stringResource(R.string.calibrate_allow_camera)) }
        }
    }
}

private fun hasCamera(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun cameraText(state: LightCamera.State): String = when (state) {
    LightCamera.State.Closed, LightCamera.State.Opening -> stringResource(R.string.calibrate_camera_opening)
    is LightCamera.State.Ready -> if (state.fps > 0) stringResource(R.string.calibrate_camera_rate, state.fps)
    else stringResource(R.string.calibrate_camera_opening)
    is LightCamera.State.Failed -> stringResource(
        when (state.reason) {
            LightCamera.Failure.NoCamera -> R.string.calibrate_error_no_camera
            LightCamera.Failure.UnknownClock -> R.string.calibrate_error_clock
            LightCamera.Failure.Unavailable -> R.string.calibrate_error_camera
        },
    )
}
