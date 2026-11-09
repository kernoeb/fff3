package com.kernoeb.fff3.data

import androidx.datastore.core.DataStore
import com.kernoeb.fff3.ble.queueAll
import com.kernoeb.fff3.music.musicRestoreCommands
import com.kernoeb.fff3.protocol.Command
import com.kernoeb.fff3.protocol.Rgb
import com.kernoeb.fff3.protocol.ScheduleAction
import com.kernoeb.fff3.ui.calibrate.restoreCommands
import java.time.DayOfWeek
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceStateStoreTest {

    /** A file that is read only once [opened] completes, like a slow cold start. */
    private class FakeFile(var saved: Map<String, LedState>) : DataStore<Map<String, LedState>> {
        val opened = CompletableDeferred<Unit>()
        var writes = 0
        override val data: Flow<Map<String, LedState>> = flow {
            opened.await()
            emit(saved)
        }
        override suspend fun updateData(transform: suspend (Map<String, LedState>) -> Map<String, LedState>): Map<String, LedState> {
            saved = transform(saved)
            writes++
            return saved
        }
    }

    private val pink = Rgb(0xFF3399)
    private val blue = Rgb(0x0000FF)
    private val clock = Command.ClockSync(21, 4, 9, DayOfWeek.SUNDAY)

    private fun TestScope.store(file: FakeFile) = PersistentDeviceStateStore(file, backgroundScope)

    // advanceUntilIdle does not run backgroundScope, where the store saves.
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun clockSyncBeforeLoadKeepsSavedColour() = runTest {
        val file = FakeFile(mapOf("A" to LedState(power = true, color = pink)))
        val store = store(file)
        runCurrent()
        store.record("A", clock)
        file.opened.complete(Unit)
        settle()
        assertEquals(LedState(power = true, color = pink), store.states.value["A"])
        assertEquals(LedState(power = true, color = pink), file.saved["A"])
    }

    @Test
    fun commandBeforeLoadOnlyReplacesTheFieldsItSets() = runTest {
        val file = FakeFile(mapOf("A" to LedState(power = true, color = pink, brightness = 40)))
        val store = store(file)
        runCurrent()
        store.record("A", Command.Power(false))
        file.opened.complete(Unit)
        settle()
        assertEquals(LedState(power = false, color = pink, brightness = 40), store.states.value["A"])
        assertEquals(LedState(power = false, color = pink, brightness = 40), file.saved["A"])
    }

    @Test
    fun forgetBeforeLoadStillForgets() = runTest {
        val file = FakeFile(mapOf("A" to LedState(color = pink)))
        val store = store(file)
        runCurrent()
        store.forget("A")
        file.opened.complete(Unit)
        settle()
        assertNull(store.states.value["A"])
    }

    @Test
    fun commandsThatChangeNothingCreateNoEntry() = runTest {
        val file = FakeFile(emptyMap())
        file.opened.complete(Unit)
        val store = store(file)
        settle()
        store.record("A", clock)
        store.record("A", Command.MicSensitivity(50))
        store.record("A", Command.Schedule(ScheduleAction.TurnOn, 7, 0, emptySet(), true))
        settle()
        assertFalse("A" in store.states.value)
        assertNull(store.lastControlled.value)
        assertEquals(0, file.writes)
    }

    @Test
    fun changeIsSavedWithoutWaitingForADebounce() = runTest {
        val file = FakeFile(emptyMap())
        file.opened.complete(Unit)
        val store = store(file)
        settle()
        store.record("A", Command.Color(pink))
        runCurrent()
        assertEquals(pink, file.saved["A"]?.color)
    }

    @Test
    fun dragIsSavedAtItsLastValue() = runTest {
        val file = FakeFile(emptyMap())
        file.opened.complete(Unit)
        val store = store(file)
        settle()
        repeat(50) { store.record("A", Command.Brightness(it)) }
        settle()
        assertEquals(49, file.saved["A"]?.brightness)
        assertTrue("a drag must not write on every frame", file.writes < 5)
    }

    @Test
    fun transientSendsDoNotChangeTheSavedState() = runTest {
        val file = FakeFile(mapOf("A" to LedState(power = true, color = pink)))
        file.opened.complete(Unit)
        val store = store(file)
        settle()
        store.queueAll("A", listOf(Command.Color(Rgb(0x010101)), Command.Color(blue)), save = false) { true }
        settle()
        assertEquals(pink, store.states.value["A"]?.color)
        assertEquals(pink, file.saved["A"]?.color)

        store.queueAll("A", listOf(Command.Color(blue)), save = true) { true }
        settle()
        assertEquals(blue, file.saved["A"]?.color)
    }

    @Test
    fun musicRestoreRecordsTheStateFromBefore() = runTest {
        val before = LedState(power = false, color = pink)
        // The user changed the colour from a widget during music; the restore puts the old one back.
        val file = FakeFile(mapOf("A" to LedState(power = true, color = blue)))
        file.opened.complete(Unit)
        val store = store(file)
        settle()
        store.queueAll("A", listOf(Command.Color(Rgb(0x000000))), save = false) { true }
        store.queueAll("A", musicRestoreCommands(before), save = true) { true }
        settle()
        assertEquals(before, file.saved["A"])

        store.queueAll("B", musicRestoreCommands(null), save = true) { true }
        assertEquals(true, store.states.value["B"]?.power)
    }

    @Test
    fun calibrationRestoreRecordsTheStateFromBefore() = runTest {
        val before = LedState(power = true, brightness = 60, effectId = 3, speed = 50)
        val file = FakeFile(emptyMap())
        file.opened.complete(Unit)
        val store = store(file)
        settle()
        store.queueAll("A", restoreCommands(before), save = true) { true }
        settle()
        assertEquals(LedState(power = null, brightness = 60, effectId = 3, speed = 50), file.saved["A"])
    }
}
