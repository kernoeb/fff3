package com.kernoeb.fff3.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kernoeb.fff3.R
import com.kernoeb.fff3.ble.ConnectionState
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.common.PowerChoice
import com.kernoeb.fff3.ui.common.labelRes
import com.kernoeb.fff3.ui.theme.toColor
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(32.dp)

/** One menu entry of a card's overflow and long-press menu. */
data class CardAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlowCard(
    colours: List<Color>,
    lit: Boolean,
    unknown: Boolean,
    onClick: (() -> Unit)?,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val base = MaterialTheme.colorScheme.surfaceContainer
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val first by animateColorAsState(
        if (unknown) neutral else glowOf(colours.getOrNull(0), lit), tween(450), label = "glow",
    )
    val second by animateColorAsState(
        if (unknown) neutral else glowOf(colours.getOrNull(1) ?: colours.getOrNull(0), lit), tween(450), label = "glow2",
    )
    Box(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .drawBehind {
                drawRect(base)
                drawRect(
                    Brush.radialGradient(
                        listOf(first, Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.1f),
                        radius = size.maxDimension * 0.8f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(second.copy(alpha = second.alpha * 0.7f), Color.Transparent),
                        center = Offset(size.width, size.height),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            }
            .then(
                if (onClick == null && actions.isEmpty()) Modifier
                else Modifier.combinedClickable(
                    onClick = { onClick?.invoke() ?: run { menuOpen = true } },
                    onLongClick = if (actions.isEmpty()) null else {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                ),
            ),
    ) {
        content { menuOpen = true }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp)) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = MaterialTheme.shapes.large,
            ) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            menuOpen = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}

private fun glowOf(colour: Color?, lit: Boolean): Color = when {
    colour == null -> Color.Transparent
    lit -> colour.copy(alpha = 0.55f)
    else -> colour.copy(alpha = 0.10f)
}

@Composable
fun DeviceCard(
    item: DeviceItem,
    onOpen: () -> Unit,
    onPower: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
) {
    val on = item.state.power == true
    val unknown = item.state.unknown
    val colour = item.state.color?.takeUnless { unknown }?.toColor()
    GlowCard(listOfNotNull(colour), lit = on, unknown = unknown, onClick = onOpen, actions = actions, modifier = modifier) { openMenu ->
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColourOrb(colour, on)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.device.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ConnectionLine(item.connection, stringResource(item.connection.labelRes()))
                    if (unknown) UnknownHint()
                }
                MoreButton(openMenu)
                PowerChoice(item.state.power, onPower, compact = true)
            }
            BrightnessRow(item.state.brightness, enabled = item.state.power != false, onBrightness = onBrightness)
        }
    }
}

@Composable
fun GroupCard(
    item: GroupItem,
    onOpen: () -> Unit,
    onPower: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
) {
    SummaryCard(
        title = item.group.name,
        subtitle = groupSubtitle(item.summary),
        summary = item.summary,
        onOpen = onOpen,
        onPower = onPower,
        onBrightness = onBrightness,
        actions = actions,
        modifier = modifier,
    )
}

/** The card at the top that acts on every strip at once. */
@Composable
fun AllStripsCard(
    summary: MemberSummary,
    hasGroup: Boolean,
    onOpen: (() -> Unit)?,
    onMakeGroup: () -> Unit,
    onPower: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SummaryCard(
        title = stringResource(R.string.home_all_strips),
        subtitle = groupSubtitle(summary),
        summary = summary,
        onOpen = onOpen,
        onPower = onPower,
        onBrightness = onBrightness,
        actions = emptyList(),
        modifier = modifier,
        footer = if (hasGroup) null else {
            {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_all_group_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onMakeGroup) { Text(stringResource(R.string.home_all_group_make)) }
                }
            }
        },
    )
}

