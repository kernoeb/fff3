package com.kernoeb.fff3.music

import com.kernoeb.fff3.data.LedState
import com.kernoeb.fff3.protocol.Command

/**
 * Puts a strip back as it was before music started. Music sends without recording,
 * so these commands are recorded and are what the app shows and saves afterwards.
 */
fun musicRestoreCommands(state: LedState?): List<Command> = buildList {
    when {
        state?.effectId != null -> add(Command.Effect(state.effectId, state.speed))
        state?.sceneId != null -> add(Command.Scene(state.sceneId))
        state?.color != null -> add(Command.Color(state.color))
    }
    when (state?.power) {
        false -> add(Command.Power(false))
        // Music turned the strip on, so an unknown power is now known to be on.
        null -> add(Command.Power(true))
        true -> Unit
    }
}
