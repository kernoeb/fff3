package com.kernoeb.fff3.ui.add

import com.kernoeb.fff3.data.KnownDevice

/** Quick picks in the name dialog, so naming a strip is one tap. */
val NAME_SUGGESTIONS = listOf("Desk", "Bed", "TV", "Shelf", "Kitchen", "Ceiling")

/**
 * A name for a new strip. Strips of one brand all advertise the same name ("ELK-BLEDOM"),
 * so we number them instead: "Strip 1", "Strip 2", taking the lowest number not in use.
 */
fun suggestStripName(existing: List<KnownDevice>): String {
    val used = existing.map { it.displayName.trim().lowercase() }.toSet()
    val n = generateSequence(1) { it + 1 }.first { "strip $it" !in used }
    return "Strip $n"
}

/** What adding [address] should do, given the strips already saved. */
sealed interface AddOutcome {
    data object New : AddOutcome
    data class AlreadyAdded(val name: String) : AddOutcome
}

fun addOutcome(address: String, existing: List<KnownDevice>): AddOutcome =
    existing.firstOrNull { it.address.equals(address, ignoreCase = true) }
        ?.let { AddOutcome.AlreadyAdded(it.displayName) }
        ?: AddOutcome.New
