package dev.anydm.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.AppIcon
import dev.anydm.desktop.theme.DesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ConnectUiTest {
    @Test
    fun `Enter in either field connects with what's typed, and a failure is shown`() =
        runComposeUiTest {
            val tried = mutableListOf<Pair<String, String?>>()
            setContent {
                DesktopTheme(dark = false) {
                    ConnectScreen("", null, "Couldn't reach http://nas:8030", connecting = false) { url, key -> tried += url to key }
                }
            }
            onNodeWithText("Couldn't reach http://nas:8030").assertExists()
            onNodeWithTag("url").performTextReplacement("http://127.0.0.1:8046")
            onNodeWithTag("key").performTextInput("secret")
            onNodeWithTag("key").performKeyInput { pressKey(Key.Enter) }
            assertEquals<List<Pair<String, String?>>>(listOf("http://127.0.0.1:8046" to "secret"), tried)
        }

    @Test
    fun `the app icon loads from the resources`() {
        assertEquals(256f, AppIcon.intrinsicSize.width)
    }
}
