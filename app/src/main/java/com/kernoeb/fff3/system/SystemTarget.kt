package com.kernoeb.fff3.system

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.AppData
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.navigation.Route

/**
 * What the tile, widget and shortcuts act on: a chosen device or group, or else every strip.
 * [name] is null when it means every strip.
 */
data class SystemTarget(
    val targets: List<ControlTarget>,
    val addresses: List<String>,
    val name: String?,
) {
    val isEmpty: Boolean get() = addresses.isEmpty()

    /** The screen a long press or a tap on the widget label opens. */
    val route: Route get() = targets.singleOrNull()?.let { Route.Control(it) } ?: Route.Home

    fun isOn(states: Map<String, LedState>): Boolean = addresses.any { states[it]?.power == true }

    fun color(states: Map<String, LedState>): Rgb? = addresses.firstNotNullOfOrNull { states[it]?.color }

    companion object {
        /** [chosen] if it still exists, else every strip. */
        fun resolve(data: AppData, chosen: ControlTarget?): SystemTarget {
            val all = data.devices.map { it.address }
            when (chosen) {
                is ControlTarget.Device -> data.devices.firstOrNull { it.address == chosen.address }?.let {
                    return SystemTarget(listOf(chosen), listOf(it.address), it.displayName)
                }
                is ControlTarget.Group -> data.groups.firstOrNull { it.id == chosen.id }?.let { group ->
                    return SystemTarget(listOf(chosen), group.memberAddresses.filter { it in all }, group.name)
                }
                null -> Unit
            }
            // A group of every strip (the usual "All") gives one Control screen instead of Home.
            val allGroup = data.groups.firstOrNull { all.isNotEmpty() && it.memberAddresses.toSet() == all.toSet() }
            val targets = when {
                allGroup != null -> listOf(ControlTarget.Group(allGroup.id))
                all.size == 1 -> listOf(ControlTarget.Device(all.single()))
                else -> all.map { ControlTarget.Device(it) }
            }
            return SystemTarget(targets, all, null)
        }
    }
}

/** The colour a favourite shows, if it sets one. */
val Favourite.color: Rgb? get() = commands.firstNotNullOfOrNull { (it as? Command.Color)?.rgb }

/** A favourite tapped outside the app should light the strips even when they are off. */
fun Favourite.commandsFromOutside(): List<Command> =
    if (commands.any { it is Command.Power }) commands else listOf(Command.Power(true)) + commands
