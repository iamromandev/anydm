package dev.anydm.desktop.chrome

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.store.Tone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class BannerHostUiTest {
    @Test
    fun `a banner's action runs and the banner goes`() =
        runComposeUiTest {
            var revealed = 0
            val queue = BannerQueue { 0L }
            queue.push(Banner(Tone.SUCCESS, "Saved README", action = "Reveal") { revealed += 1 })
            setContent { DesktopTheme(dark = false) { BannerHost(queue) } }
            onNodeWithText("Saved README").assertExists()
            onNodeWithText("Reveal").performClick()
            assertEquals(1, revealed)
            assertNull(queue.current.value)
        }
}
