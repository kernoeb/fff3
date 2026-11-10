package com.kernoeb.fff3.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.toArgb
import com.kernoeb.fff3.protocol.Rgb
import kotlin.math.min

/** The accent used before any strip colour is known: a soft violet. */
val DefaultSeed = Color(0xFF8C7BFF)

/**
 * A dark colour scheme whose accents follow [seed], usually the strip's current colour.
 * Surfaces stay near-black with a faint tint of the seed's hue, so the UI reads as "dark with the
 * strip's colour", whatever colour that is. Grey or white seeds give a neutral scheme.
 */
fun stripDarkColorScheme(seed: Color): ColorScheme {
    val lch = Oklch.fromArgb(seed.toArgb())
    // Below this chroma a seed is grey or white: tinting surfaces with its hue would look random.
    val chroma = if (lch.c < 0.03) 0.0 else lch.c
    val hue = lch.h
    fun tone(l: Double, maxChroma: Double, hueShift: Double = 0.0) =
        Color(Oklch.toArgb(l, min(chroma, maxChroma), hue + hueShift))
    fun neutral(l: Double) = tone(l, 0.012)
    fun neutralVariant(l: Double) = tone(l, 0.025)

    return darkColorScheme(
        primary = tone(0.82, 0.14),
        onPrimary = tone(0.26, 0.09),
        primaryContainer = tone(0.38, 0.12),
        onPrimaryContainer = tone(0.93, 0.05),
        inversePrimary = tone(0.48, 0.14),
        secondary = tone(0.82, 0.05),
        onSecondary = tone(0.26, 0.04),
        secondaryContainer = tone(0.34, 0.05),
        onSecondaryContainer = tone(0.93, 0.03),
        tertiary = tone(0.82, 0.10, hueShift = 60.0),
        onTertiary = tone(0.26, 0.07, hueShift = 60.0),
        tertiaryContainer = tone(0.38, 0.09, hueShift = 60.0),
        onTertiaryContainer = tone(0.93, 0.04, hueShift = 60.0),
        background = neutral(0.15),
        onBackground = neutral(0.93),
        surface = neutral(0.15),
        onSurface = neutral(0.93),
        surfaceVariant = neutralVariant(0.32),
        onSurfaceVariant = neutralVariant(0.80),
        surfaceTint = tone(0.82, 0.14),
        inverseSurface = neutral(0.93),
        inverseOnSurface = neutral(0.25),
        outline = neutralVariant(0.62),
        outlineVariant = neutralVariant(0.36),
        scrim = Color.Black,
        surfaceBright = neutral(0.30),
        surfaceDim = neutral(0.15),
        surfaceContainerLowest = neutral(0.12),
        surfaceContainerLow = neutral(0.18),
        surfaceContainer = neutral(0.20),
        surfaceContainerHigh = neutral(0.24),
        surfaceContainerHighest = neutral(0.28),
    )
}

/** Blends every colour that [stripDarkColorScheme] sets. Cheap: no gamut search, safe on every frame. */
fun lerp(from: ColorScheme, to: ColorScheme, fraction: Float): ColorScheme {
    fun mix(pick: ColorScheme.() -> Color) = lerpColor(from.pick(), to.pick(), fraction)
    return to.copy(
        primary = mix { primary },
        onPrimary = mix { onPrimary },
        primaryContainer = mix { primaryContainer },
        onPrimaryContainer = mix { onPrimaryContainer },
        inversePrimary = mix { inversePrimary },
        secondary = mix { secondary },
        onSecondary = mix { onSecondary },
        secondaryContainer = mix { secondaryContainer },
        onSecondaryContainer = mix { onSecondaryContainer },
        tertiary = mix { tertiary },
        onTertiary = mix { onTertiary },
        tertiaryContainer = mix { tertiaryContainer },
        onTertiaryContainer = mix { onTertiaryContainer },
        background = mix { background },
        onBackground = mix { onBackground },
        surface = mix { surface },
        onSurface = mix { onSurface },
        surfaceVariant = mix { surfaceVariant },
        onSurfaceVariant = mix { onSurfaceVariant },
        surfaceTint = mix { surfaceTint },
        inverseSurface = mix { inverseSurface },
        inverseOnSurface = mix { inverseOnSurface },
        outline = mix { outline },
        outlineVariant = mix { outlineVariant },
        surfaceBright = mix { surfaceBright },
        surfaceDim = mix { surfaceDim },
        surfaceContainerLowest = mix { surfaceContainerLowest },
        surfaceContainerLow = mix { surfaceContainerLow },
        surfaceContainer = mix { surfaceContainer },
        surfaceContainerHigh = mix { surfaceContainerHigh },
        surfaceContainerHighest = mix { surfaceContainerHighest },
    )
}

fun Rgb.toColor(): Color = Color(0xFF000000.toInt() or packed)

fun Color.toRgb(): Rgb = Rgb(toArgb() and 0xFFFFFF)
