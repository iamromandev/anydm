package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandsTest {
    private fun item(
        id: String,
        status: DownloadStatus,
    ) = DownloadItem(id, "f$id.zip", 100L, 10L, 0L, status)

    private val downloading = item("d", DownloadStatus.Downloading)
    private val queued = item("q", DownloadStatus.Queued)
    private val paused = item("p", DownloadStatus.Paused)
    private val finished = item("f", DownloadStatus.Finished)

    private fun on(
        id: CommandId,
        ctx: CommandContext,
    ) = enabled(id, ctx)

    private val fileIds = listOf(CommandId.NewDownload, CommandId.OpenTorrent, CommandId.Settings)
    private val listIds =
        listOf(CommandId.SelectAll, CommandId.ClearSelection, CommandId.Pause, CommandId.Resume, CommandId.TogglePause, CommandId.Remove)
    private val viewIds =
        listOf(
            CommandId.FilterAll,
            CommandId.FilterDownloading,
            CommandId.FilterFinished,
            CommandId.ToggleTheme,
            CommandId.ToggleUnit,
            CommandId.SetUnitMBps,
            CommandId.SetUnitMbps,
            CommandId.Shortcuts,
        )

    private val busy =
        CommandContext(
            targets = listOf(downloading, paused),
            visibleCount = 2,
            hasSelection = true,
        )

    // Table

    @Test
    fun theTableHasExactlyOneEntryPerCommandId() {
        assertEquals(CommandId.entries.toSet(), commandTable.map { it.id }.toSet())
        assertEquals(CommandId.entries.size, commandTable.size)
    }

    @Test
    fun labelsAndGroupsFollowTheSpec() {
        fun label(id: CommandId) = commandTable.first { it.id == id }.let { it.group to it.label }
        assertEquals(Group.File to "New download", label(CommandId.NewDownload))
        assertEquals(Group.File to "Open .torrent…", label(CommandId.OpenTorrent))
        assertEquals(Group.File to "Settings…", label(CommandId.Settings))
        assertEquals(Group.Edit to "Select all", label(CommandId.SelectAll))
        assertEquals(Group.Edit to "Clear selection", label(CommandId.ClearSelection))
        assertEquals(Group.Edit to "Pause", label(CommandId.Pause))
        assertEquals(Group.Edit to "Resume", label(CommandId.Resume))
        assertEquals(Group.Edit to "Remove…", label(CommandId.Remove))
        assertEquals(Group.View to "All downloads", label(CommandId.FilterAll))
        assertEquals(Group.View to "Downloading", label(CommandId.FilterDownloading))
        assertEquals(Group.View to "Finished", label(CommandId.FilterFinished))
        assertEquals(Group.View to "Toggle theme", label(CommandId.ToggleTheme))
        assertEquals(Group.View to "Switch speed unit", label(CommandId.ToggleUnit))
        assertEquals(Group.View to "Keyboard shortcuts", label(CommandId.Shortcuts))
    }

    @Test
    fun everyShortcutIsUniqueExceptTheSpaceKeyThatPauseResumeAndToggleShare() {
        val space = setOf(CommandId.Pause, CommandId.Resume, CommandId.TogglePause)
        val others = commandTable.filter { it.id !in space && it.shortcut != null }
        assertEquals(others.size, others.map { it.shortcut }.toSet().size)
        assertTrue(space.all { id -> commandTable.first { it.id == id }.shortcut?.key == Key.Spacebar })
        assertTrue(others.none { it.shortcut?.key == Key.Spacebar })
    }

    @Test
    fun theToggleCommandIsHiddenFromTheMenuAndTheOverlay() {
        val toggle = commandTable.first { it.id == CommandId.TogglePause }
        assertFalse(toggle.inMenu)
        assertFalse(toggle.inOverlay)
    }

    @Test
    fun theUnitIsTwoMenuRadiosInsteadOfTheToggleWhichStaysInTheOverlay() {
        fun cmd(id: CommandId) = commandTable.first { it.id == id }
        assertFalse(cmd(CommandId.ToggleUnit).inMenu)
        assertTrue(cmd(CommandId.ToggleUnit).inOverlay)
        for (id in listOf(CommandId.SetUnitMBps, CommandId.SetUnitMbps)) {
            assertTrue(cmd(id).inMenu)
            assertFalse(cmd(id).inOverlay)
            assertNull(cmd(id).shortcut)
        }
        assertEquals("MB/s", cmd(CommandId.SetUnitMBps).label)
        assertEquals("Mbps", cmd(CommandId.SetUnitMbps).label)
    }

    @Test
    fun theOverlayListsEveryMenuCommandByGroupWithItsPlatformLabel() {
        val mac = overlayEntries(Platform.Mac)
        val other = overlayEntries(Platform.Other)
        val visible = commandTable.filter { it.inOverlay }
        assertEquals(visible.map { it.label }, mac.map { it.label })
        assertEquals(visible.map { it.group }, other.map { it.group })
        assertEquals("⌘N", mac.first { it.label == "New download" }.shortcut)
        assertEquals("Ctrl+N", other.first { it.label == "New download" }.shortcut)
        assertEquals("Space", mac.first { it.label == "Pause" }.shortcut)
        assertEquals("Space", other.first { it.label == "Resume" }.shortcut)
        assertEquals("⌫", mac.first { it.label == "Remove…" }.shortcut)
        assertEquals("Delete", other.first { it.label == "Remove…" }.shortcut)
        assertEquals("Esc", other.first { it.label == "Clear selection" }.shortcut)
    }

    // Labels and key matching

    @Test
    fun macLabelsUseSymbols() {
        fun l(c: CommandId) = shortcutLabel(commandTable.first { it.id == c }.shortcut!!, Platform.Mac)
        assertEquals("⌘N", l(CommandId.NewDownload))
        assertEquals("⌘,", l(CommandId.Settings))
        assertEquals("⌘1", l(CommandId.FilterAll))
        assertEquals("⇧⌘D", l(CommandId.ToggleTheme))
        assertEquals("⇧⌘U", l(CommandId.ToggleUnit))
        assertEquals("⌘/", l(CommandId.Shortcuts))
        assertEquals("⌫", l(CommandId.Remove))
        assertEquals("Space", l(CommandId.TogglePause))
    }

    @Test
    fun otherLabelsUseWords() {
        fun l(c: CommandId) = shortcutLabel(commandTable.first { it.id == c }.shortcut!!, Platform.Other)
        assertEquals("Ctrl+N", l(CommandId.NewDownload))
        assertEquals("Ctrl+,", l(CommandId.Settings))
        assertEquals("Ctrl+Shift+D", l(CommandId.ToggleTheme))
        assertEquals("Ctrl+Shift+U", l(CommandId.ToggleUnit))
        assertEquals("Ctrl+/", l(CommandId.Shortcuts))
        assertEquals("Delete", l(CommandId.Remove))
        assertEquals("Esc", l(CommandId.ClearSelection))
    }

    @Test
    fun macMatchesCommandNotControl() {
        assertEquals(CommandId.NewDownload, matchKey(Key.N, meta = true, ctrl = false, shift = false, platform = Platform.Mac))
        assertNull(matchKey(Key.N, meta = false, ctrl = true, shift = false, platform = Platform.Mac))
        assertNull(matchKey(Key.N, meta = false, ctrl = false, shift = false, platform = Platform.Mac))
    }

    @Test
    fun otherMatchesControlNotMeta() {
        assertEquals(CommandId.NewDownload, matchKey(Key.N, meta = false, ctrl = true, shift = false, platform = Platform.Other))
        assertNull(matchKey(Key.N, meta = true, ctrl = false, shift = false, platform = Platform.Other))
    }

    @Test
    fun everyModShortcutMatchesItsOwnCommandAndShiftIsExact() {
        for (c in commandTable.filter { it.shortcut?.mod == true }) {
            val s = c.shortcut!!
            assertEquals(c.id, matchKey(s.key, false, true, s.shift, Platform.Other), c.label)
            assertNull(matchKey(s.key, false, true, !s.shift, Platform.Other), c.label)
        }
        assertEquals(CommandId.ToggleTheme, matchKey(Key.D, false, true, true, Platform.Other))
        assertNull(matchKey(Key.D, false, true, false, Platform.Other))
        assertEquals(CommandId.SelectAll, matchKey(Key.A, true, false, false, Platform.Mac))
        assertEquals(CommandId.FilterFinished, matchKey(Key.Three, true, false, false, Platform.Mac))
        assertEquals(CommandId.Shortcuts, matchKey(Key.Slash, true, false, false, Platform.Mac))
        assertEquals(CommandId.Settings, matchKey(Key.Comma, true, false, false, Platform.Mac))
    }

    @Test
    fun bareKeysMapToTheirCommands() {
        assertEquals(CommandId.TogglePause, matchBareKey(Key.Spacebar))
        assertEquals(CommandId.Remove, matchBareKey(Key.Delete))
        assertEquals(CommandId.Remove, matchBareKey(Key.Backspace))
        assertEquals(CommandId.ClearSelection, matchBareKey(Key.Escape))
        assertNull(matchBareKey(Key.N))
    }

    // Space

    @Test
    fun spacePausesWhenAnyTargetIsDownloadingOrQueued() {
        assertEquals(CommandId.Pause, spaceAction(listOf(downloading)))
        assertEquals(CommandId.Pause, spaceAction(listOf(queued)))
        assertEquals(CommandId.Pause, spaceAction(listOf(paused, downloading)))
        assertEquals(CommandId.Pause, spaceAction(listOf(paused, queued, finished)))
    }

    @Test
    fun spaceResumesWhenTargetsArePausedOnly() {
        assertEquals(CommandId.Resume, spaceAction(listOf(paused)))
        assertEquals(CommandId.Resume, spaceAction(listOf(paused, finished)))
    }

    @Test
    fun spaceDoesNothingForFinishedOrNoTargets() {
        assertNull(spaceAction(emptyList()))
        assertNull(spaceAction(listOf(finished)))
    }

    // Enabled rules

    @Test
    fun withAModalOpenOnlyShortcutsIsEnabled() {
        val ctx = busy.copy(modalOpen = true)
        for (id in CommandId.entries) assertEquals(id == CommandId.Shortcuts, on(id, ctx), id.name)
    }

    @Test
    fun withAMenuOpenOnlyFileCommandsAreEnabled() {
        val ctx = busy.copy(menuOpen = true)
        for (id in CommandId.entries) assertEquals(id in fileIds, on(id, ctx), id.name)
    }

    @Test
    fun withNothingOpenFileAndViewCommandsAreAlwaysEnabled() {
        for (id in fileIds + viewIds) assertTrue(on(id, CommandContext()), id.name)
        for (id in fileIds + viewIds) assertTrue(on(id, busy.copy(detailOpen = true)), id.name)
    }

    @Test
    fun withSettingsOpenListCommandsAreDisabledAndFileAndViewCommandsStayEnabled() {
        val ctx = busy.copy(settingsOpen = true)
        for (id in listIds) assertFalse(on(id, ctx), id.name)
        for (id in fileIds + viewIds) assertTrue(on(id, ctx), id.name)
    }

    @Test
    fun settingsKeepsTheModalAndEditingRules() {
        assertFalse(on(CommandId.Settings, CommandContext(editing = true, settingsOpen = true)))
        assertFalse(on(CommandId.ToggleTheme, CommandContext(modalOpen = true, settingsOpen = true)))
    }

    @Test
    fun listCommandsNeedTheirTargets() {
        val none = CommandContext()
        for (id in listIds) assertFalse(on(id, none), id.name)
        assertTrue(on(CommandId.SelectAll, none.copy(visibleCount = 1)))
        assertFalse(on(CommandId.ClearSelection, none.copy(visibleCount = 1)))
        assertTrue(on(CommandId.ClearSelection, none.copy(hasSelection = true)))
        assertTrue(on(CommandId.Remove, none.copy(targets = listOf(finished))))
        assertFalse(on(CommandId.Pause, none.copy(targets = listOf(finished, paused))))
        assertFalse(on(CommandId.Resume, none.copy(targets = listOf(downloading, queued))))
        assertTrue(on(CommandId.Pause, none.copy(targets = listOf(queued))))
        assertTrue(on(CommandId.Resume, none.copy(targets = listOf(paused))))
        assertFalse(on(CommandId.TogglePause, none.copy(targets = listOf(finished))))
        assertTrue(on(CommandId.TogglePause, none.copy(targets = listOf(paused))))
    }

    @Test
    fun inTheDetailViewOnlyToggleAndRemoveActOnTheOpenItem() {
        val ctx = CommandContext(detailOpen = true, targets = listOf(downloading), visibleCount = 3, hasSelection = true)
        assertTrue(on(CommandId.TogglePause, ctx))
        assertTrue(on(CommandId.Remove, ctx))
        for (id in listOf(CommandId.SelectAll, CommandId.ClearSelection, CommandId.Pause, CommandId.Resume)) {
            assertFalse(on(id, ctx), id.name)
        }
    }

    // Checked

    @Test
    fun checkedFollowsTheFilterAndUnit() {
        val ctx = CommandContext(filter = Filter.Finished, unit = SpeedUnit.Mbps)
        assertEquals(false, checked(CommandId.FilterAll, ctx))
        assertEquals(false, checked(CommandId.FilterDownloading, ctx))
        assertEquals(true, checked(CommandId.FilterFinished, ctx))
        assertNull(checked(CommandId.ToggleUnit, ctx))
        assertEquals(false, checked(CommandId.SetUnitMBps, ctx))
        assertEquals(true, checked(CommandId.SetUnitMbps, ctx))
        assertEquals(true, checked(CommandId.SetUnitMBps, ctx.copy(unit = SpeedUnit.MBps)))
        assertEquals(true, checked(CommandId.FilterAll, ctx.copy(filter = Filter.All)))
        assertNull(checked(CommandId.NewDownload, ctx))
        assertNull(checked(CommandId.ToggleTheme, ctx))
    }

    // Bus

    private class Clock(
        var t: Long = 1_000,
    ) {
        fun now() = t
    }

    private fun bus(
        clock: Clock,
        ran: MutableList<CommandId>,
        ctx: CommandContext = CommandContext(),
    ) = CommandBus(clock::now).also {
        it.context = ctx
        it.handler = { id -> ran.add(id) }
    }

    @Test
    fun aSecondRunOfTheSameIdWithin150msIsDropped() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran)
        assertTrue(b.run(CommandId.NewDownload))
        clock.t += 149
        assertFalse(b.run(CommandId.NewDownload))
        assertEquals(listOf(CommandId.NewDownload), ran)
    }

    @Test
    fun aRunAfter150msGoesThrough() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran)
        b.run(CommandId.NewDownload)
        clock.t += 150
        assertTrue(b.run(CommandId.NewDownload))
        assertEquals(2, ran.size)
    }

    @Test
    fun aDifferentIdIsNotDeduped() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran)
        b.run(CommandId.NewDownload)
        assertTrue(b.run(CommandId.Settings))
        assertEquals(listOf(CommandId.NewDownload, CommandId.Settings), ran)
    }

    @Test
    fun aDisabledCommandDoesNotRunAndDoesNotStartTheWindow() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran, CommandContext(modalOpen = true))
        assertFalse(b.run(CommandId.NewDownload))
        b.context = CommandContext()
        assertTrue(b.run(CommandId.NewDownload))
        assertEquals(listOf(CommandId.NewDownload), ran)
    }

    @Test
    fun runningWithoutDedupeAlwaysRunsAnEnabledCommand() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran)
        assertTrue(b.run(CommandId.Settings, dedupe = false))
        assertTrue(b.run(CommandId.Settings, dedupe = false))
        assertEquals(2, ran.size)
    }

    // Key repeat

    @Test
    fun aKeyHeldDownCountsOnceUntilItIsReleased() {
        val k = KeyTracker()
        k.track(Key.Spacebar, KeyEventType.KeyDown)
        assertFalse(k.repeating)
        k.track(Key.Spacebar, KeyEventType.KeyDown)
        assertTrue(k.repeating)
        k.track(Key.Spacebar, KeyEventType.KeyDown)
        assertTrue(k.repeating)
        k.track(Key.Spacebar, KeyEventType.KeyUp)
        k.track(Key.Spacebar, KeyEventType.KeyDown)
        assertFalse(k.repeating)
    }

    @Test
    fun otherKeysDoNotMakeAKeyRepeat() {
        val k = KeyTracker()
        k.track(Key.Spacebar, KeyEventType.KeyDown)
        k.track(Key.N, KeyEventType.KeyDown)
        assertFalse(k.repeating)
    }

    // Modifiers

    @Test
    fun aModKeyWithOptionHeldMatchesNothing() {
        assertNull(matchKey(Key.N, meta = true, ctrl = false, shift = false, platform = Platform.Mac, alt = true))
        assertEquals(CommandId.NewDownload, matchKey(Key.N, meta = true, ctrl = false, shift = false, platform = Platform.Mac))
    }

    @Test
    fun bareKeysNeedEveryModifierUp() {
        assertEquals(CommandId.TogglePause, matchBareKey(Key.Spacebar))
        assertNull(matchBareKey(Key.Spacebar, alt = true))
        assertNull(matchBareKey(Key.Delete, shift = true))
        assertNull(matchBareKey(Key.Backspace, meta = true))
        assertNull(matchBareKey(Key.Escape, ctrl = true))
    }

    // Consumed keys

    @Test
    fun aDisabledCommandIsNotConsumedSoATextFieldKeepsItsKey() {
        val modal = CommandContext(modalOpen = true, visibleCount = 3)
        assertFalse(consumes(CommandId.SelectAll, modal))
        assertFalse(consumes(CommandId.FilterDownloading, modal))
        assertFalse(consumes(CommandId.ToggleTheme, modal))
        assertFalse(consumes(CommandId.ToggleUnit, modal))
        assertFalse(consumes(CommandId.SelectAll, CommandContext(menuOpen = true, visibleCount = 3)))
        assertTrue(consumes(CommandId.SelectAll, CommandContext(visibleCount = 3)))
    }

    @Test
    fun inAModalTheFileCommandsAndTheOverlayAreStillSwallowed() {
        val modal = CommandContext(modalOpen = true)
        assertTrue(consumes(CommandId.NewDownload, modal))
        assertTrue(consumes(CommandId.OpenTorrent, modal))
        assertTrue(consumes(CommandId.Settings, modal))
        assertTrue(consumes(CommandId.Shortcuts, modal))
    }

    @Test
    fun fileCommandsDoNothingWhileANetworkFieldIsEdited() {
        val editing = CommandContext(menuOpen = true, editing = true)
        fileIds.forEach { assertFalse(enabled(it, editing), it.name) }
        fileIds.forEach { assertTrue(enabled(it, CommandContext(menuOpen = true)), it.name) }
    }

    // Toggles

    @Test
    fun aToggleHeldDownRunsOnceUntilAKeyIsReleased() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran)
        assertTrue(b.run(CommandId.ToggleTheme))
        repeat(20) {
            clock.t += 40
            assertFalse(b.run(CommandId.ToggleTheme))
        }
        assertEquals(1, ran.size)
        b.release()
        clock.t += 200
        assertTrue(b.run(CommandId.ToggleTheme))
        assertEquals(2, ran.size)
    }

    @Test
    fun aToggleRunsAgainAfter500msWithoutARepeat() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran)
        b.run(CommandId.ToggleUnit)
        clock.t += 499
        assertFalse(b.run(CommandId.ToggleUnit))
        clock.t += 500
        assertTrue(b.run(CommandId.ToggleUnit))
    }

    @Test
    fun theBusReadsTheContextAtRunTime() {
        val clock = Clock()
        val ran = mutableListOf<CommandId>()
        val b = bus(clock, ran)
        var live = CommandContext(modalOpen = true)
        b.contextSource = { live }
        assertFalse(b.run(CommandId.SelectAll))
        live = CommandContext(visibleCount = 2)
        assertTrue(b.run(CommandId.SelectAll))
    }

    // Held keys that never come up

    @Test
    fun releasingCommandClearsKeysWhoseKeyUpNeverCame() {
        val k = KeyTracker()
        k.track(Key.MetaLeft, KeyEventType.KeyDown)
        k.track(Key.N, KeyEventType.KeyDown)
        k.track(Key.MetaLeft, KeyEventType.KeyUp)
        k.track(Key.MetaLeft, KeyEventType.KeyDown)
        k.track(Key.N, KeyEventType.KeyDown)
        assertFalse(k.repeating)
    }

    @Test
    fun releasingControlClearsHeldKeysToo() {
        val k = KeyTracker()
        k.track(Key.N, KeyEventType.KeyDown)
        k.track(Key.CtrlRight, KeyEventType.KeyUp)
        k.track(Key.N, KeyEventType.KeyDown)
        assertFalse(k.repeating)
    }

    @Test
    fun clearForgetsEveryHeldKey() {
        val k = KeyTracker()
        k.track(Key.Spacebar, KeyEventType.KeyDown)
        k.clear()
        k.track(Key.Spacebar, KeyEventType.KeyDown)
        assertFalse(k.repeating)
    }

    @Test
    fun aKeyUpReportsTheReleaseToTheCallback() {
        var releases = 0
        val k = KeyTracker { releases++ }
        k.track(Key.D, KeyEventType.KeyDown)
        assertEquals(0, releases)
        k.track(Key.D, KeyEventType.KeyUp)
        assertEquals(1, releases)
        k.clear()
        assertEquals(2, releases)
    }
}
