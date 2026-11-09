package com.kernoeb.fff3.ui.control

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.ConnectionState
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.common.labelRes
import com.kernoeb.fff3.ui.theme.toColor

enum class ControlMode { Colour, Effects, Mic }

/** A thick Expressive slider whose filled part takes the colour from [fill], read only while drawing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BigSlider(
    state: SliderState,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    fill: () -> Color,
) {
    val haptics = LocalHapticFeedback.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Slider(
        state = state,
        modifier = modifier.semantics { contentDescription = label },
        onValueChange = {
            state.value = it
            onValueChange(it)
        },
        onValueChangeFinished = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface),
        track = { sliderState ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .drawBehind {
                        val range = sliderState.trackRange
                        val fraction = ((sliderState.value - range.start) / (range.endInclusive - range.start))
                            .coerceIn(0f, 1f)
                        val corner = CornerRadius(size.height / 2.5f)
                        drawRoundRect(track, cornerRadius = corner)
                        val gap = 6.dp.toPx()
                        val width = (size.width * fraction - gap).coerceAtLeast(0f)
                        if (width > 0f) drawRoundRect(fill(), size = Size(width, size.height), cornerRadius = corner)
                    },
            )
        },
    )
}

/** Connected toggle buttons that switch between colour, effects and mic. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModeSelector(modes: List<ControlMode>, selected: ControlMode, onSelect: (ControlMode) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, mode ->
            ToggleButton(
                checked = mode == selected,
                onCheckedChange = {
                    if (mode != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(mode)
                },
                modifier = Modifier.weight(1f).semantics { role = Role.Tab },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(stringResource(mode.labelRes()))
            }
        }
    }
}

private fun ControlMode.labelRes(): Int = when (this) {
    ControlMode.Colour -> R.string.control_mode_colour
    ControlMode.Effects -> R.string.control_mode_effects
    ControlMode.Mic -> R.string.control_mode_mic
}

/** A large power button that morphs its shape when on. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PowerButton(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    ToggleButton(
        checked = on,
        onCheckedChange = {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onToggle(it)
        },
        modifier = modifier.size(width = 72.dp, height = 56.dp),
        shapes = ToggleButtonDefaults.shapesFor(56.dp),
        contentPadding = ToggleButtonDefaults.contentPaddingFor(56.dp),
    ) {
        Icon(painterResource(R.drawable.ic_power_settings_new), stringResource(R.string.control_power))
    }
}

/** Cool white and warm white first, then saturated colours. */
val QuickSwatches: List<Rgb> = listOf(
    Rgb.fromKelvin(6500),
    Rgb.fromKelvin(2700),
    Rgb(0xFF0000),
    Rgb(0xFF6A00),
    Rgb(0xFFC800),
    Rgb(0x00FF40),
    Rgb(0x00E5FF),
    Rgb(0x0040FF),
    Rgb(0x8A00FF),
    Rgb(0xFF0090),
)

@Composable
fun SwatchRow(onPick: (Rgb) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val ring = MaterialTheme.colorScheme.outlineVariant
    val white = stringResource(R.string.control_swatch_white)
    val warm = stringResource(R.string.control_warm_white)
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickSwatches.forEachIndexed { index, rgb ->
            val description = when (index) {
                0 -> white
                1 -> warm
                else -> stringResource(R.string.control_swatch_colour, rgb.toHex())
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, ring, CircleShape)
                    .drawBehind { drawCircle(rgb.toColor()) }
                    .clickable(role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onPick(rgb)
                    }
                    .semantics { contentDescription = description },
            )
        }
    }
}

/** One small pill per strip: a dot and its link state. Controls work whatever the state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionChips(members: List<ControlMember>, showNames: Boolean, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        members.forEach { member -> ConnectionChip(member, showNames) }
    }
}

@Composable
private fun ConnectionChip(member: ControlMember, showName: Boolean) {
    val state = member.connection
    val label = stringResource(state.labelRes())
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateDot(state)
            Spacer(Modifier.width(8.dp))
            Text(
                if (showName) "${member.name} · $label" else label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun StateDot(state: ConnectionState) {
    val colors = MaterialTheme.colorScheme
    val busy = state is ConnectionState.Connecting || state is ConnectionState.WaitingToRetry
    val color = when (state) {
        ConnectionState.Connected -> colors.primary
        is ConnectionState.Connecting, is ConnectionState.WaitingToRetry -> colors.tertiary
        ConnectionState.Idle -> colors.outline
        ConnectionState.BluetoothOff, ConnectionState.PermissionMissing, ConnectionState.Unsupported -> colors.error
    }
    val pulse = if (busy) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }
    Box(Modifier.size(8.dp).drawBehind { drawCircle(color.copy(alpha = pulse.value)) })
}