@Composable
private fun SummaryCard(
    title: String,
    subtitle: String,
    summary: MemberSummary,
    onOpen: (() -> Unit)?,
    onPower: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    actions: List<CardAction>,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    val colours = summary.colours.map(Rgb::toColor)
    val lit = summary.onCount > 0
    val unknown = summary.count > 0 && (summary.unknown || colours.isEmpty())
    GlowCard(colours, lit = lit, unknown = unknown, onClick = onOpen, actions = actions, modifier = modifier) { openMenu ->
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColourStack(colours, lit)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actions.isNotEmpty()) MoreButton(openMenu)
                PowerChoice(summary.power, onPower, compact = true)
            }
            BrightnessRow(summary.brightness, enabled = summary.count > 0, onBrightness = onBrightness)
            footer?.invoke()
        }
    }
}

@Composable
private fun groupSubtitle(summary: MemberSummary): String = when {
    summary.count == 0 -> stringResource(R.string.home_group_empty)
    summary.unknown -> stringResource(R.string.home_group_status_unknown, summary.connectedCount, summary.count)
    else -> stringResource(R.string.home_group_status, summary.onCount, summary.count, summary.connectedCount)
}

@Composable
private fun MoreButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(R.drawable.ic_more_vert),
            stringResource(R.string.action_more),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnknownHint() {
    Text(
        stringResource(R.string.state_unknown),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BrightnessRow(brightness: Int?, enabled: Boolean, onBrightness: (Int) -> Unit) {
    // Holds the finger position while dragging; the stored value takes over again on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val label = stringResource(R.string.control_brightness)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Icon(
            painterResource(R.drawable.ic_lightbulb),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Slider(
            value = dragging ?: (brightness ?: 100).toFloat(),
            onValueChange = {
                dragging = it
                onBrightness(it.roundToInt())
            },
            onValueChangeFinished = { dragging = null },
            valueRange = 1f..100f,
            enabled = enabled,
            colors = if (brightness == null && dragging == null) {
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.outline,
                    activeTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                )
            } else {
                SliderDefaults.colors(
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                )
            },
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .semanticsLabel(label),
        )
    }
}

@Composable
private fun ColourOrb(colour: Color?, on: Boolean) {
    val fill = colour ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val animated by animateColorAsState(if (on) fill else fill.copy(alpha = 0.35f), tween(450), label = "orb")
    Box(
        Modifier
            .size(44.dp)
            .background(animated, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape),
    )
}

/** Up to 4 overlapping dots, one per member colour. */
@Composable
private fun ColourStack(colours: List<Color>, lit: Boolean) {
    val shown = colours.take(4).ifEmpty { listOf(MaterialTheme.colorScheme.surfaceContainerHighest) }
    val ring = MaterialTheme.colorScheme.surfaceContainer
    Box(Modifier.size(width = 44.dp + 14.dp * (shown.size - 1), height = 44.dp)) {
        shown.forEachIndexed { index, colour ->
            Box(
                Modifier
                    .offset(x = 14.dp * index)
                    .size(44.dp)
                    .graphicsLayer { alpha = if (lit) 1f else 0.35f }
                    .border(2.dp, ring, CircleShape)
                    .padding(2.dp)
                    .background(colour, CircleShape),
            )
        }
    }
}

@Composable
private fun ConnectionLine(connection: ConnectionState, label: String) {
    val dot = when (connection) {
        ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
        is ConnectionState.WaitingToRetry, ConnectionState.BluetoothOff,
        ConnectionState.PermissionMissing, ConnectionState.Unsupported -> MaterialTheme.colorScheme.error
        ConnectionState.Idle -> MaterialTheme.colorScheme.outline
    }
    val pulse = if (connection is ConnectionState.Connecting) {
        val transition = rememberInfiniteTransition(label = "connecting")
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
    } else {
        1f
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse }.background(dot, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

internal fun Modifier.semanticsLabel(label: String): Modifier =
    this.then(Modifier.semantics { contentDescription = label })
