package com.kernoeb.fff3.ui.control

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.theme.toColor
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Position of the wheel thumb. Values are snapshot state, read only while drawing,
 * so a drag redraws the wheel without recomposing anything.
 */
@Stable
class ColorWheelState(hue: Float = 0f, saturation: Float = 1f) {
    var hue by mutableFloatStateOf(hue)
        private set
    var saturation by mutableFloatStateOf(saturation)
        private set

    /** 0 at rest, 1 while the finger holds the thumb. Grows the thumb. */
    internal val grab = Animatable(0f)
    private val mutex = MutatorMutex()

    val color: Rgb get() = Rgb.fromHsv(hue, saturation)

    /** Moves the thumb now, cancelling any running animation. */
    suspend fun snapTo(hue: Float, saturation: Float) = mutex.mutate(MutatePriority.UserInput) {
        set(hue, saturation)
    }

    internal fun set(hue: Float, saturation: Float) {
        this.hue = hue
        this.saturation = saturation.coerceIn(0f, 1f)
    }

    /** Glides the thumb to [rgb], turning the short way round. A drag interrupts it. */
    suspend fun animateTo(rgb: Rgb, spec: AnimationSpec<Float> = spring(Spring.DampingRatioLowBouncy, 380f)) =
        mutex.mutate {
            val target = rgb.toHsv()
            val startHue = hue
            val startSaturation = saturation
            // A grey colour has no hue: keep the current one so the thumb moves straight in.
            val hueDelta = if (target.saturation < 0.02f) 0f else WheelGeometry.shortestHueDelta(startHue, target.hue)
            Animatable(0f).animateTo(1f, spec) {
                set(
                    ((startHue + hueDelta * value) % 360f + 360f) % 360f,
                    startSaturation + (target.saturation - startSaturation) * value,
                )
            }
        }

    /** Stops a running [animateTo], so a new drag owns the thumb. */
    internal suspend fun stopAnimation() = mutex.mutate(MutatePriority.UserInput) {}
}

/**
 * A hue and saturation disc, drawn on a Canvas. [onColorChange] runs for every drag event; pass it
 * straight to the controller, which coalesces sends.
 */
@Composable
fun ColorWheel(
    state: ColorWheelState,
    onColorChange: (Rgb) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val onChange by rememberUpdatedState(onColorChange)
    val scope = rememberCoroutineScope()

    Spacer(
        modifier
            .testTag("colour_wheel")
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val radius = wheelRadius(size.width.toFloat(), size.height.toFloat(), thumbRadiusPx())
                    val center = Offset(size.width / 2f, size.height / 2f)
                    // Touches outside the disc belong to the page scroll.
                    if ((down.position - center).getDistance() > radius + 24.dp.toPx()) return@awaitEachGesture

                    fun moveTo(position: Offset) {
                        val (hue, saturation) = WheelGeometry.hueSaturationAt(
                            position.x - center.x, position.y - center.y, radius,
                        )
                        state.set(hue, saturation)
                        onChange(state.color)
                    }

                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    scope.launch { state.stopAnimation() }
                    scope.launch { state.grab.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, 600f)) }
                    down.consume()
                    var pointerId = down.id
                    moveTo(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: event.changes.firstOrNull { it.pressed }?.also { pointerId = it.id }
                            ?: break
                        if (!change.pressed) break
                        if (change.position != change.previousPosition) moveTo(change.position)
                        change.consume()
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    scope.launch { state.grab.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, 400f)) }
                }
            }
            .drawWithCache {
                val thumb = thumbRadiusPx()
                val radius = wheelRadius(size.width, size.height, thumb)
                val center = Offset(size.width / 2f, size.height / 2f)
                val hues = Brush.sweepGradient(
                    (0..12).map { Rgb.fromHsv(it * 30f).toColor() },
                    center = center,
                )
                val whiteCore = Brush.radialGradient(
                    listOf(Color.White, Color.White.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                )
                val ring = Stroke(width = 3.dp.toPx())
                val shadow = Color.Black.copy(alpha = 0.35f)
                onDrawBehind {
                    drawCircle(hues, radius, center)
                    drawCircle(whiteCore, radius, center)
                    val (dx, dy) = WheelGeometry.offsetOf(state.hue, state.saturation, radius)
                    val at = Offset(center.x + dx, center.y + dy)
                    val r = thumb * (1f + 0.35f * state.grab.value)
                    drawCircle(shadow, r + 2.dp.toPx(), at + Offset(0f, 1.5f.dp.toPx()))
                    drawCircle(state.color.toColor(), r, at)
                    drawCircle(Color.White, r, at, style = ring)
                }
            },
    )
}

private fun androidx.compose.ui.unit.Density.thumbRadiusPx() = 14.dp.toPx()

/** The disc leaves room for the grown thumb at the rim. */
private fun wheelRadius(width: Float, height: Float, thumb: Float) = min(width, height) / 2f - thumb * 1.4f
