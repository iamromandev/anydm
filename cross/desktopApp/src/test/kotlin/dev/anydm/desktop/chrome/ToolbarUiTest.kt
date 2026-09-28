package dev.anydm.desktop.chrome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.anydm.desktop.theme.DesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ToolbarUiTest {
    @Test
    fun `the toolbar's buttons act, and Enter in the field adds the link`() =
        runComposeUiTest {
            var paused = 0
            var submitted = ""
            var preset = "best"
            setContent {
                DesktopTheme(dark = false) {
                    var link by remember { mutableStateOf("") }
                    Toolbar(
                        inset = 0.dp,
                        link = link,
                        onLink = { link = it },
                        onSubmit = { submitted = link },
                        adding = false,
                        preset = preset,
                        presets = listOf("best" to "Best", "720" to "720p"),
                        onPreset = { preset = it },
                        focus = remember { FocusRequester() },
                        onPauseAll = { paused += 1 },
                        onResumeAll = {},
                        onTorrent = {},
                        onSettings = {},
                    )
                }
            }
            onNodeWithContentDescription("Pause all").performClick()
            assertEquals(1, paused)
            onNodeWithTag("link").performTextInput("https://a.example/b")
            onNodeWithTag("link").performKeyInput { pressKey(Key.Enter) }
            assertEquals("https://a.example/b", submitted)
            onNodeWithText("Best").performClick()
            onNodeWithText("720p").performClick()
            assertEquals("720", preset)
        }
}
