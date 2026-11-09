package com.kernoeb.fff3.ui.navigation

import com.kernoeb.fff3.ble.ControlTarget

/** Every screen of the app. Add a route here, then an `entry` in [AppNavigation]. */
sealed interface Route {

    data object Home : Route

    data class Control(val target: ControlTarget) : Route

    data object AddDevice : Route

    data object Settings : Route

    /** A stable string form, used to save the back stack and in intents from shortcuts, tile and widget. */
    fun encode(): String = when (this) {
        Home -> "home"
        is Control -> "control/${target.encode()}"
        AddDevice -> "add-device"
        Settings -> "settings"
    }

    companion object {
        fun decode(value: String?): Route? = when {
            value == null -> null
            value == "home" -> Home
            value == "add-device" -> AddDevice
            value == "settings" -> Settings
            value.startsWith("control/") -> ControlTarget.decode(value.removePrefix("control/"))?.let(::Control)
            else -> null
        }
    }
}
