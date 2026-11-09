package com.kernoeb.fff3.ui.control

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kernoeb.fff3.R
import com.kernoeb.fff3.protocol.EffectInfo
import com.kernoeb.fff3.ui.theme.toColor

/**
 * The family's effects, grouped as the catalogue groups them, each with a live preview.
 * Previews read one shared clock while drawing, so they animate without recomposing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EffectsSection(
    effects: List<EffectInfo>,
    selectedId: Int?,
    speed: SliderState?,
    onSpeedChange: (Float) -> Unit,
    onPick: (EffectInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = it - start }
    }
    val previews = remember(effects) { effects.associate { it.id to EffectPreview.of(it) } }
    val accent = MaterialTheme.colorScheme.primary
    val periodOf = { EffectPreview.periodFor(speed?.value ?: 50f) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (speed != null) {
            Text(stringResource(R.string.control_speed), style = MaterialTheme.typography.titleSmall)
            BigSlider(speed, onSpeedChange, stringResource(R.string.control_speed), fill = { accent })
        }
        effects.groupBy { it.group }.forEach { (group, items) ->
            Text(
                group,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                items.forEach { effect ->
                    EffectCard(
                        effect = effect,
                        preview = previews.getValue(effect.id),
                        selected = effect.id == selectedId,
                        time = { clock.longValue },
                        period = periodOf,
                        onClick = { onPick(effect) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (items.size % 2 == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EffectCard(
    effect: EffectInfo,
    preview: EffectPreview,
    selected: Boolean,
    time: () -> Long,
    period: () -> Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(
        if (selected) 28.dp else 16.dp,
        MaterialTheme.motionSchemeSpatial(),
        label = "corner",
    )
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = modifier,
        shape = RoundedCornerShape(corner),
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .drawBehind { drawPreview(preview, time(), period()) },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                effect.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPreview(
    preview: EffectPreview,
    time: Long,
    period: Long,
) {
    if (!preview.isSpatial) {
        drawRect(preview.colorAt(time, period).toColor())
        return
    }
    val leds = 4
    val cell = size.width / leds
    repeat(leds) { index ->
        drawRect(
            preview.colorAt(time, period, index / leds.toFloat()).toColor(),
            topLeft = Offset(cell * index, 0f),
            size = Size(cell + 0.5f, size.height),
        )
    }
}

/** The controller's own microphone: on or off, a mode, and how loud a sound must be. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MicSection(
    enabled: Boolean,
    modes: List<String>,
    selectedMode: Int,
    sensitivity: SliderState?,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (Int) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.control_mic_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.control_mic_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onEnabledChange(it)
                })
            }
        }
        if (modes.isNotEmpty()) {
            Text(stringResource(R.string.control_mic_mode), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEachIndexed { index, mode ->
                    FilterChip(
                        selected = enabled && index == selectedMode,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onModeChange(index)
                        },
                        label = { Text(mode) },
                    )
                }
            }
        }
        if (sensitivity != null) {
            Text(stringResource(R.string.control_mic_sensitivity), style = MaterialTheme.typography.titleSmall)
            BigSlider(
                sensitivity,
                onSensitivityChange,
                stringResource(R.string.control_mic_sensitivity),
                fill = { accent },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialTheme.motionSchemeSpatial() = motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.Dp>()
