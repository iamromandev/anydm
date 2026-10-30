package dev.anydm.desktop

import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrayMenuTest {
    private fun TestScope.model(
        source: FakeSource,
        window: WindowModel = WindowModel(MemoryPrefs(), trayAvailable = true),
        onQuit: () -> Unit = {},
    ) = AppModel(
        source,
        object : FirstRun {
            override fun isDone() = true

            override fun markDone() = Unit
        },
        backgroundScope + UnconfinedTestDispatcher(testScheduler),
        FakeFiles(),
        onQuit = onQuit,
        window = window,
    ).also { it.start() }

    @Test
    fun `the menu has the four entries and its labels follow the window`() {
        val shown = trayMenu(WindowState(visible = true), paused = false)
        assertEquals(listOf(TrayAction.TOGGLE_WINDOW, TrayAction.OPEN, TrayAction.TOGGLE_PAUSE, TrayAction.QUIT), shown.map { it.action })
        assertEquals("Hide anydm", shown.first { it.action == TrayAction.TOGGLE_WINDOW }.label)
        val hidden = trayMenu(WindowState(visible = false), paused = false)
        assertEquals("Show anydm", hidden.first { it.action == TrayAction.TOGGLE_WINDOW }.label)
    }

    @Test
    fun `an entry for the last finished download is there only when there is one`() {
        val without = trayMenu(WindowState(), paused = false)
        assertFalse(without.any { it.action == TrayAction.OPEN_LAST })
        val with = trayMenu(WindowState(), paused = false, hasLast = true)
        assertEquals("Show last finished", with.first { it.action == TrayAction.OPEN_LAST }.label)
    }

    @Test
    fun `the pause entry follows whether everything is paused`() {
        assertEquals("Pause all", trayMenu(WindowState(), paused = false).first { it.action == TrayAction.TOGGLE_PAUSE }.label)
        assertEquals("Resume all", trayMenu(WindowState(), paused = true).first { it.action == TrayAction.TOGGLE_PAUSE }.label)
        assertTrue(trayMenu(WindowState(), paused = true).all { it.enabled })
    }

    @Test
    fun `the tooltip says what is going on`() {
        assertEquals("anydm", trayTooltip(UiState()))
        val busy = UiState(counts = Counts(all = 3, byGroup = mapOf(StatusGroup.ACTIVE to 2)), speed = 2L * 1024 * 1024)
        assertEquals("anydm · 2 downloading · 2.0 MB/s", trayTooltip(busy))
        assertTrue(trayTooltip(UiState(globallyPaused = true)).contains("paused"))
    }

    @Test
    fun `the window entry shows or hides it`() =
        runTest {
            val window = WindowModel(MemoryPrefs(), trayAvailable = true)
            val model = model(FakeSource(), window)
            model.trayAction(TrayAction.TOGGLE_WINDOW)
            assertFalse(window.state.value.visible)
            model.trayAction(TrayAction.TOGGLE_WINDOW)
            assertTrue(window.state.value.visible)
        }

    @Test
    fun `open brings the window forward on the whole list`() =
        runTest {
            val window = WindowModel(MemoryPrefs(), trayAvailable = true).also { it.hide() }
            val model = model(FakeSource(), window)
            model.navigate(Route.Settings)
            model.trayAction(TrayAction.OPEN)
            assertTrue(window.state.value.visible)
            assertEquals(Route.Downloads(Filter.All), model.state.value.route)
        }

    @Test
    fun `pause all and resume all go to the engine`() =
        runTest {
            val source = FakeSource()
            source.downloads.value = listOf(snapshot(1, DOWNLOADING), snapshot(2, QUEUED))
            val model = model(source)
            model.trayAction(TrayAction.TOGGLE_PAUSE)
            assertEquals(listOf("pauseAll"), source.calls)
        }

    @Test
    fun `quit quits`() =
        runTest {
            var quits = 0
            val model = model(FakeSource(), onQuit = { quits++ })
            model.trayAction(TrayAction.QUIT)
            assertEquals(1, quits)
        }
}
