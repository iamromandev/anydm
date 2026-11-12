package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MenuModelTest {
    private fun item(status: DownloadStatus) = populatedSample().downloads.first().copy(status = status)

    private fun model(ctx: CommandContext = CommandContext()) = menuModel(commandTable, ctx)

    private fun List<MenuGroup>.entry(id: CommandId) = flatMap { it.entries }.first { it.id == id }

    private val running = CommandContext(targets = listOf(item(DownloadStatus.Downloading)), visibleCount = 1)

    @Test
    fun groupsAreFileEditViewInOrder() {
        assertEquals(listOf("File", "Edit", "View"), model().map { it.title })
    }

    @Test
    fun theUnitShowsAsTwoRadiosAndTheToggleAndTogglePauseAreNotInTheMenu() {
        val ids = model().flatMap { it.entries }.map { it.id }
        assertTrue(CommandId.SetUnitMBps in ids && CommandId.SetUnitMbps in ids)
        assertFalse(CommandId.ToggleUnit in ids)
        assertFalse(CommandId.TogglePause in ids)
        assertEquals(CommandId.entries.toSet() - CommandId.ToggleUnit - CommandId.TogglePause, ids.toSet())
    }

    @Test
    fun onlyModShortcutsBecomeAccelerators() {
        val m = model()
        for (id in listOf(CommandId.Pause, CommandId.Resume, CommandId.Remove, CommandId.ClearSelection)) {
            assertNull(m.entry(id).accelerator, "$id")
        }
        assertEquals(Shortcut(androidx.compose.ui.input.key.Key.N, mod = true), m.entry(CommandId.NewDownload).accelerator)
        assertEquals(
            Shortcut(androidx.compose.ui.input.key.Key.D, mod = true, shift = true),
            m.entry(CommandId.ToggleTheme).accelerator,
        )
        val all = m.flatMap { it.entries }
        assertTrue(all.none { e -> e.accelerator != null && !e.accelerator.mod })
        assertTrue(all.filter { it.accelerator != null }.size >= 9)
    }

    @Test
    fun enabledStatesComeFromTheEnabledRule() {
        val none = model()
        assertFalse(none.entry(CommandId.Pause).enabled)
        assertFalse(none.entry(CommandId.Remove).enabled)
        assertTrue(none.entry(CommandId.NewDownload).enabled)
        val m = model(running)
        assertTrue(m.entry(CommandId.Pause).enabled)
        assertFalse(m.entry(CommandId.Resume).enabled)
        assertTrue(m.entry(CommandId.Remove).enabled)
        for (e in model(running.copy(detailOpen = true)).flatMap { it.entries }.filter { it.id == CommandId.SelectAll }) {
            assertFalse(e.enabled)
        }
    }

    @Test
    fun aModalDisablesEverythingButKeyboardShortcuts() {
        val entries = model(running.copy(modalOpen = true)).flatMap { it.entries }
        assertEquals(listOf(CommandId.Shortcuts), entries.filter { it.enabled }.map { it.id })
    }

    @Test
    fun filterAndUnitRadiosFollowTheContext() {
        val m = model(CommandContext(filter = Filter.Finished, unit = SpeedUnit.Mbps))
        assertEquals(false, m.entry(CommandId.FilterAll).checked)
        assertEquals(true, m.entry(CommandId.FilterFinished).checked)
        assertEquals(false, m.entry(CommandId.SetUnitMBps).checked)
        assertEquals(true, m.entry(CommandId.SetUnitMbps).checked)
        assertNull(m.entry(CommandId.ToggleTheme).checked)
        assertNull(m.entry(CommandId.NewDownload).checked)
    }

    @Test
    fun settingsReplacesTheLimitsItemInTheFileMenuWithItsAccelerator() {
        val file = model().first { it.title == "File" }.entries
        val e = file.first { it.id == CommandId.Settings }
        assertEquals("Settings…", e.label)
        assertEquals(Shortcut(androidx.compose.ui.input.key.Key.Comma, mod = true), e.accelerator)
        assertTrue(e.enabled)
        assertTrue(file.none { it.label.contains("limits") })
    }

    @Test
    fun withSettingsOpenTheListItemsAreDisabledAndTheOthersStay() {
        val m = model(running.copy(settingsOpen = true))
        for (id in listOf(CommandId.Pause, CommandId.Remove, CommandId.SelectAll)) assertFalse(m.entry(id).enabled, "$id")
        for (id in listOf(CommandId.Settings, CommandId.NewDownload, CommandId.ToggleTheme)) assertTrue(m.entry(id).enabled, "$id")
    }

    @Test
    fun labelsComeFromTheTableAndTheWindowMenuIsMacOnly() {
        assertEquals("Open .torrent…", model().entry(CommandId.OpenTorrent).label)
        assertTrue(hasWindowMenu(Platform.Mac))
        assertFalse(hasWindowMenu(Platform.Other))
    }
}
