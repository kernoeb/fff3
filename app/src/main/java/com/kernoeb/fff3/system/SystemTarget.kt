package com.kernoeb.fff3.system

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.AppData
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.ui.home.findAllStripsGroup
import com.kernoeb.fff3.ui.home.previewColour
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

    /** False when fff3 never sent power to one of these strips. A tap then sends On, as [isOn] is false. */
    fun powerKnown(states: Map<String, LedState>): Boolean = addresses.all { states[it]?.power != null }

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
            val allGroup = findAllStripsGroup(all, data.groups)
            val targets = when {
                allGroup != null -> listOf(ControlTarget.Group(allGroup.id))
                all.size == 1 -> listOf(ControlTarget.Device(all.single()))
                else -> all.map { ControlTarget.Device(it) }
            }
            return SystemTarget(targets, all, null)
        }
    }
}

/** Known strips that [target] covers, every strip when null. A missing device or group gives none. */
fun AppData.addressesOf(target: ControlTarget?): List<String> {
    val known = devices.map { it.address }
    return when (target) {
        null -> known
        is ControlTarget.Device -> listOf(target.address).filter { it in known }
        is ControlTarget.Group -> groups.firstOrNull { it.id == target.id }?.memberAddresses.orEmpty().filter { it in known }
    }
}

/** The colour a favourite shows, if it sets one. */
val Favourite.color: Rgb? get() = previewColour()

/** A colour sent to a strip that is off shows nothing, so turn it on unless the favourite sets power. */
fun Favourite.commandsFromOutside(): List<Command> =
    if (commands.any { it is Command.Power }) commands else listOf(Command.Power(true)) + commands
