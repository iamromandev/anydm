package dev.anydm.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentMeta
import dev.anydm.engine.torrent.TorrentResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private class KeyPicker(
    var torrent: Path? = null,
) : FilePicker {
    var torrentPicks = 0

    override fun pickTorrent(): Path? {
        torrentPicks++
        return torrent
    }

    override fun pickFolder(current: String): String? = null
}

/** Drives Home through the keyboard only. The clock moves on every call, so the bus dedupe never hides a second press. */
@OptIn(ExperimentalTestApi::class)
class ShortcutsTest {
    private var tick = 0L
    private val picker = KeyPicker()
    private val torrents = mutableMapOf<Path, TorrentResult>()
    private val env =
        FilesEnv(
            picker,
            object : TorrentLoader {
                override suspend fun load(path: Path): TorrentResult = torrents[path] ?: TorrentResult.Err("Can't read file")
            },
            "/home/test",
        )

    private fun row(
        id: String,
        status: DownloadStatus,
    ) = DownloadItem(
        id,
        "file$id.zip",
        100_000_000L,
        10_000_000L,
        if (status ==
            DownloadStatus.Downloading
        ) {
            4L * 1024 * 1024
        } else {
            0L
        },
        status,
    )

    private fun withRows(vararg rows: DownloadItem) = populatedSample().copy(downloads = rows.toList())

    private fun ComposeUiTest.home(
        state: HomeState = detailSample(),
        platform: Platform = Platform.Mac,
    ) = setContent {
        HomeScreen(
            state,
            true,
            clipboardText = { null },
            files = env,
            bus =
                CommandBus {
                    tick += 1_000
                    tick
                },
            platform = platform,
        )
    }

    // The home root, not onRoot(): an open dropdown adds a second root.
    private fun ComposeUiTest.press(key: Key) = onNodeWithTag("home-root").performKeyInput { pressKey(key) }

    private fun ComposeUiTest.mod(
        key: Key,
        shift: Boolean = false,
        meta: Boolean = true,
    ) = onNodeWithTag("home-root").performKeyInput {
        val m = if (meta) Key.MetaLeft else Key.CtrlLeft
        keyDown(m)
        if (shift) keyDown(Key.ShiftLeft)
        pressKey(key)
        if (shift) keyUp(Key.ShiftLeft)
        keyUp(m)
    }

    private fun ComposeUiTest.tickRows(vararg ids: String) = ids.forEach { onNodeWithTag("select-$it").performClick() }

    private fun ComposeUiTest.speed(
        id: String,
        text: String,
    ) = onNodeWithTag("speed-$id").assertTextEquals(text)

    private fun ComposeUiTest.dialogOpen() = onNodeWithTag("new-download-dialog").assertIsDisplayed()

    private fun ComposeUiTest.noDialog() = onNodeWithTag("new-download-dialog").assertDoesNotExist()

    private fun ComposeUiTest.noRemoveDialog() = onNodeWithTag("remove-dialog").assertDoesNotExist()

    private fun ComposeUiTest.count(text: String) = onNodeWithTag("selection-count").assertTextEquals(text)

    private fun ComposeUiTest.themeIs(state: String) =
        assertEquals(state, onNodeWithTag("home-root").fetchSemanticsNode().config[SemanticsProperties.StateDescription])

    // R1, R9, AC1

    @Test
    fun modNOpensNewDownload() =
        runComposeUiTest {
            home()
            noDialog()
            mod(Key.N)
            dialogOpen()
            press(Key.Escape)
            noDialog()
        }

    @Test
    fun modCommaWhileSettingsIsOpenKeepsOnePageAndTheSection() =
        runComposeUiTest {
            home()
            mod(Key.Comma)
            onNodeWithTag("settings-page").assertIsDisplayed()
            onNodeWithTag("settings-nav-appearance").performClick()
            mod(Key.Comma)
            assertEquals(1, onAllNodesWithTag("settings-page").fetchSemanticsNodes().size)
            onNodeWithTag("settings-nav-appearance").assertIsSelected()
        }

    @Test
    fun modNWhileSettingsIsOpenOpensTheDialogOverThePage() =
        runComposeUiTest {
            home()
            mod(Key.Comma)
            mod(Key.N)
            dialogOpen()
            onNodeWithTag("settings-page").assertIsDisplayed()
            press(Key.Escape)
            noDialog()
            onNodeWithTag("settings-page").assertIsDisplayed()
        }

    @Test
    fun modNWhileTheNewDownloadDialogIsOpenKeepsOne() =
        runComposeUiTest {
            home()
            mod(Key.N)
            mod(Key.N)
            assertEquals(1, onAllNodesWithTag("new-download-dialog").fetchSemanticsNodes().size)
            press(Key.Escape)
            noDialog()
        }

