package dev.anydm.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
internal class UpdateIndicatorTest {
    private val v = Version.parse("1.2.0")!!
    private val page = "https://github.com/iamromandev/anydm/releases/tag/v1.2.0"

    private fun ComposeUiTest.show(
        controls: FakeUpdateControls,
        files: FakeFileActions = FakeFileActions(),
        theme: ThemeChoice = ThemeChoice.DARK,
        onMenuOpen: (Boolean) -> Unit = {},
        keys: WindowKeys = WindowKeys(),
    ) {
        val session = UpdateSession(controls, files, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        setContent {
            PlexoTheme(theme) {
                LaunchedEffect(session) { session.collectEvents() }
                UpdateIndicator(session, onMenuOpen)
                UpdateDialogs(session, keys)
            }
        }
    }

    @Test
    fun `idle shows the up arrow with the automatic label`() =
        runComposeUiTest {
            show(FakeUpdateControls())
            onNodeWithTag("update-button").assertIsDisplayed().assertContentDescriptionEquals("anydm updates automatically")
        }

    @Test
    fun `idle with automatic updates off says so`() =
        runComposeUiTest {
            show(FakeUpdateControls(auto = false))
            onNodeWithTag("update-button").assertContentDescriptionEquals("Automatic updates are off")
        }

    @Test
    fun `the button follows the state through an update`() =
        runComposeUiTest {
            val controls = FakeUpdateControls()
            show(controls, theme = ThemeChoice.LIGHT)
            controls.state.value = UpdateState.Checking
            onNodeWithTag("update-button").assertContentDescriptionEquals("Checking for updates…")
            controls.state.value = UpdateState.Available(v, page)
            onNodeWithText("Update").assertIsDisplayed()
            onNodeWithTag("update-button").assertContentDescriptionEquals("anydm 1.2.0 is available")
            controls.state.value = UpdateState.Downloading(v, 42)
            onNodeWithText("42%").assertIsDisplayed()
            controls.state.value = UpdateState.Ready(v, Path.of("anydm-1.2.0.dmg"))
            onNodeWithText("Restart to update").assertIsDisplayed()
            controls.state.value = UpdateState.Error(v, "bad", page)
            onNodeWithTag("update-button").assertContentDescriptionEquals("Couldn't install anydm 1.2.0")
        }

    @Test
    fun `the menu opens on a click and offers the check and the automatic tick`() =
        runComposeUiTest {
            show(FakeUpdateControls())
            onNodeWithTag("update-menu").assertDoesNotExist()
            onNodeWithTag("update-button").performClick()
            onNodeWithTag("update-menu-check").assertIsDisplayed()
            onNodeWithText("Check for Updates…").assertIsDisplayed()
            onNodeWithTag("update-menu-auto").assertIsOn()
        }

    @Test
    fun `Check for Updates asks the updater and closes the menu`() =
        runComposeUiTest {
            val controls = FakeUpdateControls()
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithTag("update-menu-check").performClick()
            assertEquals(listOf("check"), controls.calls)
            onNodeWithTag("update-menu").assertDoesNotExist()
        }

    @Test
    fun `Check for Updates does nothing while checking`() =
        runComposeUiTest {
            val controls = FakeUpdateControls(UpdateState.Checking)
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithTag("update-menu-check").performClick()
            assertEquals(emptyList(), controls.calls)
        }

    @Test
    fun `the download item starts the download`() =
        runComposeUiTest {
            val controls = FakeUpdateControls(UpdateState.Available(v, page), auto = false)
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithText("Download anydm 1.2.0").performClick()
            assertEquals(listOf("download"), controls.calls)
        }

    @Test
    fun `while downloading the item is disabled`() =
        runComposeUiTest {
            val controls = FakeUpdateControls(UpdateState.Downloading(v, 5))
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithText("Downloading 1.2.0…").performClick()
            assertEquals(emptyList(), controls.calls)
        }

    @Test
    fun `the website item opens the release page`() =
        runComposeUiTest {
            val files = FakeFileActions()
            show(FakeUpdateControls(UpdateState.Error(v, "bad", page)), files)
            onNodeWithTag("update-button").performClick()
            onNodeWithText("Get anydm 1.2.0 from the website…").performClick()
            assertEquals(listOf("openUrl $page"), files.calls)
        }

    @Test
    fun `the automatic tick turns updates off and on`() =
        runComposeUiTest {
            val controls = FakeUpdateControls()
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithTag("update-menu-auto").performClick()
            assertEquals(listOf("auto false"), controls.calls)
            onNodeWithTag("update-menu-auto").assertIsOff()
            onNodeWithTag("update-menu-auto").performClick()
            assertEquals(listOf("auto false", "auto true"), controls.calls)
        }

    @Test
    fun `restart asks the updater and quits without a question when nothing is running`() =
        runComposeUiTest {
            val controls = FakeUpdateControls(UpdateState.Ready(v, Path.of("anydm-1.2.0.dmg")))
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithText("Restart to Update to 1.2.0").performClick()
            assertEquals(listOf("restart false"), controls.calls)
            onNodeWithTag("update-confirm-dialog").assertDoesNotExist()
        }

    @Test
    fun `restart with downloads running asks first and goes ahead on Restart`() =
        runComposeUiTest {
            val controls = FakeUpdateControls(UpdateState.Ready(v, Path.of("anydm-1.2.0.dmg")))
            controls.unconfirmed = RestartResult.NeedsConfirmation(3)
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithTag("update-menu-restart").performClick()
            onNodeWithTag("update-confirm-dialog").assertIsDisplayed()
            onNodeWithText("3 downloads are active and will pause", substring = true).assertIsDisplayed()
            assertEquals(listOf("restart false"), controls.calls)
            onNodeWithText("Restart").performClick()
            assertEquals(listOf("restart false", "restart true"), controls.calls)
            onNodeWithTag("update-confirm-dialog").assertDoesNotExist()
        }

    @Test
    fun `cancelling the question does not restart`() =
        runComposeUiTest {
            val controls = FakeUpdateControls(UpdateState.Ready(v, Path.of("anydm-1.2.0.dmg")))
            controls.unconfirmed = RestartResult.NeedsConfirmation(1)
            show(controls)
            onNodeWithTag("update-button").performClick()
            onNodeWithTag("update-menu-restart").performClick()
            onNodeWithText("1 download is active and will pause", substring = true).assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertEquals(listOf("restart false"), controls.calls)
            onNodeWithTag("update-confirm-dialog").assertDoesNotExist()
        }

    @Test
    fun `a manual check with nothing newer says so until OK`() =
        runComposeUiTest {
            val controls = FakeUpdateControls()
            show(controls)
            controls.emit(UpdateEvent.UpToDate)
            onNodeWithText("You're up to date").assertIsDisplayed()
            onNodeWithTag("update-notice-ok").performClick()
            onNodeWithTag("update-notice-dialog").assertDoesNotExist()
        }

    @Test
    fun `a failed manual check says so`() =
        runComposeUiTest {
            val controls = FakeUpdateControls()
            show(controls)
            controls.emit(UpdateEvent.CheckFailed)
            onNodeWithText("Couldn't check for updates").assertIsDisplayed()
        }

    @Test
    fun `no dialog shows unless an event came`() =
        runComposeUiTest {
            show(FakeUpdateControls())
            onNodeWithTag("update-notice-dialog").assertDoesNotExist()
            onNodeWithTag("update-confirm-dialog").assertDoesNotExist()
        }

    @Test
    fun `the menu tells the window when it opens and closes`() =
        runComposeUiTest {
            val told = mutableListOf<Boolean>()
            show(FakeUpdateControls(), onMenuOpen = { told += it })
            assertEquals(false, told.last())

            onNodeWithTag("update-button").performClick()
            onNodeWithTag("update-menu").assertIsDisplayed()
            assertEquals(true, told.last())

            onNodeWithTag("update-menu-check").performClick()
            onNodeWithTag("update-menu").assertDoesNotExist()
            assertEquals(false, told.last())
        }

    @Test
    fun `a website that cannot be opened says so until OK`() =
        runComposeUiTest {
            val files = FakeFileActions()
            files.problem = "The page could not be opened: no browser"
            show(FakeUpdateControls(UpdateState.Error(v, "bad", page)), files)
            onNodeWithTag("update-button").performClick()
            onNodeWithText("Get anydm 1.2.0 from the website…").performClick()

            onNodeWithText("Couldn't open the page").assertIsDisplayed()
            onNodeWithText("The page could not be opened: no browser", substring = true).assertIsDisplayed()
            onNodeWithTag("update-notice-ok").performClick()
            onNodeWithTag("update-notice-dialog").assertDoesNotExist()
        }
}
