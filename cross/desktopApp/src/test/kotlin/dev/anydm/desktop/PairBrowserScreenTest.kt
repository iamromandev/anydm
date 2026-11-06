package dev.anydm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.os.PairedBrowsers
import dev.anydm.engine.os.PairingState
import dev.anydm.engine.os.RevokeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
internal class PairBrowserScreenTest {
    private class Shown(
        val source: FakeSource,
        val model: PairBrowserModel,
        val files: FakeFileActions,
        val keys: WindowKeys,
        val closed: IntArray,
        /** Takes the dialog out of the window without its Done button, as the window may. */
        val drop: () -> Unit,
    )

    private fun ComposeUiTest.show(
        configure: (FakeSource) -> Unit = {},
        block: Shown.() -> Unit,
    ) {
        val source = FakeSource()
        configure(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = PairBrowserModel(source, scope, poll = 50.milliseconds)
        val files = FakeFileActions()
        val keys = WindowKeys()
        val closed = intArrayOf(0)
        var open by mutableStateOf(true)
        setContent {
            PlexoTheme(ThemeChoice.DARK) {
                if (open) {
                    PairBrowserDialog(model, files, keys, onClose = {
                        closed[0]++
                        open = false
                    }, clock = { Instant.fromEpochSeconds(1_000_000) })
                }
            }
        }
        try {
            Shown(source, model, files, keys, closed) { open = false }.block()
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.until(what: () -> Boolean) = waitUntil(timeoutMillis = 5_000) { what() }

    private fun ComposeUiTest.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasDesc(text: String) = onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.confirmRevoke(label: String) =
        onNode(hasText("Revoke", substring = false) and hasAnyAncestor(hasContentDescription("Revoke $label?"))).performClick()

    private fun ComposeUiTest.generate() {
        onNodeWithContentDescription("Generate code").performClick()
        until { hasDesc("Pairing code $DEFAULT_CODE") }
    }

    @Test
    fun `before a code the dialog gives the instruction and a Generate button`() =
        runComposeUiTest {
            show {
                onNodeWithText("Open the anydm extension, choose Pair, type this code.").assertIsDisplayed()
                onNodeWithText("Generate code").assertIsDisplayed()
                until { has("No browser is paired.") }
            }
        }

    @Test
    fun `generating shows the code in groups with a countdown`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText(DEFAULT_CODE).assertIsDisplayed()
                until { has("Expires in 2:00") || has("Expires in 1:59") }
                onNodeWithText("New code").assertIsDisplayed()
            }
        }

    @Test
    fun `Copy puts the grouped code on the clipboard and says so`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Copy").performClick()
                assertEquals(listOf("copy $DEFAULT_CODE"), files.calls)
                until { has("Copied") }
            }
        }

    @Test
    fun `a copy that fails says so`() =
        runComposeUiTest {
            show {
                generate()
                files.problem = "locked"
                onNodeWithText("Copy").performClick()
                // Said in the dialog's own words: what the OS answered is about something else (a path, say).
                until { has("Couldn't copy the code") }
                assertFalse(has("locked"))
            }
        }

    @Test
    fun `a copy that failed leaves nothing to take off the clipboard later`() =
        runComposeUiTest {
            show {
                generate()
                files.problem = "locked"
                onNodeWithText("Copy").performClick()
                until { has("Couldn't copy the code") }
                onNodeWithText("Done").performClick()
                until { closed[0] == 1 }
                assertEquals(listOf("copy $DEFAULT_CODE"), files.calls)
            }
        }

    @Test
    fun `a code never copied leaves the clipboard alone`() =
        runComposeUiTest {
            show {
                generate()
                source.pairState = PairingState.BURNED
                until { has("Code used up, generate a new one") }
                onNodeWithText("Done").performClick()
                until { closed[0] == 1 }
                assertEquals(emptyList(), files.calls)
            }
        }

    @Test
    fun `the copied code comes off the clipboard when Done closes the dialog`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Copy").performClick()
                until { has("Copied") }
                onNodeWithText("Done").performClick()
                until { closed[0] == 1 }
                assertEquals(listOf("copy $DEFAULT_CODE", "clearCopied $DEFAULT_CODE"), files.calls)
            }
        }

    @Test
    fun `the copied code comes off the clipboard when Escape closes the dialog`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Copy").performClick()
                until { has("Copied") }
                onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
                until { closed[0] == 1 }
                assertEquals(listOf("copy $DEFAULT_CODE", "clearCopied $DEFAULT_CODE"), files.calls)
            }
        }

    @Test
    fun `the copied code comes off the clipboard when the dialog is dropped without Done`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Copy").performClick()
                until { has("Copied") }
                drop()
                until { !hasDesc("Browsers dialog") }
                assertEquals(listOf("copy $DEFAULT_CODE", "clearCopied $DEFAULT_CODE"), files.calls)
            }
        }

    @Test
    fun `the copied code comes off the clipboard when it is used up`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Copy").performClick()
                source.pairState = PairingState.BURNED
                until { has("Code used up, generate a new one") }
                until { files.calls.contains("clearCopied $DEFAULT_CODE") }
            }
        }

    @Test
    fun `the copied code comes off the clipboard when it runs out`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Copy").performClick()
                source.pairState = PairingState.EXPIRED
                until { has("Code expired") }
                until { files.calls.contains("clearCopied $DEFAULT_CODE") }
            }
        }

    @Test
    fun `the copied code comes off the clipboard when a browser pairs with it`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Copy").performClick()
                source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Firefox on Mac")))
                source.pairState = PairingState.PAIRED
                until { has("Paired Firefox on Mac") }
                until { files.calls.contains("clearCopied $DEFAULT_CODE") }
            }
        }

    @Test
    fun `Escape closes the dialog and clears the code`() =
        runComposeUiTest {
            show {
                generate()
                onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
                until { closed[0] == 1 }
                assertNull(model.state.value.code)
                until { "cancelPairing" in source.calls }
            }
        }

    @Test
    fun `Done tells the engine to drop the code`() =
        runComposeUiTest {
            show {
                generate()
                onNodeWithText("Done").performClick()
                until { closed[0] == 1 }
                until { "cancelPairing" in source.calls }
            }
        }

    @Test
    fun `wrong tries too many times says to wait a minute`() =
        runComposeUiTest {
            show {
                generate()
                source.pairState = PairingState.THROTTLED
                until { has("Too many wrong tries, wait a minute") }
                onNodeWithText(DEFAULT_CODE).assertIsDisplayed()
            }
        }

    @Test
    fun `a used up code is gone and the screen says to generate a new one`() =
        runComposeUiTest {
            show {
                generate()
                source.pairState = PairingState.BURNED
                until { has("Code used up, generate a new one") }
                assertFalse(has(DEFAULT_CODE))
            }
        }

    @Test
    fun `an expired code is gone and the screen says it expired`() =
        runComposeUiTest {
            show {
                generate()
                source.pairState = PairingState.EXPIRED
                until { has("Code expired") }
                assertFalse(has(DEFAULT_CODE))
            }
        }

    @Test
    fun `after a code expired New code makes another and the notice goes`() =
        runComposeUiTest {
            show {
                generate()
                source.pairState = PairingState.EXPIRED
                until { has("Code expired") }
                onNodeWithText("New code").assertIsDisplayed()
                source.pairState = PairingState.WAITING
                onNodeWithContentDescription("Generate code").performClick()
                until { hasDesc("Pairing code $DEFAULT_CODE") }
                assertFalse(has("Code expired"))
            }
        }

    @Test
    fun `losing touch with the app takes the code off and says why`() =
        runComposeUiTest {
            show {
                generate()
                source.pairStateThrows = IllegalStateException("the engine has stopped")
                until { hasDesc("Pairing problem") }
                onNodeWithText("Lost touch with the app: the engine has stopped").assertIsDisplayed()
                assertFalse(has(DEFAULT_CODE))
                onNodeWithContentDescription("Generate code").assertIsDisplayed()
            }
        }

    @Test
    fun `pairing closes the code view and notes the browser in the list`() =
        runComposeUiTest {
            show {
                generate()
                source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Firefox on Mac")))
                source.pairState = PairingState.PAIRED
                until { has("Paired Firefox on Mac") }
                assertFalse(has(DEFAULT_CODE))
                onNodeWithContentDescription("Revoke Firefox on Mac").assertIsDisplayed()
                onNodeWithText("Generate code").assertIsDisplayed()
            }
        }

    @Test
    fun `a paired browser shows its label and when it paired and was last used`() =
        runComposeUiTest {
            show({
                it.pairedList =
                    PairedBrowsers.Listed(
                        listOf(
                            browser("a", "Chrome", Instant.fromEpochSeconds(1_000_000 - 7200), Instant.fromEpochSeconds(1_000_000 - 300)),
                        ),
                    )
            }) {
                until { has("Chrome") }
                onNodeWithText("Paired 2 h ago, last used 5 min ago").assertIsDisplayed()
            }
        }

    @Test
    fun `Revoke asks first and only the confirm revokes`() =
        runComposeUiTest {
            show({ it.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome"), browser("b", "Firefox"))) }) {
                until { hasDesc("Revoke Chrome") }
                onNodeWithContentDescription("Revoke Chrome").performClick()
                until { hasDesc("Revoke Chrome?") }
                assertFalse(source.calls.any { it.startsWith("revokeBrowser") })
                onNodeWithText("Cancel").performClick()
                until { !hasDesc("Revoke Chrome?") }
                assertFalse(source.calls.any { it.startsWith("revokeBrowser") })
                onNodeWithContentDescription("Revoke Chrome").performClick()
                until { hasDesc("Revoke Chrome?") }
                confirmRevoke("Chrome")
                until { !hasDesc("Revoke Chrome") }
                assertTrue("revokeBrowser a" in source.calls)
                onNodeWithContentDescription("Revoke Firefox").assertIsDisplayed()
            }
        }

    @Test
    fun `a revoke that could not be saved shows an error line`() =
        runComposeUiTest {
            show({
                it.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome")))
                it.revokeResult = RevokeResult.NOT_SAVED
            }) {
                until { hasDesc("Revoke Chrome") }
                onNodeWithContentDescription("Revoke Chrome").performClick()
                until { hasDesc("Revoke Chrome?") }
                confirmRevoke("Chrome")
                until { hasDesc("Pairing problem") }
                assertTrue(
                    has("Revoked, but the change could not be saved: the revoked browser may be accepted again after a restart of anydm."),
                )
            }
        }

    @Test
    fun `unreadable pairings show a retry state not nothing paired and Retry reads again`() =
        runComposeUiTest {
            show({ it.pairedList = PairedBrowsers.Unavailable }) {
                until { has("The paired browsers can't be read right now.") }
                assertFalse(has("No browser is paired."))
                source.pairedList = PairedBrowsers.Listed(listOf(browser("a", "Chrome")))
                onNodeWithText("Retry").performClick()
                until { hasDesc("Revoke Chrome") }
            }
        }

    @Test
    fun `the window's keys are held back while open and the code is cleared when Done closes it`() =
        runComposeUiTest {
            show {
                until { keys.blocked }
                generate()
                onNodeWithText("Done").performClick()
                until { closed[0] == 1 }
                assertNull(model.state.value.code)
                until { !keys.blocked }
            }
        }

    @Test
    fun `the header's Browsers button opens the dialog and Done puts it away`() =
        runComposeUiTest {
            val keys = WindowKeys()
            setContent {
                App(
                    FakeSource(),
                    MemoryViewPrefs(),
                    keys,
                    files = FakeFileActions(),
                    navigator =
                        Navigator().apply {
                            downloads()
                        },
                    pickers = FakePickers(),
                )
            }
            until { has("Browsers") }
            onNodeWithText("Browsers").performClick()
            until { hasDesc("Browsers dialog") }
            until { keys.blocked }
            onNodeWithText("Done").performClick()
            until { !hasDesc("Browsers dialog") }
            until { !keys.blocked }
        }
}