    // R3, AC2

    private fun queuedState() =
        withRows(row("a", DownloadStatus.Downloading), row("b", DownloadStatus.Downloading), row("c", DownloadStatus.Queued))

    @Test
    fun spacePausesASelectedDownloadingAndQueuedRow() =
        runComposeUiTest {
            home(queuedState())
            tickRows("a", "c")
            press(Key.Spacebar)
            speed("a", "Paused")
            speed("c", "Paused")
            speed("b", "4.0 MB/s")
        }

    @Test
    fun spaceResumesASelectedPausedRowPerTheQueueRules() =
        runComposeUiTest {
            home(withRows(row("a", DownloadStatus.Downloading), row("b", DownloadStatus.Downloading)))
            onNodeWithTag("pause-a").performClick()
            tickRows("a")
            speed("a", "Paused")
            press(Key.Spacebar)
            speed("a", "4.0 MB/s")
        }

    @Test
    fun spaceWithAMixOfPausedAndRunningRowsPauses() =
        runComposeUiTest {
            home(queuedState())
            onNodeWithTag("pause-a").performClick()
            tickRows("a", "b")
            press(Key.Spacebar)
            speed("a", "Paused")
            speed("b", "Paused")
        }

    @Test
    fun spaceOnlyActsOnVisibleSelectedRows() =
        runComposeUiTest {
            home()
            tickRows("1", "3")
            mod(Key.Three)
            press(Key.Spacebar)
            mod(Key.One)
            speed("1", "4.0 MB/s")
            speed("3", "11.0 MB/s")
        }

    // R3, AC3

    @Test
    fun spaceOnAFocusedRowWithNoSelectionTogglesThatRowAndOpensNoDetail() =
        runComposeUiTest {
            home()
            onNodeWithTag("row-1").requestFocus()
            onNodeWithTag("row-1").performKeyInput { pressKey(Key.Spacebar) }
            speed("1", "Paused")
            speed("3", "11.0 MB/s")
            onNodeWithTag("detail-name").assertDoesNotExist()
            onNodeWithTag("row-1").performKeyInput { pressKey(Key.Spacebar) }
            speed("1", "4.0 MB/s")
        }

    @Test
    fun spaceOnAFocusedQueuedRowPausesItPerTheQueueRules() =
        runComposeUiTest {
            home(queuedState())
            onNodeWithTag("row-c").requestFocus()
            onNodeWithTag("row-c").performKeyInput { pressKey(Key.Spacebar) }
            speed("c", "Paused")
            speed("a", "4.0 MB/s")
        }

    @Test
    fun spaceWithNoSelectionAndNoFocusedRowDoesNothing() =
        runComposeUiTest {
            home()
            press(Key.Spacebar)
            speed("1", "4.0 MB/s")
            speed("3", "11.0 MB/s")
            noRemoveDialog()
        }

    @Test
    fun deleteOnAFocusedRowWithNoSelectionRemovesThatRow() =
        runComposeUiTest {
            home()
            onNodeWithTag("row-1").requestFocus()
            onNodeWithTag("row-1").performKeyInput { pressKey(Key.Delete) }
            onNodeWithTag("remove-title").assertTextEquals("Remove 1 download?")
            onNodeWithTag("remove-list").performClick()
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithTag("row-3").assertIsDisplayed()
        }

    // R3, AC4

    @Test
    fun deleteWithASelectionOpensRemoveForExactlThoseRows() =
        runComposeUiTest {
            home()
            tickRows("1", "3")
            press(Key.Delete)
            onNodeWithTag("remove-title").assertTextEquals("Remove 2 downloads?")
            onNodeWithTag("remove-list").performClick()
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithTag("row-3").assertDoesNotExist()
            onNodeWithTag("row-2").assertIsDisplayed()
        }

    @Test
    fun backspaceAlsoOpensRemove() =
        runComposeUiTest {
            home()
            tickRows("2")
            press(Key.Backspace)
            onNodeWithTag("remove-title").assertTextEquals("Remove 1 download?")
        }

    @Test
    fun deleteWithNothingToTargetOpensNoDialog() =
        runComposeUiTest {
            home()
            press(Key.Delete)
            press(Key.Backspace)
            noRemoveDialog()
        }

    // R4, AC5

    @Test
    fun modAUnderTheFinishedFilterSelectsOnlyFinishedRowsAndEscClears() =
        runComposeUiTest {
            home()
            mod(Key.Three)
            mod(Key.A)
            count("1 selected")
            press(Key.Escape)
            onNodeWithTag("selection-bar").assertDoesNotExist()
            mod(Key.One)
            onNodeWithTag("select-2").assertIsOff()
        }

    @Test
    fun modASelectsEveryVisibleRowUnderAll() =
        runComposeUiTest {
            home()
            mod(Key.A)
            count("3 selected")
        }

