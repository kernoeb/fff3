package com.kernoeb.fff3.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The fff3 theme: always dark, Material 3 Expressive motion, accents built from [seed].
 *
 * Pass the strip's current colour as [seed] to make the UI follow it; null uses [DefaultSeed].
 * Changes animate. You can nest it, for example around one card to tint it with that strip's colour.
 * Feed it a settled colour, not every drag event: a new seed recomposes everything that reads colours.
 */
@Composable
fun Fff3Theme(seed: Color? = null, content: @Composable () -> Unit) {
    val target = seed ?: DefaultSeed
    // The OKLCH maths runs once per seed. Animation frames only blend two ready schemes.
    val targetScheme = remember(target) { stripDarkColorScheme(target) }
    val transition = remember { SchemeTransition(targetScheme) }
    LaunchedEffect(targetScheme) { transition.animateTo(targetScheme) }
    MaterialExpressiveTheme(
        colorScheme = transition.current,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@Stable
private class SchemeTransition(initial: ColorScheme) {
    private var from by mutableStateOf(initial)
    private var to by mutableStateOf(initial)
    private val progress = Animatable(1f)

    val current: ColorScheme
        get() = progress.value.let { if (it >= 1f) to else lerp(from, to, it) }

    suspend fun animateTo(target: ColorScheme) {
        if (target == to) return
        from = current
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 450))
    }
}
