package com.kernoeb.fff3.ui.navigation

import com.kernoeb.fff3.ble.ControlTarget

/** Every screen of the app. Add a route here, then an `entry` in [AppNavigation]. */
sealed interface Route {

    data object Home : Route

    data class Control(val target: ControlTarget) : Route

    data object AddDevice : Route

    data object Settings : Route

    /** Rename, wire order, schedules and forget, for one strip. */
    data class StripSettings(val address: String) : Route
    /** Phone music mode. [target] null means every strip. */
    data class Music(val target: ControlTarget? = null) : Route

    /** A stable string form, used to save the back stack and in intents from shortcuts, tile and widget. */
    fun encode(): String = when (this) {
        Home -> "home"
        is Control -> "control/${target.encode()}"
        AddDevice -> "add-device"
        Settings -> "settings"
        is StripSettings -> "strip-settings/$address"
        is Music -> target?.let { "music/${it.encode()}" } ?: "music"
    }

    companion object {
        fun decode(value: String?): Route? = when {
            value == null -> null
            value == "home" -> Home
            value == "add-device" -> AddDevice
            value == "settings" -> Settings
            value.startsWith("strip-settings/") ->
                value.removePrefix("strip-settings/").takeIf { it.isNotEmpty() }?.let(::StripSettings)
            value == "music" -> Music()
            value.startsWith("music/") -> ControlTarget.decode(value.removePrefix("music/"))?.let(::Music)
            value.startsWith("control/") -> ControlTarget.decode(value.removePrefix("control/"))?.let(::Control)
            else -> null
        }
    }
}
