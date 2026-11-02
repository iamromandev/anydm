package dev.anydm.desktop

import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.TransferSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class FooterModelTest {
    private fun footer(
        prefs: ViewPrefs = MemoryViewPrefs(),
        block: suspend TestScope.(FakeSource, FooterModel, ViewPrefs) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        block(source, FooterModel(source, prefs, backgroundScope), prefs)
    }

    @Test
    fun `the slow mode switch does not undo a limits change made while it was applying`() =
        footer { source, model, _ ->
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            source.gate = gate
            model.toggleSlowMode()
            // A Limits save lands while the toggle's own write is still in flight.
            val limits = launch { source.updateTransfer { it.copy(globalSpeedLimit = 5_000_000) } }
            gate.complete(Unit)
            limits.join()
            assertTrue(source.transfer.slowMode)
            assertEquals(5_000_000L, source.transfer.globalSpeedLimit)
        }

    @Test
    fun `free space is measured where downloads are saved`() =
        footer { source, model, _ ->
            source.transfer = TransferSettings(defaultSaveFolder = "/data/dl")
            source.free["/data/dl"] = 78_500_000_000
            model.refresh()
            assertEquals(78_500_000_000, model.state.value.freeSpace)
        }

    @Test
    fun `free space is unknown when it can't be measured`() =
        footer { source, model, _ ->
            source.transfer = TransferSettings(defaultSaveFolder = "/nowhere")
            model.refresh()
            assertNull(model.state.value.freeSpace)
        }

    @Test
    fun `free space is read again every half minute`() =
        footer { source, model, _ ->
            source.transfer = TransferSettings(defaultSaveFolder = "/data/dl")
            source.free["/data/dl"] = 100
            model.refresh()
            source.free["/data/dl"] = 50
            advanceTimeBy(FREE_SPACE_POLL_MS + 1)
            assertEquals(50, model.state.value.freeSpace)
        }

    @Test
    fun `slow mode follows the engine and its switch flips it`() =
        footer { source, model, _ ->
            source.transfer = TransferSettings(slowMode = false, slowModeLimit = 2_000_000)
            model.refresh()
            assertFalse(model.state.value.slowMode)
            assertEquals(2_000_000, model.state.value.slowModeLimit)
            model.toggleSlowMode()
            assertTrue(source.transfer.slowMode)
            assertTrue(model.state.value.slowMode)
            model.toggleSlowMode()
            assertFalse(source.transfer.slowMode)
        }

    @Test
    fun `a refused slow mode change leaves the switch where it was and says why`() =
        footer { source, model, _ ->
            source.settingsResult = CommandResult.Invalid("not now")
            model.refresh()
            model.toggleSlowMode()
            assertFalse(model.state.value.slowMode)
            assertEquals("Slow mode could not be changed: not now", model.state.value.message)
        }

    @Test
    fun `a stopped engine does not break the footer`() =
        footer { source, model, _ ->
            source.refuseAll = true
            model.refresh()
            model.toggleSlowMode()
            assertFalse(model.state.value.slowMode)
        }

    @Test
    fun `the speed unit switches and is remembered`() =
        footer { _, model, prefs ->
            assertEquals(SpeedUnit.BYTES, model.state.value.unit)
            model.setUnit(SpeedUnit.BITS)
            assertEquals(SpeedUnit.BITS, model.state.value.unit)
            assertEquals(SpeedUnit.BITS, prefs.speedUnit)
        }

    @Test
    fun `the theme toggles between dark and light and is remembered`() =
        footer { _, model, prefs ->
            assertEquals(ThemeChoice.DARK, model.state.value.theme)
            model.toggleTheme()
            assertEquals(ThemeChoice.LIGHT, model.state.value.theme)
            assertEquals(ThemeChoice.LIGHT, prefs.theme)
            model.toggleTheme()
            assertEquals(ThemeChoice.DARK, prefs.theme)
        }

    @Test
    fun `the model starts from what was remembered`() =
        footer(MemoryViewPrefs(ThemeChoice.LIGHT, SpeedUnit.BITS)) { _, model, _ ->
            assertEquals(ThemeChoice.LIGHT, model.state.value.theme)
            assertEquals(SpeedUnit.BITS, model.state.value.unit)
        }
}
