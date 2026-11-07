package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.SpeedLimit
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal class NetworksScreenTest {
    private val mib = 1024L * 1024

    private class Shown(
        val source: FakeSource,
        val visuals: NetworkVisuals,
        val limits: MutableList<String?>,
    )

    private fun network(
        key: String,
        name: String,
        speed: Double? = null,
    ) = BondedNetwork(NetworkInfo(key, name, true, listOf("10.0.0.1")), true, BindStatus.ISOLATED, speed, false)

    private fun ComposeUiTest.showApp(
        visuals: NetworkVisuals = NetworkVisuals(null),
        configure: (FakeSource) -> Unit = {},
    ): Shown {
        val source = FakeSource()
        source.networks.value = BondingState(listOf(network("en0", "Wi-Fi"), network("usb0", "USB Tethering", speed = 3.0 * mib)))
        configure(source)
        val keys = WindowKeys()
        val limits = mutableListOf<String?>()
        val window = FocusRequester()
        setContent {
            Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                App(
                    source,
                    MemoryViewPrefs(),
                    keys,
                    files = FakeFileActions(),
                    navigator = Navigator().apply { downloads() },
                    pickers = FakePickers(),
                    visuals = visuals,
                    onOpenLimits = { limits += it },
                )
            }
            LaunchedEffect(Unit) { window.requestFocus() }
        }
        return Shown(source, visuals, limits)
    }

    private fun ComposeUiTest.until(what: () -> Boolean) = waitUntil(timeoutMillis = 5_000) { what() }

    private fun ComposeUiTest.eventually(check: () -> Unit) = until { runCatching(check).isSuccess }

    private fun ComposeUiTest.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.openMenu() {
        onNodeWithText("2 networks").performClick()
        until { has("Speed & data limits") }
    }

    @Test
    fun `the pill opens the menu with a line for each network`() =
        runComposeUiTest {
            showApp()
            openMenu()
            onNodeWithText("Networks").assertIsDisplayed()
            onNodeWithText("Wi-Fi").assertIsDisplayed()
            onNodeWithContentDescription("USB limits").assertIsDisplayed()
            onNodeWithText("WIFI · en0").assertIsDisplayed()
            onNodeWithText("USB · usb0").assertIsDisplayed()
            onNodeWithText("Idle").assertIsDisplayed()
            onAllNodes(hasText("3.0 MB/s"))[0].assertIsDisplayed()
        }

    @Test
    fun `a line's chevron and the limits link ask for limits and close the menu`() =
        runComposeUiTest {
            val shown = showApp()
            openMenu()
            onNodeWithContentDescription("USB limits").performClick()
            until { shown.limits.isNotEmpty() }
            assertEquals(listOf<String?>("usb0"), shown.limits)
            // The menu closed and the dialog is up (its title is the only "Speed & data limits" left); Cancel puts it away.
            until { has("Limits the combined download speed on USB.") }
            onNodeWithText("Cancel").performClick()
            eventually { assertTrue(!has("Speed & data limits")) }
            openMenu()
            onNodeWithText("Speed & data limits").performClick()
            until { shown.limits.size == 2 }
            assertEquals(null, shown.limits[1])
        }

    @Test
    fun `a data limit line on a row opens the Limits dialog on that network`() =
        runComposeUiTest {
            val shown =
                showApp {
                    it.downloads.value =
                        listOf(
                            snapshot(
                                1,
                                DOWNLOADING,
                                name = "a.bin",
                                total = 100,
                                downloaded = 1,
                                speed = 10,
                                limitedBy = SpeedLimit.DataLimit("usb0", "USB Tethering"),
                            ),
                        )
                }
            until { has("USB reached its data limit") }
            onNodeWithText("USB reached its data limit").performClick()
            until { shown.limits.isNotEmpty() }
            assertEquals(listOf<String?>("usb0"), shown.limits)
            until { has("Limits the combined download speed on USB.") }
        }

    @Test
    fun `a chevron opens the dialog on that network's page and the link opens all downloads`() =
        runComposeUiTest {
            showApp()
            openMenu()
            onNodeWithContentDescription("USB limits").performClick()
            until { has("Limits the combined download speed on USB.") }
            onNodeWithContentDescription("Speed & data limits").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            eventually { assertTrue(onAllNodes(hasContentDescription("Speed & data limits")).fetchSemanticsNodes().isEmpty()) }
            openMenu()
            onNodeWithText("Speed & data limits").performClick()
            until { has("Limits the combined download speed across all networks.") }
        }

    @Test
    fun `saving the dialog hands the footer the slow mode it wrote`() =
        runComposeUiTest {
            val shown = showApp()
            openMenu()
            onNodeWithText("Speed & data limits").performClick()
            until { has("Limits the combined download speed across all networks.") }
            onNodeWithContentDescription("Slow mode enabled").performClick()
            onNodeWithContentDescription("Save limits").performClick()
            eventually { assertTrue(shown.source.transfer.slowMode) }
            eventually { assertTrue(onAllNodes(hasContentDescription("Speed & data limits")).fetchSemanticsNodes().isEmpty()) }
            eventually { onNodeWithContentDescription("Slow mode").assertIsOn() }
        }

    @Test
    fun `a rename and a color picked in the pencil's popover show in the menu and persist`() =
        runComposeUiTest {
            val file = Files.createTempDirectory("anydm-menu").resolve("network-visuals.json")
            val shown = showApp(NetworkVisuals(file))
            openMenu()
            onNodeWithContentDescription("Edit USB").performClick()
            until { has("NAME") }
            onNodeWithContentDescription("Network name").performTextClearance()
            onNodeWithContentDescription("Network name").performTextInput("Pixel")
            onNodeWithContentDescription("Rose").performClick()
            eventually { onNodeWithContentDescription("Rose").assertIsSelected() }
            onNodeWithText("Done").performClick()
            // The save runs off the UI thread: the name and the color land a moment apart.
            eventually { assertEquals(NetworkPref("Pixel", "rose"), shown.visuals.prefs.value["usb0"]) }
            eventually { assertTrue(has("Pixel")) }
            // After a restart the same file gives the same name and color.
            assertEquals(NetworkPref("Pixel", "rose"), NetworkVisuals(file).prefs.value["usb0"])
        }

    @Test
    fun `a name that was saved before shows in the menu when the app starts`() =
        runComposeUiTest {
            val file = Files.createTempDirectory("anydm-restart").resolve("network-visuals.json")
            NetworkVisuals(file).rename("usb0", "Pixel")
            showApp(NetworkVisuals(file))
            openMenu()
            onNodeWithText("Pixel").assertIsDisplayed()
        }

    @Test
    fun `an empty name is refused and the popover stays open with the reason`() =
        runComposeUiTest {
            val shown = showApp()
            openMenu()
            onNodeWithContentDescription("Edit USB").performClick()
            until { has("NAME") }
            onNodeWithContentDescription("Network name").performTextClearance()
            onNodeWithText("Done").performClick()
            eventually { onNodeWithText("A network needs a name.").assertIsDisplayed() }
            assertTrue(
                shown.visuals.prefs.value
                    .isEmpty(),
            )
        }

    @Test
    fun `Escape throws the draft away`() =
        runComposeUiTest {
            val shown = showApp()
            openMenu()
            onNodeWithContentDescription("Edit USB").performClick()
            until { has("NAME") }
            onNodeWithContentDescription("Network name").performTextClearance()
            onNodeWithContentDescription("Network name").performTextInput("Pixel")
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            eventually { assertTrue(!has("NAME")) }
            assertTrue(
                shown.visuals.prefs.value
                    .isEmpty(),
            )
        }

    @Test
    fun `the table's pencil on a download's page renames a network there`() =
        runComposeUiTest {
            val shown =
                showApp {
                    it.downloads.value =
                        listOf(
                            snapshot(
                                1,
                                DOWNLOADING,
                                name = "Project.zip",
                                total = 400 * mib,
                                downloaded = 100 * mib,
                                speed = mib,
                                networkShare = mapOf("en0" to 50 * mib, "usb0" to 50 * mib),
                            ),
                        )
                }
            onNodeWithContentDescription("Open Project.zip").performClick()
            until { has("TOTAL SPEED") }
            onNodeWithContentDescription("Edit USB").performClick()
            until { has("NAME") }
            onNodeWithContentDescription("Network name").performTextClearance()
            onNodeWithContentDescription("Network name").performTextInput("Pixel")
            onNodeWithText("Done").performClick()
            eventually { onNodeWithContentDescription("Use Pixel").assertIsDisplayed() }
            assertEquals(
                "Pixel",
                shown.visuals.prefs.value["usb0"]
                    ?.name,
            )
        }

    @Test
    fun `Escape in the table's popover closes it and does not leave the page`() =
        runComposeUiTest {
            showApp {
                it.downloads.value =
                    listOf(
                        snapshot(
                            1,
                            DOWNLOADING,
                            name = "Project.zip",
                            total = 400 * mib,
                            downloaded = 100 * mib,
                            speed = mib,
                            networkShare = mapOf("en0" to 50 * mib, "usb0" to 50 * mib),
                        ),
                    )
            }
            onNodeWithContentDescription("Open Project.zip").performClick()
            until { has("TOTAL SPEED") }
            onNodeWithContentDescription("Edit USB").performClick()
            until { has("NAME") }
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            eventually { assertTrue(!has("NAME")) }
            onNodeWithText("TOTAL SPEED").assertIsDisplayed()
        }

    @Test
    fun `no network shows the pill as such and an empty menu`() =
        runComposeUiTest {
            showApp { it.networks.value = BondingState(emptyList()) }
            onNodeWithText("No network").performClick()
            until { has("Speed & data limits") }
            onAllNodes(hasText("No network")).fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
        }

    @Test
    fun `the menu has a Network Doctor entry that opens the doctor and closes the menu`() =
        runComposeUiTest {
            val shown = showApp()
            openMenu()
            onNodeWithText("Network Doctor").performClick()
            until { has(DOCTOR_NOTICE) }
            eventually { assertTrue(!has("Speed & data limits")) }
            assertTrue(shown.source.calls.none { it == "diagnose" })
        }

    @Test
    fun `an idle network offers the why question and a busy one does not`() =
        runComposeUiTest {
            showApp()
            openMenu()
            assertEquals(1, onAllNodes(hasText("Why isn't this network adding speed?")).fetchSemanticsNodes().size)
            onNodeWithTag("network-help-en0").assertIsDisplayed()
            onNodeWithTag("network-help-usb0").assertDoesNotExist()
        }

    @Test
    fun `asking why on an idle network opens the doctor without running it`() =
        runComposeUiTest {
            val shown = showApp()
            openMenu()
            onNodeWithTag("network-help-en0").performClick()
            until { has(DOCTOR_NOTICE) }
            assertTrue(shown.source.calls.none { it == "diagnose" })
            assertTrue(shown.limits.isEmpty())
        }
}
