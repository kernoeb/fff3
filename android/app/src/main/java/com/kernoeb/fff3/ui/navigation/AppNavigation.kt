package com.kernoeb.fff3.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.kernoeb.fff3.ui.add.AddDeviceScreen
import com.kernoeb.fff3.ui.calibrate.CalibrateLightScreen
import com.kernoeb.fff3.ui.control.ControlScreen
import com.kernoeb.fff3.ui.home.HomeScreen
import com.kernoeb.fff3.ui.music.MusicScreen
import com.kernoeb.fff3.ui.settings.SettingsScreen
import com.kernoeb.fff3.ui.strip.StripSettingsScreen

/**
 * Maps each [Route] to its screen. Each entry gets its own ViewModel store, so a screen's
 * ViewModel lives exactly as long as the route stays on the back stack.
 */
@Composable
fun AppNavigation(navigator: Navigator, modifier: Modifier = Modifier) {
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        onBack = navigator::back,
        transitionSpec = { forward() },
        popTransitionSpec = { pop(EmphasizedEasing) },
        // The library default scales the screen down while the finger drags. Slide instead.
        predictivePopTransitionSpec = { pop(LinearEasing) },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Route.Home> {
                HomeScreen(
                    onOpenControl = { target -> navigator.navigate(Route.Control(target)) },
                    onAddDevice = { navigator.navigate(Route.AddDevice) },
                    onOpenSettings = { navigator.navigate(Route.Settings) },
                    onOpenStripSettings = { navigator.navigate(Route.StripSettings(it)) },
                )
            }
            entry<Route.Control> { route ->
                ControlScreen(
                    target = route.target,
                    onBack = navigator::back,
                    onOpenMusic = { navigator.navigate(Route.Music(route.target)) },
                )
            }
            entry<Route.AddDevice> {
                AddDeviceScreen(onDone = navigator::back, onBack = navigator::back)
            }
            entry<Route.Settings> {
                SettingsScreen(onBack = navigator::back)
            }
            entry<Route.StripSettings> { route ->
                StripSettingsScreen(
                    address = route.address,
                    onBack = navigator::back,
                    onForgotten = { navigator.openFromLink(Route.Home) },
                    onCalibrateLight = { navigator.navigate(Route.CalibrateLight(route.address)) },
                )
            }
            entry<Route.Music> { route ->
                MusicScreen(
                    initialTarget = route.target,
                    onBack = navigator::back,
                    onCalibrateLight = { navigator.navigate(Route.CalibrateLight(it)) },
                )
            }
            entry<Route.CalibrateLight> { route ->
                CalibrateLightScreen(initialAddress = route.address, onBack = navigator::back)
            }
        },
    )
}

private const val DURATION_MS = 300
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun AnimatedContentTransitionScope<Scene<Route>>.forward(): ContentTransform =
    (slideInHorizontally(tween(DURATION_MS, easing = EmphasizedEasing)) { it / 4 } +
        fadeIn(tween(DURATION_MS, easing = EmphasizedEasing)))
        .togetherWith(
            slideOutHorizontally(tween(DURATION_MS, easing = EmphasizedEasing)) { -it / 10 } +
                fadeOut(tween(DURATION_MS / 2)),
        )

// Linear easing for the predictive gesture keeps the screen under the finger.
private fun AnimatedContentTransitionScope<Scene<Route>>.pop(easing: Easing): ContentTransform =
    ContentTransform(
        targetContentEnter = slideInHorizontally(tween(DURATION_MS, easing = easing)) { -it / 10 } +
            fadeIn(tween(DURATION_MS, easing = easing)),
        initialContentExit = slideOutHorizontally(tween(DURATION_MS, easing = easing)) { it } +
            fadeOut(tween(DURATION_MS, easing = easing), targetAlpha = 0.6f),
        // The leaving screen stays on top while it slides away.
        targetContentZIndex = -1f,
    )