    // R5, AC6

    @Test
    fun inTheDetailViewSpaceTogglesTheOpenDownload() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-pause").assertTextEquals("Pause")
            press(Key.Spacebar)
            onNodeWithTag("detail-pause").assertTextEquals("Resume")
            press(Key.Spacebar)
            onNodeWithTag("detail-pause").assertTextEquals("Pause")
        }

    @Test
    fun inTheDetailViewDeleteOpensRemoveForThatDownload() =
        runComposeUiTest {
            home()
            tickRows("3")
            onNodeWithTag("chevron-1").performClick()
            press(Key.Delete)
            onNodeWithTag("remove-title").assertTextEquals("Remove 1 download?")
            onNodeWithTag("remove-list").performClick()
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithTag("row-3").assertIsDisplayed()
        }

    @Test
    fun inTheDetailViewModAAndEscapeDoNotTouchTheSelection() =
        runComposeUiTest {
            home()
            tickRows("3")
            onNodeWithTag("chevron-1").performClick()
            mod(Key.A)
            press(Key.Escape)
            onNodeWithTag("detail-screen").assertDoesNotExist()
            count("1 selected")
        }

    // R6, AC7

    @Test
    fun modOOpensTheDialogAndThePickerAndACancelLeavesItEmpty() =
        runComposeUiTest {
            home()
            mod(Key.O)
            dialogOpen()
            waitForIdle()
            assertEquals(1, picker.torrentPicks)
            onNodeWithText("Paste a link or magnet").assertIsDisplayed()
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("download").assertIsDisplayedAndDisabled()
        }

    @Test
    fun modOWithAPickedTorrentLoadsIt() =
        runComposeUiTest {
            val path = Path.of("/t/show.torrent")
            torrents[path] = TorrentResult.Ok(TorrentMeta("Show", listOf(TorrentFile("a.mkv", 1_000_000))))
            picker.torrent = path
            home()
            mod(Key.O)
            waitForIdle()
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
        }

    // R7, AC8

    @Test
    fun modCommaOpensSettingsOnGeneral() =
        runComposeUiTest {
            home()
            mod(Key.Comma)
            onNodeWithTag("settings-page").assertIsDisplayed()
            onNodeWithTag("settings-folder").assertIsDisplayed()
        }

    @Test
    fun modShiftDTogglesTheTheme() =
        runComposeUiTest {
            home()
            themeIs("dark")
            mod(Key.D, shift = true)
            themeIs("light")
            mod(Key.D)
            themeIs("light")
        }

    @Test
    fun modShiftUSwitchesTheSpeedUnitBothWays() =
        runComposeUiTest {
            home()
            speed("1", "4.0 MB/s")
            mod(Key.U, shift = true)
            speed("1", "32.0 Mbps")
            mod(Key.U, shift = true)
            speed("1", "4.0 MB/s")
        }

    @Test
    fun modOneTwoThreeSwitchTheFilter() =
        runComposeUiTest {
            home()
            mod(Key.Three)
            onNodeWithTag("row-2").assertIsDisplayed()
            onNodeWithTag("row-1").assertDoesNotExist()
            mod(Key.Two)
            onNodeWithTag("row-1").assertIsDisplayed()
            onNodeWithTag("row-2").assertDoesNotExist()
            mod(Key.One)
            onNodeWithTag("row-2").assertIsDisplayed()
            onNodeWithTag("row-1").assertIsDisplayed()
        }

    // R8, AC9: text fields

    private fun ComposeUiTest.listIsUntouched() {
        speed("1", "4.0 MB/s")
        speed("3", "11.0 MB/s")
        noRemoveDialog()
        count("1 selected")
    }

    @Test
    fun backspaceAndSpaceInTheRenameFieldActOnTheText() =
        runComposeUiTest {
            home()
            tickRows("1")
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-rename-wifi").performClick()
            onNodeWithTag("network-rename-field").performTextReplacement("Wi-Fi")
            onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Backspace) }
            onNodeWithTag("network-rename-field").assertTextEquals("Wi-F")
            onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Spacebar) }
            mod(Key.A)
            onNodeWithTag("network-rename-field").assertIsDisplayed()
            listIsUntouched()
        }

    @Test
    fun backspaceAndSpaceInTheUrlFieldActOnTheText() =
        runComposeUiTest {
            home()
            tickRows("1")
            mod(Key.N)
            onNodeWithTag("url-field").performTextInput("abc")
            onNodeWithTag("url-field").performKeyInput { pressKey(Key.Backspace) }
            onNodeWithTag("url-field").assertTextEquals("ab")
            onNodeWithTag("url-field").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("url-field").performKeyInput { pressKey(Key.Delete) }
            onNodeWithTag("url-field").assertIsDisplayed()
            noRemoveDialog()
            press(Key.Escape)
            listIsUntouched()
        }

    @Test
    fun backspaceAndSpaceInAProxyFieldActOnTheText() =
        runComposeUiTest {
            home()
            tickRows("1")
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-chevron-wifi").performClick()
            onNodeWithTag("network-page-proxy-edit").performClick()
            onNodeWithTag("proxy-page-type-http").performClick()
            onNodeWithTag("proxy-page-host").performTextInput("abc")
            onNodeWithTag("proxy-page-host").performKeyInput { pressKey(Key.Backspace) }
            onNodeWithTag("proxy-page-host").assertTextEquals("ab")
            onNodeWithTag("proxy-page-host").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("proxy-page-host").performKeyInput { pressKey(Key.Delete) }
            noRemoveDialog()
            listIsUntouched()
        }

    @Test
    fun backspaceAndSpaceInALimitsFieldActOnTheText() =
        runComposeUiTest {
            home()
            tickRows("1")
            mod(Key.Comma)
            onNodeWithTag("settings-nav-speeddata").performClick()
            onNodeWithTag("total-limit").performClick()
            onNodeWithTag("total-field").performTextInput("12")
            onNodeWithTag("total-field").performKeyInput { pressKey(Key.Backspace) }
            onNodeWithTag("total-field").assertTextEquals("1")
            onNodeWithTag("total-field").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("total-field").performKeyInput { pressKey(Key.Delete) }
            noRemoveDialog()
            press(Key.Escape)
            listIsUntouched()
        }

    // R9, AC10

    private fun ComposeUiTest.listIsSame() {
        noRemoveDialog()
        speed("1", "4.0 MB/s")
        speed("3", "11.0 MB/s")
        count("1 selected")
        onNodeWithTag("row-2").assertIsDisplayed()
        themeIs("dark")
    }

    /** Space goes last: a focused button in a dialog or menu takes Space for itself, which may close it. */
    private fun ComposeUiTest.listKeysDoNothing(
        stillOpen: SemanticsNodeInteraction,
        space: Boolean = true,
    ) {
        press(Key.Delete)
        press(Key.Backspace)
        mod(Key.A)
        mod(Key.Two)
        mod(Key.D, shift = true)
        mod(Key.U, shift = true)
        listIsSame()
        stillOpen.assertIsDisplayed()
        if (space) press(Key.Spacebar)
        listIsSame()
    }

    @Test
    fun withTheRowMenuOpenListKeysDoNothing() =
        runComposeUiTest {
            home()
            tickRows("1")
            onNodeWithTag("row-1").performMouseInput { rightClick(Offset(width / 2f, height / 2f)) }
            onNodeWithTag("row-menu").assertIsDisplayed()
            // The menu's first item has the focus and takes Space as its own click, so only the other keys are tried.
            listKeysDoNothing(onNodeWithTag("row-menu"), space = false)
        }

    @Test
    fun withTheNetworksMenuOpenListKeysDoNothing() =
        runComposeUiTest {
            home()
            tickRows("1")
            onNodeWithTag("network-chip").performClick()
            listKeysDoNothing(onNodeWithTag("networks-menu"))
        }

    @Test
    fun withSettingsOpenListKeysDoNothing() =
        runComposeUiTest {
            home()
            tickRows("1")
            mod(Key.Comma)
            press(Key.Delete)
            press(Key.Backspace)
            mod(Key.A)
            press(Key.Spacebar)
            onNodeWithTag("settings-page").assertIsDisplayed()
            noRemoveDialog()
            press(Key.Escape)
            listIsSame()
        }

    @Test
    fun withTheRemoveDialogOpenListKeysDoNothingElse() =
        runComposeUiTest {
            home()
            tickRows("1")
            press(Key.Delete)
            onNodeWithTag("remove-dialog").assertIsDisplayed()
            press(Key.Spacebar)
            press(Key.Backspace)
            mod(Key.A)
            mod(Key.Two)
            mod(Key.N)
            noDialog()
            onNodeWithTag("remove-dialog").assertIsDisplayed()
            speed("1", "4.0 MB/s")
            count("1 selected")
            onNodeWithTag("row-2").assertIsDisplayed()
        }

    // Esc order

    @Test
    fun escClosesTheRowMenuBeforeClearingTheSelection() =
        runComposeUiTest {
            home()
            tickRows("1")
            onNodeWithTag("row-1").performMouseInput { rightClick(Offset(width / 2f, height / 2f)) }
            press(Key.Escape)
            onNodeWithTag("row-menu").assertDoesNotExist()
            count("1 selected")
            press(Key.Escape)
            onNodeWithTag("selection-bar").assertDoesNotExist()
        }

    @Test
    fun escInADialogClosesTheDialogAndKeepsTheSelection() =
        runComposeUiTest {
            home()
            tickRows("1")
            press(Key.Delete)
            press(Key.Escape)
            noRemoveDialog()
            count("1 selected")
        }

    // R13, AC14

    @Test
    fun modSlashOpensTheOverlayWithEveryCommandAndEscCloses() =
        runComposeUiTest {
            home()
            onNodeWithTag("shortcuts-overlay").assertDoesNotExist()
            mod(Key.Slash)
            onNodeWithTag("shortcuts-overlay").assertIsDisplayed()
            for (e in overlayEntries(Platform.Mac)) {
                // One merged node per row, so a screen reader says the label and its shortcut together.
                onNodeWithTag("shortcut-row-${e.label}").assertTextEquals(e.label, e.shortcut)
            }
            press(Key.Escape)
            onNodeWithTag("shortcuts-overlay").assertDoesNotExist()
        }

    @Test
    fun theOverlayClosesWithItsButtonAndOnAnOutsideClick() =
        runComposeUiTest {
            home()
            mod(Key.Slash)
            onNodeWithTag("shortcuts-close").performClick()
            onNodeWithTag("shortcuts-overlay").assertDoesNotExist()
            mod(Key.Slash)
            onNodeWithTag("shortcuts-scrim").performMouseInput { click(Offset(2f, 2f)) }
            onNodeWithTag("shortcuts-overlay").assertDoesNotExist()
        }

    @Test
    fun theOverlayCountsAsAModal() =
        runComposeUiTest {
            home()
            tickRows("1")
            mod(Key.Slash)
            listKeysDoNothing(onNodeWithTag("shortcuts-overlay"))
        }

    // Platform

    @Test
    fun onOtherPlatformsCtrlWorksAndCommandDoesNot() =
        runComposeUiTest {
            home(platform = Platform.Other)
            mod(Key.N, meta = true)
            noDialog()
            mod(Key.N, meta = false)
            dialogOpen()
            press(Key.Escape)
            mod(Key.Slash, meta = false)
            onNodeWithTag("shortcut-row-New download").assertTextEquals("New download", "Ctrl+N")
        }

    @Test
    fun onMacControlDoesNotWork() =
        runComposeUiTest {
            home()
            mod(Key.N, meta = false)
            noDialog()
        }

    // NFR2, AC16: the spec scenario with keys only

    @Test
    fun theSpecScenarioRunsWithKeysOnly() =
        runComposeUiTest {
            home(withRows(row("a", DownloadStatus.Downloading), row("b", DownloadStatus.Downloading), row("c", DownloadStatus.Queued)))
            mod(Key.N)
            dialogOpen()
            press(Key.Escape)
            noDialog()
            onNodeWithTag("select-a").requestFocus()
            onNodeWithTag("select-a").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("select-b").requestFocus()
            onNodeWithTag("select-b").performKeyInput { pressKey(Key.Spacebar) }
            count("2 selected")
            // Tab moves on from the checkbox to the row; Space on the checkbox would only untick it.
            onNodeWithTag("row-b").requestFocus()
            press(Key.Spacebar)
            speed("a", "Paused")
            speed("b", "Paused")
            mod(Key.Two)
            onNodeWithTag("row-c").assertIsDisplayed()
            mod(Key.A)
            count("3 selected")
            press(Key.Backspace)
            onNodeWithTag("remove-title").assertTextEquals("Remove 3 downloads?")
            press(Key.Escape)
            mod(Key.Slash)
            onNodeWithTag("shortcuts-overlay").assertIsDisplayed()
        }

    // Review fixes

    private val hostMac = Platform.current() == Platform.Mac

    private fun ComposeUiTest.homeOnHost() = home(platform = Platform.current())

    private fun ComposeUiTest.hostMod(key: Key) = mod(key, meta = hostMac)

    /**
     * Types "abc", presses Mod+A, types "x": the field must read "x", so the key went to the field and not to the list.
     * [leave]: the field is on the Settings page, which hides the list; Esc goes back to it before the list is checked.
     */
    private fun ComposeUiTest.selectAllInField(
        field: SemanticsNodeInteraction,
        leave: Boolean = false,
    ) {
        field.performTextInput("abc")
        field.assertTextEquals("abc")
        hostMod(Key.A)
        field.performTextInput("x")
        field.assertTextEquals("x")
        if (leave) press(Key.Escape)
        count("1 selected")
        listIsUntouched()
    }

    @Test
    fun modAInTheUrlFieldSelectsItsText() =
        runComposeUiTest {
            homeOnHost()
            tickRows("1")
            hostMod(Key.N)
            selectAllInField(onNodeWithTag("url-field"))
        }

    @Test
    fun modAInAProxyFieldSelectsItsText() =
        runComposeUiTest {
            homeOnHost()
            tickRows("1")
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-chevron-wifi").performClick()
            onNodeWithTag("network-page-proxy-edit").performClick()
            onNodeWithTag("proxy-page-type-http").performClick()
            selectAllInField(onNodeWithTag("proxy-page-host"))
        }

    @Test
    fun modAInALimitsFieldSelectsItsText() =
        runComposeUiTest {
            homeOnHost()
            tickRows("1")
            hostMod(Key.Comma)
            onNodeWithTag("settings-nav-speeddata").performClick()
            onNodeWithTag("total-limit").performClick()
            selectAllInField(onNodeWithTag("total-field"), leave = true)
        }

    @Test
    fun modAInTheNetworkRenameFieldSelectsItsText() =
        runComposeUiTest {
            homeOnHost()
            tickRows("1")
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-rename-wifi").performClick()
            val field = onNodeWithTag("network-rename-field")
            field.performTextReplacement("abc")
            hostMod(Key.A)
            field.performTextInput("x")
            field.assertTextEquals("x")
            listIsUntouched()
        }

    @Test
    fun modOneThreeAndTheTogglesPassThroughAModalUnconsumed() =
        runComposeUiTest {
            home()
            tickRows("1")
            mod(Key.N)
            mod(Key.One)
            mod(Key.Three)
            mod(Key.D, shift = true)
            mod(Key.U, shift = true)
            themeIs("dark")
            speed("1", "4.0 MB/s")
            onNodeWithTag("row-1").assertIsDisplayed()
        }

    // Held keys that never come up (macOS sends no KeyUp for a letter under Command)

    /** Sends a key event straight to the scene, so a KeyUp can be left out as macOS does. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun ComposeUiTest.raw(
        key: Key,
        type: KeyEventType,
        meta: Boolean = false,
        shift: Boolean = false,
        idle: Boolean = true,
    ) {
        val e = KeyEvent(key = key, type = type, isMetaPressed = meta, isShiftPressed = shift)
        (this as SkikoComposeUiTest).scene.sendKeyEvent(e)
        if (idle) waitForIdle()
    }

    private fun ComposeUiTest.cmdShiftDDown() {
        raw(Key.MetaLeft, KeyEventType.KeyDown, meta = true)
        raw(Key.ShiftLeft, KeyEventType.KeyDown, meta = true, shift = true)
        raw(Key.D, KeyEventType.KeyDown, meta = true, shift = true)
    }

    @Test
    fun aToggleRunsAgainAfterCommandWasReleasedEvenWithoutAKeyUpForTheLetter() =
        runComposeUiTest {
            home()
            themeIs("dark")
            cmdShiftDDown()
            themeIs("light")
            raw(Key.ShiftLeft, KeyEventType.KeyUp, meta = true)
            raw(Key.MetaLeft, KeyEventType.KeyUp)
            cmdShiftDDown()
            themeIs("dark")
        }

    private fun ComposeUiTest.cmdShiftUDown() {
        raw(Key.MetaLeft, KeyEventType.KeyDown, meta = true)
        raw(Key.ShiftLeft, KeyEventType.KeyDown, meta = true, shift = true)
        raw(Key.U, KeyEventType.KeyDown, meta = true, shift = true)
    }

    @Test
    fun aSecondCommandShiftUAfterTheLockoutRunsWithCommandHeldAndNoLetterKeyUp() =
        runComposeUiTest {
            val bus = CommandBus { clock }
            homeWithClock(bus)
            cmdShiftUDown()
            speed("1", "32.0 Mbps")
            clock += TOGGLE_LOCKOUT_MS + 100
            raw(Key.U, KeyEventType.KeyDown, meta = true, shift = true)
            speed("1", "4.0 MB/s")
        }

    @Test
    fun aSecondCommandShiftUWithinTheLockoutDoesNothing() =
        runComposeUiTest {
            val bus = CommandBus { clock }
            homeWithClock(bus)
            cmdShiftUDown()
            speed("1", "32.0 Mbps")
            clock += TOGGLE_LOCKOUT_MS - 100
            raw(Key.U, KeyEventType.KeyDown, meta = true, shift = true)
            speed("1", "32.0 Mbps")
        }

    @Test
    fun aHeldEscapeAfterClosingADialogDoesNotClearTheSelection() =
        runComposeUiTest {
            home()
            tickRows("1")
            press(Key.Delete)
            onNodeWithTag("remove-dialog").assertIsDisplayed()
            raw(Key.Escape, KeyEventType.KeyDown)
            noRemoveDialog()
            raw(Key.Escape, KeyEventType.KeyDown)
            count("1 selected")
            raw(Key.Escape, KeyEventType.KeyUp)
            raw(Key.Escape, KeyEventType.KeyDown)
            onNodeWithTag("selection-bar").assertDoesNotExist()
        }

    @Test
    fun aHeldEscapeAfterClosingTheRowMenuDoesNotClearTheSelection() =
        runComposeUiTest {
            home()
            tickRows("1")
            onNodeWithTag("row-1").performMouseInput { rightClick(Offset(width / 2f, height / 2f)) }
            raw(Key.Escape, KeyEventType.KeyDown)
            onNodeWithTag("row-menu").assertDoesNotExist()
            raw(Key.Escape, KeyEventType.KeyDown)
            count("1 selected")
            raw(Key.Escape, KeyEventType.KeyUp)
            raw(Key.Escape, KeyEventType.KeyDown)
            onNodeWithTag("selection-bar").assertDoesNotExist()
        }

    @Test
    fun losingTheWindowFocusForgetsHeldKeys() =
        runComposeUiTest {
            var focused by mutableStateOf(true)
            val info =
                object : WindowInfo {
                    override val isWindowFocused: Boolean get() = focused
                }
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides info) {
                    HomeScreen(
                        detailSample(),
                        true,
                        clipboardText = { null },
                        files = env,
                        bus =
                            CommandBus {
                                tick += 1_000
                                tick
                            },
                        platform = Platform.Mac,
                    )
                }
            }
            themeIs("dark")
            cmdShiftDDown()
            themeIs("light")
            focused = false
            waitForIdle()
            focused = true
            waitForIdle()
            // Command and Shift are still down, the letter's KeyUp went to another window.
            raw(Key.D, KeyEventType.KeyDown, meta = true, shift = true)
            themeIs("dark")
        }

    // AC11: a menu accelerator and the window key event for one press

    private var clock = 10_000L

    private fun ComposeUiTest.homeWithClock(bus: CommandBus) =
        setContent { HomeScreen(detailSample(), true, clipboardText = { null }, files = env, bus = bus, platform = Platform.Mac) }

    @Test
    fun oneToggleKeyPressSeenByTheMenuAndTheKeyHandlerRunsOnce() =
        runComposeUiTest {
            val bus = CommandBus { clock }
            homeWithClock(bus)
            themeIs("dark")
            runOnIdle { bus.run(CommandId.ToggleTheme) }
            themeIs("light")
            clock += 30
            mod(Key.D, shift = true)
            themeIs("light")
            clock += 3_000
            mod(Key.D, shift = true)
            themeIs("dark")
            clock += 30
            runOnIdle { bus.run(CommandId.ToggleTheme) }
            themeIs("dark")
        }

    @Test
    fun aNativelyRepeatedAcceleratorTogglesOnce() =
        runComposeUiTest {
            val bus = CommandBus { clock }
            homeWithClock(bus)
            repeat(30) {
                runOnIdle { bus.run(CommandId.ToggleUnit) }
                clock += 40
            }
            speed("1", "32.0 Mbps")
        }

    // Stale context

    @Test
    fun twoSpacePressesInOneFrameStopThenStartTheRow() =
        runComposeUiTest {
            val bus = CommandBus { clock }
            homeWithClock(bus)
            onNodeWithTag("row-1").requestFocus()
            waitForIdle()
            // Both runs happen before any recomposition, so a stale snapshot would pause twice.
            runOnUiThread {
                bus.run(CommandId.TogglePause, dedupe = false)
                bus.run(CommandId.TogglePause, dedupe = false)
            }
            waitForIdle()
            speed("1", "4.0 MB/s")
        }

    // Modifiers

    @Test
    fun optionSpaceShiftDeleteAndCommandOptionNDoNothing() =
        runComposeUiTest {
            home()
            onNodeWithTag("row-1").requestFocus()
            onNodeWithTag("row-1").performKeyInput {
                keyDown(Key.AltLeft)
                pressKey(Key.Spacebar)
                keyUp(Key.AltLeft)
                keyDown(Key.ShiftLeft)
                pressKey(Key.Delete)
                keyUp(Key.ShiftLeft)
                keyDown(Key.MetaLeft)
                keyDown(Key.AltLeft)
                pressKey(Key.N)
                keyUp(Key.AltLeft)
                keyUp(Key.MetaLeft)
            }
            speed("1", "4.0 MB/s")
            noRemoveDialog()
            noDialog()
        }

    // The filter dropdown counts as an open menu

    @Test
    fun withTheFilterDropdownOpenListKeysDoNothing() =
        runComposeUiTest {
            val bus = CommandBus { clock }
            homeWithClock(bus)
            tickRows("1")
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-finished").assertIsDisplayed()
            // The menu path (a native accelerator) must be refused too.
            runOnIdle {
                for (id in listOf(CommandId.SelectAll, CommandId.FilterDownloading, CommandId.TogglePause)) {
                    clock += 1_000
                    assertFalse(bus.run(id), id.name)
                }
            }
            mod(Key.A)
            mod(Key.Two)
            press(Key.Spacebar)
            speed("1", "4.0 MB/s")
            speed("3", "11.0 MB/s")
            count("1 selected")
            onNodeWithTag("filter-finished").assertIsDisplayed()
        }

    // Focus

    @Test
    fun spaceOnATabReachedCheckboxTogglesItAndKeepsTheFocus() =
        runComposeUiTest {
            home()
            onNodeWithTag("select-1").requestFocus()
            onNodeWithTag("select-1").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("select-1").assertIsOn()
            onNodeWithTag("select-1").assertIsFocused()
            onNodeWithTag("select-1").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("select-1").assertIsOff()
            speed("1", "4.0 MB/s")
        }

    @Test
    fun spaceOnASectionCheckboxTogglesItAndKeepsTheFocus() =
        runComposeUiTest {
            home()
            onNodeWithTag("select-all-downloading").requestFocus()
            onNodeWithTag("select-all-downloading").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("select-all-downloading").assertIsOn()
            onNodeWithTag("select-all-downloading").assertIsFocused()
            onNodeWithTag("select-all-downloading").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("select-all-downloading").assertIsOff()
        }

    @Test
    fun aFocusedRowStaysFocusedWhenAnotherRowIsRemoved() =
        runComposeUiTest {
            home()
            onNodeWithTag("row-1").requestFocus()
            onNodeWithTag("row-1").assertIsFocused()
            // Downloading hides the finished row 2 and shrinks the list, but row 1 is still there.
            mod(Key.Two)
            onNodeWithTag("row-2").assertDoesNotExist()
            onNodeWithTag("row-1").assertIsFocused()
        }

    @Test
    fun theDetailViewKeepsItsFocusWhenTheFilterChanges() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-pause").requestFocus()
            onNodeWithTag("detail-pause").assertIsFocused()
            mod(Key.Three)
            onNodeWithTag("detail-pause").assertIsFocused()
        }

    @Test
    fun aFocusedRowThatTheFilterHidesHandsTheFocusBackToHome() =
        runComposeUiTest {
            home()
            onNodeWithTag("row-1").requestFocus()
            mod(Key.Three)
            onNodeWithTag("row-1").assertDoesNotExist()
            mod(Key.A)
            count("1 selected")
        }

    @Test
    fun escClosesTheOverlayOverTheDialogFirstAndThenTheDialog() =
        runComposeUiTest {
            home()
            mod(Key.N)
            onNodeWithTag("url-field").assertIsFocused()
            mod(Key.Slash)
            waitForIdle()
            onNodeWithTag("shortcuts-overlay").assertIsDisplayed()
            press(Key.Escape)
            waitForIdle()
            onNodeWithTag("shortcuts-overlay").assertDoesNotExist()
            dialogOpen()
            onNodeWithTag("url-field").assertIsFocused()
            press(Key.Escape)
            noDialog()
        }

    @Test
    fun theOverlayLeavesTheFocusWhereItWasAndKeepsKeysFromIt() =
        runComposeUiTest {
            home()
            onNodeWithTag("select-1").requestFocus()
            mod(Key.Slash)
            waitForIdle()
            press(Key.Spacebar)
            onNodeWithTag("select-1").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("select-1").assertIsOff()
            press(Key.Escape)
            waitForIdle()
            onNodeWithTag("shortcuts-overlay").assertDoesNotExist()
            onNodeWithTag("select-1").assertIsFocused()
            onNodeWithTag("select-1").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("select-1").assertIsOn()
        }

    // File commands

    @Test
    fun aFileCommandClosesTheDetailFilesPopover() =
        runComposeUiTest {
            home(withRows(row("a", DownloadStatus.Downloading).copy(kind = DownloadKind.Torrent, files = listOf(SampleFile("x.bin", 100)))))
            onNodeWithTag("chevron-a").performClick()
            onNodeWithTag("detail-files").performClick()
            onNodeWithTag("detail-files-menu").assertIsDisplayed()
            mod(Key.N)
            onNodeWithTag("detail-files-menu").assertDoesNotExist()
            dialogOpen()
        }

    @Test
    fun fileCommandsDoNothingWhileANetworkIsBeingRenamed() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-rename-wifi").performClick()
            onNodeWithTag("network-rename-field").performTextReplacement("Home")
            mod(Key.N)
            mod(Key.O)
            mod(Key.Comma)
            noDialog()
            onNodeWithTag("settings-page").assertDoesNotExist()
            onNodeWithTag("network-rename-field").assertTextEquals("Home")
        }
}

private fun SemanticsNodeInteraction.assertIsDisplayedAndDisabled() {
    assertIsDisplayed()
    assertIsNotEnabled()
}
