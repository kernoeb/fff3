package com.kernoeb.fff3.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

/** The back stack. Survives rotation and process death through [Route.encode]. */
@Stable
class Navigator internal constructor(initial: List<Route>) {

    val backStack: SnapshotStateList<Route> = mutableStateListOf<Route>().apply { addAll(initial) }

    fun navigate(route: Route) {
        backStack.add(route)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Opens [route] on top of Home, dropping everything else. For links from shortcuts, tile and widget. */
    fun openFromLink(route: Route) {
        backStack.clear()
        backStack.add(Route.Home)
        if (route != Route.Home) backStack.add(route)
    }

    companion object {
        val Saver = listSaver<Navigator, String>(
            save = { navigator -> navigator.backStack.map(Route::encode) },
            restore = { saved -> Navigator(saved.mapNotNull(Route::decode).ifEmpty { listOf(Route.Home) }) },
        )
    }
}

@Composable
fun rememberNavigator(): Navigator = rememberSaveable(saver = Navigator.Saver) { Navigator(listOf(Route.Home)) }
