package com.kernoeb.fff3.protocol

/**
 * A controller family, picked from the name the strip advertises.
 * Each family knows its protocol, catalogues and which commands its hardware accepts.
 */
enum class LedFamily(
    val label: String,
    val protocol: LedProtocol,
    val effects: List<EffectInfo>,
    val scenes: List<EffectInfo>,
    val micModes: List<String>,
    val hasWireOrder: Boolean,
    val hasPixelCount: Boolean,
) {
    DOM(
        label = "ELK-BLEDOM",
        protocol = DomProtocol,
        effects = DomCatalog.effects,
        scenes = emptyList(),
        micModes = DomCatalog.micModes,
        hasWireOrder = true,
        hasPixelCount = false,
    ),

    /** Same bytes as DOM, but with no microphone modes. */
    DOB(
        label = "ELK-BLEDOB",
        protocol = DomProtocol,
        effects = DomCatalog.effects,
        scenes = emptyList(),
        micModes = emptyList(),
        hasWireOrder = true,
        hasPixelCount = false,
    ),

    MELK(
        label = "MELK",
        protocol = MelkProtocol,
        effects = MelkCatalog.effects,
        scenes = emptyList(),
        micModes = MelkCatalog.micModes,
        hasWireOrder = true,
        hasPixelCount = true,
    ),

    /** MELK-OC, MELK-OD and MELK-OF: MELK plus scenes. */
    MELK_SCENE(
        label = "MELK (scenes)",
        protocol = MelkProtocol,
        effects = MelkCatalog.effects,
        scenes = MelkCatalog.scenes,
        micModes = MelkCatalog.micModes,
        hasWireOrder = true,
        hasPixelCount = true,
    );

    val hasMic: Boolean get() = micModes.isNotEmpty()

    /** The frames for [command], or an empty list if this family cannot do it. */
    fun encode(command: Command): List<ByteArray> =
        if (accepts(command)) protocol.encode(command) else emptyList()

    fun supports(command: Command): Boolean = encode(command).isNotEmpty()

    private fun accepts(command: Command): Boolean = when (command) {
        is Command.MicEnabled, is Command.MicMode, is Command.MicSensitivity -> hasMic
        is Command.Effect -> effects.any { it.id == command.id }
        is Command.Scene -> scenes.any { it.id == command.id }
        is Command.WireOrder -> hasWireOrder
        is Command.PixelCount -> hasPixelCount
        is Command.Power, is Command.Color, is Command.Brightness, is Command.Speed,
        is Command.ClockSync, is Command.Schedule -> true
    }

    companion object {

        /**
         * Families are tried in a fixed order. Matching is "contains", case-sensitive.
         * Returns null for names of families fff3 does not support (DMX, Triones, SP…) or no name.
         */
        fun detect(advertisedName: String?): LedFamily? {
            val name = advertisedName ?: return null
            return when {
                "ELK-BLEDOM" in name || "ELK-HR" in name || "CLK-BLE" in name -> DOM
                "MELK-OC" in name || "MELK-OD" in name || "MELK-OF" in name -> MELK_SCENE
                "MELK-O" in name -> MELK
                "ELK-BLEDOB" in name -> DOB
                "ELK-BLE" in name -> DOM
                else -> null
            }
        }

        /** Regex for names [detect] accepts. Used by the Companion Device Manager filter. */
        const val NAME_PATTERN: String = ".*(ELK-BLE|ELK-HR|CLK-BLE|MELK-O).*"
    }
}
