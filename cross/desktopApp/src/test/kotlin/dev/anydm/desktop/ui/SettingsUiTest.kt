package dev.anydm.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.settings.Player
import dev.anydm.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {
    @Test
    fun `each section changes its setting at once`() =
        runComposeUiTest {
            var prefs by mutableStateOf(Settings(serverUrl = "http://127.0.0.1:8046"))
            var changedServer = 0
            setContent {
                DesktopTheme(dark = false) {
                    SettingsContent(
                        prefs,
                        onChange = { change -> prefs = change(prefs) },
                        onChoosePlayer = { "/Applications/VLC.app" },
                        onChangeServer = { changedServer += 1 },
                    )
                }
            }
            onNodeWithText("Ask before removing a download").performClick()
            assertEquals(false, prefs.confirmBeforeRemove)

            onNodeWithText("Choose…").performClick()
            assertEquals(Player.Command("/Applications/VLC.app"), prefs.player)
            onNodeWithText("/Applications/VLC.app").assertExists()
            onNodeWithText("The system's default (usually the browser)").performClick()
            assertEquals(Player.System, prefs.player)

            onNodeWithText("Best").performClick()
            onNodeWithText("720p").performClick()
            assertEquals("720", prefs.defaultPreset)

            onNodeWithText("127.0.0.1:8046").assertExists()
            onNodeWithText("Change server…").performClick()
            assertEquals(1, changedServer)
        }
}
