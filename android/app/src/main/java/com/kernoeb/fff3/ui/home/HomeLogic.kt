package com.kernoeb.fff3.ui.home

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.data.DeviceGroup
import com.kernoeb.fff3.data.Favourite
import com.kernoeb.fff3.data.KnownDevice
import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.data.shared
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

/**
 * How a list of targets shows "every strip". A group that holds exactly every strip replaces the
 * built-in "All strips" entry, so the same strips never show twice.
 */
data class WholeSet(
    /** The user's groups whose members are exactly every saved strip, in saved order. */
    val groups: List<DeviceGroup>,
    /** True when the built-in "All strips" entry should show: 2 or more strips and no group above. */
    val showBuiltIn: Boolean,
    /** What a list selects when a saved choice means every strip but the built-in entry is hidden. */
    val standIn: ControlTarget?,
) {
    val groupIds: Set<String> = groups.mapTo(HashSet()) { it.id }
}

fun wholeSetOf(deviceAddresses: List<String>, groups: List<DeviceGroup>): WholeSet {
    val all = deviceAddresses.toSet()
    val covering = if (all.isEmpty()) emptyList() else groups.filter { it.memberAddresses.toSet() == all }
    val standIn = covering.firstOrNull()?.let { ControlTarget.Group(it.id) }
        ?: all.singleOrNull()?.let { ControlTarget.Device(it) }
    return WholeSet(covering, showBuiltIn = covering.isEmpty() && all.size >= 2, standIn = standIn)
}

/** The first group whose members are exactly [deviceAddresses], or null. */
fun findAllStripsGroup(deviceAddresses: List<String>, groups: List<DeviceGroup>): DeviceGroup? =
    wholeSetOf(deviceAddresses, groups).groups.firstOrNull()

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
fun defaultHomeTarget(devices: List<KnownDevice>, groups: List<DeviceGroup>): HomeTarget? =
    if (devices.isEmpty()) null
    else wholeSetOf(devices.map { it.address }, groups).standIn?.let(HomeTarget::One) ?: HomeTarget.AllStrips

/** Summary of several strips' last known state, for group and "All strips" cards. */
data class MemberSummary(
    val count: Int,
    val onCount: Int,
    val connectedCount: Int,
    /** Colours of the members that are on, or of all members when none is on. Empty when one is unknown. */
    val colours: List<Rgb>,
    /** Rounded mean brightness, or null if any member's brightness is unknown. */
    val brightness: Int?,
    /** True or false only when every member is known to be in that state. */
    val power: Boolean?,
    /** True when fff3 never sent power to at least one member. */
    val unknown: Boolean,
)

fun summarise(states: List<LedState>, connected: List<Boolean>): MemberSummary {
    val lit = states.filter { it.power == true }
    val colourSource = lit.ifEmpty { states }
    val unknown = states.any { it.power == null }
    val brightness = states.map { it.brightness }
    return MemberSummary(
        count = states.size,
        onCount = lit.size,
        connectedCount = connected.count { it },
        colours = if (unknown || colourSource.any { it.color == null }) emptyList()
        else colourSource.mapNotNull { it.color }.distinct(),
        brightness = if (brightness.isEmpty() || null in brightness) null
        else brightness.filterNotNull().let { (it.sum() + it.size / 2) / it.size },
        power = states.shared().power,
        unknown = unknown,
    )
}

/** True when fff3 never sent the power or the look (colour, effect or scene) of this strip. */
val LedState.unknown: Boolean get() = power == null || (color == null && effectId == null && sceneId == null)

/** The colour that best represents a favourite: its last plain colour, or null for effects and scenes. */
fun Favourite.previewColour(): Rgb? = commands.filterIsInstance<Command.Color>().lastOrNull()?.rgb

/** A copy with the item at [from] moved to [to]. Out-of-range indexes return the list unchanged. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val result = toMutableList()
    result.add(to, result.removeAt(from))
    return result
}
