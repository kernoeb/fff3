package com.kernoeb.fff3.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.appViewModel
import com.kernoeb.fff3.ui.common.labelRes
import com.kernoeb.fff3.ui.theme.toColor

/**
 * Placeholder control screen: power, a hue slider and brightness, enough to check live dragging
 * on the real strips. The real screen replaces it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(target: ControlTarget, onBack: () -> Unit) {
    val viewModel = appViewModel(key = target.encode()) {
        ControlViewModel(target, it.repository, it.controller, it.deviceStates)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Drag positions live here, not in the ViewModel: a drag then only redraws the slider and preview.
    val hue = remember { SliderState(value = 0f, trackRange = 0f..359f) }
    val brightness = remember { SliderState(value = 100f, trackRange = 0f..100f) }
    LaunchedEffect(viewModel) {
        val inputs = viewModel.currentInputs()
        hue.value = inputs.hue
        brightness.value = inputs.brightness
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title)
                        Text(
                            text = connectionSummary(state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ColourPreview { Rgb.fromHsv(hue.value).toColor() }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { viewModel.setPower(true) }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_power_settings_new), contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.control_on))
                }
                OutlinedButton(onClick = { viewModel.setPower(false) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.control_off))
                }
            }

            Text(stringResource(R.string.control_colour), style = MaterialTheme.typography.titleMedium)
            HueStrip()
            Slider(state = hue, onValueChange = {
                hue.value = it
                viewModel.setHue(it)
            })

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { viewModel.setColor(Rgb.White) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.control_white))
                }
                FilledTonalButton(onClick = { viewModel.setColor(Rgb.fromKelvin(2700)) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.control_warm_white))
                }
            }

            Text(stringResource(R.string.control_brightness), style = MaterialTheme.typography.titleMedium)
            Slider(state = brightness, onValueChange = {
                brightness.value = it
                viewModel.setBrightness(it)
            })

            if (state.isGroup) {
                Text(stringResource(R.string.control_members), style = MaterialTheme.typography.titleMedium)
                state.members.forEach { member ->
                    ListItem(
                        supportingContent = { Text(stringResource(member.connection.labelRes())) },
                    ) { Text(member.name) }
                }
            }
        }
    }
}

@Composable
private fun connectionSummary(state: ControlUiState): String = when {
    state.members.isEmpty() -> ""
    state.isGroup -> stringResource(R.string.control_members_connected, state.connectedCount, state.members.size)
    else -> stringResource(state.members.first().connection.labelRes())
}

/** [color] is read in the draw phase only, so a drag redraws the box without recomposing. */
@Composable
private fun ColourPreview(color: () -> Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(28.dp))
            .drawBehind { drawRect(color()) },
    )
}

@Composable
private fun HueStrip() {
    val brush = remember { Brush.horizontalGradient((0..6).map { Rgb.fromHsv(it * 60f).toColor() }) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .background(brush, RoundedCornerShape(6.dp)),
    )
}
