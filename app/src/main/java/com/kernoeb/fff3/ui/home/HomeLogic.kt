package com.kernoeb.fff3.ui.home

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.DeviceGroup
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb

/** Where a favourite or an "All strips" command goes from Home. */
sealed interface HomeTarget {

    data object AllStrips : HomeTarget

    data class One(val target: ControlTarget) : HomeTarget

    fun encode(): String = when (this) {
        AllStrips -> ALL
        is One -> target.encode()
    }

    companion object {
        private const val ALL = "all"

        fun decode(value: String?): HomeTarget? =
            if (value == ALL) AllStrips else ControlTarget.decode(value)?.let(::One)
    }
}

/** Default name for the group that holds every strip. */
const val ALL_GROUP_NAME = "All"

/** The first group whose members are exactly [deviceAddresses], or null. */
fun findAllStripsGroup(deviceAddresses: List<String>, groups: List<DeviceGroup>): DeviceGroup? {
    if (deviceAddresses.isEmpty()) return null
    val all = deviceAddresses.toSet()
    return groups.firstOrNull { it.memberAddresses.toSet() == all }
}

/** Addresses of the known strips that [target] covers, in device order. */
fun addressesOf(target: HomeTarget, devices: List<KnownDevice>, groups: List<DeviceGroup>): List<String> =
    when (target) {
        HomeTarget.AllStrips -> devices.map { it.address }
        is HomeTarget.One -> when (val t = target.target) {
            is ControlTarget.Device -> devices.filter { it.address == t.address }.map { it.address }
            is ControlTarget.Group -> {
                val members = groups.firstOrNull { it.id == t.id }?.memberAddresses.orEmpty().toSet()
                devices.filter { it.address in members }.map { it.address }
            }
        }
    }

/**
 * Strips that should get [favourite] when applied to [target].
 * Effects and scenes have ids that mean different things per family, so those go to matching strips only.
 */
fun favouriteRecipients(
    favourite: Favourite,
    target: HomeTarget,
    devices: List<KnownDevice>,
    groups: List<DeviceGroup>,
): List<String> {
    val addresses = addressesOf(target, devices, groups).toSet()
    return devices
        .filter { it.address in addresses }
        .filter { favourite.family == null || it.family == favourite.family }
        .map { it.address }
}

/** The target to preselect for favourites: the "All" group, else every strip, else the only strip. */
fun defaultHomeTarget(devices: List<KnownDevice>, groups: List<DeviceGroup>): HomeTarget? = when {
    devices.isEmpty() -> null
    devices.size == 1 -> HomeTarget.One(ControlTarget.Device(devices.single().address))
    else -> findAllStripsGroup(devices.map { it.address }, groups)
        ?.let { HomeTarget.One(ControlTarget.Group(it.id)) }
        ?: HomeTarget.AllStrips
}

/** Summary of several strips' last known state, for group and "All strips" cards. */
data class MemberSummary(
    val count: Int,
    val onCount: Int,
    val connectedCount: Int,
    /** Known colours of the members that are on, or of all members when none is on. */
    val colours: List<Rgb>,
    /** Rounded mean of the known brightness values, or null if none is known. */
    val brightness: Int?,
) {
    /** True only when every member is known to be on. */
    val allOn: Boolean get() = count > 0 && onCount == count
}

fun summarise(states: List<LedState>, connected: List<Boolean>): MemberSummary {
    val lit = states.filter { it.power == true }
    val colourSource = lit.ifEmpty { states }
    val brightness = states.mapNotNull { it.brightness }
    return MemberSummary(
        count = states.size,
        onCount = lit.size,
        connectedCount = connected.count { it },
        colours = colourSource.mapNotNull { it.color }.distinct(),
        brightness = if (brightness.isEmpty()) null else (brightness.sum() + brightness.size / 2) / brightness.size,
    )
}

/** The colour that best represents a favourite: its last plain colour, or null for effects and scenes. */
fun Favourite.previewColour(): Rgb? = commands.filterIsInstance<Command.Color>().lastOrNull()?.rgb

/** A copy with the item at [from] moved to [to]. Out-of-range indexes return the list unchanged. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val result = toMutableList()
    result.add(to, result.removeAt(from))
    return result
}
