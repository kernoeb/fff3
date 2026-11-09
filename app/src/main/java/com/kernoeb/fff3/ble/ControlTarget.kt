package com.kernoeb.fff3.ble

/** What a command goes to: one strip, or every strip of a group. */
sealed interface ControlTarget {

    data class Device(val address: String) : ControlTarget

    data class Group(val id: String) : ControlTarget

    /** A stable string form, for routes, intents, shortcuts and widget settings. */
    fun encode(): String = when (this) {
        is Device -> "device:$address"
        is Group -> "group:$id"
    }

    companion object {
        fun decode(value: String?): ControlTarget? {
            val (kind, id) = value?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
            if (id.isEmpty()) return null
            return when (kind) {
                "device" -> Device(id)
                "group" -> Group(id)
                else -> null
            }
        }
    }
}
