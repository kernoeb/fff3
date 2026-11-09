package com.kernoeb.fff3.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kernoeb.fff3.data.DeviceStateStore
import com.kernoeb.fff3.data.LedRepository
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.navigation.AppNavigation
import com.kernoeb.fff3.ui.navigation.Route
import com.kernoeb.fff3.ui.navigation.rememberNavigator
import com.kernoeb.fff3.ui.theme.Fff3Theme
import com.kernoeb.fff3.ui.theme.toColor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** The root composable. [link] is a route to open, from an intent; it is cleared once handled. */
@Composable
fun Fff3App(link: Route?, onLinkHandled: () -> Unit) {
    val appViewModel = appViewModel { AppViewModel(it.deviceStates, it.repository) }
    val accent by appViewModel.accentSeed.collectAsStateWithLifecycle()
    val navigator = rememberNavigator()

    LaunchedEffect(link) {
        if (link != null) {
            navigator.openFromLink(link)
            onLinkHandled()
        }
    }

    Fff3Theme(seed = accent?.toColor()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppNavigation(navigator)
        }
    }
}

class AppViewModel(deviceStates: DeviceStateStore, repository: LedRepository) : ViewModel() {

    /**
     * Colour of the strip controlled last, or of any strip with a known colour.
     * Settles for a moment before changing, so a drag does not recolour the whole UI on every frame.
     */
    @OptIn(FlowPreview::class)
    val accentSeed: StateFlow<Rgb?> = combine(
        deviceStates.lastControlled,
        deviceStates.states,
        repository.settings,
    ) { last, states, settings ->
        if (!settings.accentFollowsStrip) return@combine null
        last?.let { states[it]?.color } ?: states.values.firstNotNullOfOrNull { it.color }
    }
        .debounce(ACCENT_SETTLE_MS)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val ACCENT_SETTLE_MS = 150L
    }
}
