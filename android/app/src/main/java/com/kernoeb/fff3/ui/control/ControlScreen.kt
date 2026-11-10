package com.kernoeb.fff3.ui.control

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.appViewModel
import com.kernoeb.fff3.ui.common.PowerChoice
import com.kernoeb.fff3.ui.theme.toColor
import kotlinx.coroutines.launch

/**
 * Controls one strip or a group. Sends are optimistic: controls work whatever the link state,
 * and the controller delivers once a strip is reachable.
 *
 * Drag positions (wheel, sliders) live here, never in the ViewModel, and are read only while
 * drawing, so a drag redraws a few views without recomposing the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ControlScreen(target: ControlTarget, onBack: () -> Unit, onOpenMusic: (() -> Unit)? = null) {
    val viewModel = appViewModel(key = target.encode()) {
        ControlViewModel(target, it.repository, it.controller, it.deviceStates)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wheel = remember { ColorWheelState() }
    val brightness = remember { SliderState(value = 100f, trackRange = 0f..100f) }
    val speed = remember { SliderState(value = 50f, trackRange = 0f..100f) }
    val sensitivity = remember { SliderState(value = 50f, trackRange = 0f..100f) }
    var savedMode by rememberSaveable { mutableStateOf<ControlMode?>(null) }
    var micMode by rememberSaveable { mutableIntStateOf(0) }
    var favouriteDraft by remember { mutableStateOf<Favourite?>(null) }
    var brightnessKnown by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        val inputs = viewModel.currentInputs()
        inputs.color?.toHsv()?.let { wheel.snapTo(it.hue, it.saturation) }
        inputs.brightness?.let {
            brightness.value = it
            brightnessKnown = true
        }
        speed.value = inputs.speed
        if (savedMode == null) savedMode = if (viewModel.state.value.effectId != null) ControlMode.Effects else ControlMode.Colour
    }

    val features = state.features
    val modes = buildList {
        if (features.color) add(ControlMode.Colour)
        if (features.effects.isNotEmpty()) add(ControlMode.Effects)
        if (features.mic) add(ControlMode.Mic)
    }
    val mode = savedMode?.takeIf { it in modes } ?: modes.firstOrNull() ?: ControlMode.Colour
    val accent = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.outline
    // The brightness track shows the live wheel colour, or the accent while an effect runs.
    val showsWheelColour = state.effectId == null && state.micEnabled != true
    val liveFill: () -> Color = when {
        !brightnessKnown -> ({ neutral })
        showsWheelColour -> ({ if (wheel.known) wheel.color.toColor() else neutral })
        else -> ({ accent })
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (onOpenMusic != null) {
                        IconButton(onClick = onOpenMusic) {
                            Icon(painterResource(R.drawable.ic_music_note), stringResource(R.string.control_music))
                        }
                    }
                    if (state.loaded && state.exists) {
                        IconButton(onClick = {
                            val look = viewModel.currentLook()
                            if (look == null) {
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.control_favourite_nothing)) }
                            } else {
                                favouriteDraft = look
                            }
                        }) {
                            Icon(painterResource(R.drawable.ic_star), stringResource(R.string.control_save_favourite))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.loaded && !state.exists) {
            Text(
                stringResource(R.string.control_not_found),
                modifier = Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val sidePadding = Modifier.padding(horizontal = 24.dp)
            if (state.members.isNotEmpty()) {
                ConnectionChips(state.members, showNames = state.isGroup, modifier = sidePadding)
            }

            if (features.power) {
                PowerChoice(state.power, viewModel::setPower, sidePadding.fillMaxWidth())
            }
            if (features.brightness) {
                BigSlider(
                    brightness,
                    {
                        brightnessKnown = true
                        viewModel.setBrightness(it)
                    },
                    stringResource(R.string.control_brightness),
                    modifier = sidePadding,
                    fill = liveFill,
                )
            }

            if (modes.size > 1) {
                Box(sidePadding) { ModeSelector(modes, mode, onSelect = { savedMode = it }) }
            }

            val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
            val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(spatial) { if (forward) it / 4 else -it / 4 } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { if (forward) -it / 4 else it / 4 } + fadeOut(effects))
                },
                label = "mode",
            ) { shown ->
                when (shown) {
                    ControlMode.Colour -> ColourPage(
                        wheel = wheel,
                        onColor = viewModel::setColor,
                        onSwatch = { rgb ->
                            viewModel.setColor(rgb)
                            scope.launch { wheel.animateTo(rgb) }
                        },
                    )
                    ControlMode.Effects -> EffectsSection(
                        effects = features.effects,
                        selectedId = state.effectId,
                        speed = speed.takeIf { features.speed },
                        onSpeedChange = viewModel::setSpeed,
                        onPick = { viewModel.playEffect(it.id, speed.value) },
                        modifier = sidePadding,
                    )
                    ControlMode.Mic -> MicSection(
                        enabled = state.micEnabled == true,
                        modes = features.micModes,
                        selectedMode = micMode,
                        sensitivity = sensitivity.takeIf { features.micSensitivity },
                        onEnabledChange = viewModel::setMicEnabled,
                        onModeChange = {
                            micMode = it
                            viewModel.setMicMode(it)
                        },
                        onSensitivityChange = viewModel::setMicSensitivity,
                        modifier = sidePadding,
                    )
                }
            }
        }
    }

    favouriteDraft?.let { draft ->
        SaveFavouriteDialog(
            suggestedName = suggestName(draft, state.features),
            onDismiss = { favouriteDraft = null },
            onSave = { name ->
                favouriteDraft = null
                viewModel.saveFavourite(draft.copy(name = name)) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.control_favourite_saved)) }
                }
            },
        )
    }
}

@Composable
private fun ColourPage(wheel: ColorWheelState, onColor: (Rgb) -> Unit, onSwatch: (Rgb) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColorWheel(
                state = wheel,
                onColorChange = onColor,
                contentDescription = stringResource(R.string.control_wheel_description),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        }
        SwatchRow(onPick = onSwatch)
    }
}

private fun suggestName(draft: Favourite, features: ControlFeatures): String =
    draft.commands.firstNotNullOfOrNull { command ->
        when (command) {
            is Command.Effect -> features.effects.firstOrNull { it.id == command.id }?.name
            is Command.Color -> command.rgb.toHex()
            else -> null
        }
    }.orEmpty()

@Composable
private fun SaveFavouriteDialog(suggestedName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.control_favourite_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.control_favourite_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
