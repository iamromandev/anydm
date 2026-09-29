package dev.anydm.desktop.chrome

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.desktop.ui.RemoveDialog
import dev.anydm.store.RemovePrompt
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NativeDialogUiTest {
    @Test
    fun `Enter confirms, Esc cancels, and the buttons do the same`() =
        runComposeUiTest {
            val said = mutableListOf<String>()
            setContent {
                DesktopTheme(dark = false) {
                    NativeDialog("Clear finished downloads?", "They leave the list.", "Clear finished", destructive = false, onCancel = {
                        said += "cancel"
                    }, onConfirm = { said += "confirm" })
                }
            }
            onNodeWithTag("dialog").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("dialog").performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Cancel").performClick()
            onNodeWithText("Clear finished").performClick()
            assertEquals(listOf("confirm", "cancel", "cancel", "confirm"), said)
        }

    @Test
    fun `the remove dialog names what goes and offers to delete files when it may`() =
        runComposeUiTest {
            var deleted: Boolean? = null
            val prompt = RemovePrompt("Remove 2 downloads?", "What finished stays on disk unless you ask for it to go too.", true, "Remove")
            setContent { DesktopTheme(dark = true) { RemoveDialog("clip, debian.iso", prompt, onCancel = {}) { deleted = it } } }
            onNodeWithText("clip, debian.iso").assertExists()
            onNodeWithText("Also delete the downloaded files").performClick()
            onNodeWithText("Remove").performClick()
            assertEquals(true, deleted)
        }
}
