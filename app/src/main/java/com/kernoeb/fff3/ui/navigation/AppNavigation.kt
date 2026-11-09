package com.kernoeb.fff3.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.kernoeb.fff3.ui.add.AddDeviceScreen
import com.kernoeb.fff3.ui.control.ControlScreen
import com.kernoeb.fff3.ui.control.MusicPlaceholderScreen
import com.kernoeb.fff3.ui.home.HomeScreen
import com.kernoeb.fff3.ui.settings.SettingsScreen

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
                )
            }
            entry<Route.Control> { route ->
                ControlScreen(
                    target = route.target,
                    onBack = navigator::back,
                    onOpenMusic = { navigator.navigate(Route.Music) },
                )
            }
            entry<Route.AddDevice> {
                AddDeviceScreen(onDone = navigator::back, onBack = navigator::back)
            }
            entry<Route.Settings> {
                SettingsScreen(onBack = navigator::back)
            }
            entry<Route.Music> {
                MusicPlaceholderScreen(onBack = navigator::back)
            }
        },
    )
}
