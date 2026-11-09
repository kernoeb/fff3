package com.kernoeb.fff3.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val animated by animateColorAsState(target, animationSpec = tween(durationMillis = 450), label = "accent")
    val scheme = remember(animated) { stripDarkColorScheme(animated) }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
