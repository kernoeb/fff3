package com.kernoeb.fff3.system

import android.content.Context
import com.kernoeb.fff3.AppContainer
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the widget and the dynamic shortcuts in step with the stored data, for the life of the process. */
object SystemSync {

    // A colour drag records a state every frame; the widget only needs the settled one.
    private const val WIDGET_DEBOUNCE_MS = 300L

    /** Launcher and shortcut calls can fail on some devices, so failures are ignored, never thrown. */
    @OptIn(FlowPreview::class)
    fun start(context: Context, container: AppContainer) {
        val appContext = context.applicationContext
        val data = container.repository.snapshot.filterNotNull()
        container.appScope.launch {
            combine(data, container.deviceStates.states) { snapshot, states -> LedWidgets.model(snapshot, states) }
                .distinctUntilChanged()
                .debounce(WIDGET_DEBOUNCE_MS)
                .collect { runCatching { LedWidgets.push(appContext, it) } }
        }
        container.appScope.launch {
            data.map { it.favourites }
                .distinctUntilChanged()
                .collect { runCatching { AppShortcuts.update(appContext, it) } }
        }
    }
}
