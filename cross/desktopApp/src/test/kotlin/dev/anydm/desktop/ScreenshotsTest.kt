package dev.anydm.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes review screenshots to build/shots; the assertion only checks that they were produced. */
@OptIn(ExperimentalTestApi::class)
class ScreenshotsTest {
    private val dir = File("build/shots")

    private fun shot(
        name: String,
        state: HomeState,
        dark: Boolean,
        width: Int,
        height: Int,
        link: String? = null,
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(state, dark) }
        if (link != null) {
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput(link)
        }
        dir.mkdirs()
        val file = File(dir, "$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        assertTrue(file.length() > 0)
    }

    @Test
    fun capture() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            shot("populated-$theme-1280x800", populatedSample(), dark, 1280, 800)
            shot("empty-$theme-1280x800", emptySample(), dark, 1280, 800)
            shot("populated-$theme-900x600", populatedSample(), dark, 900, 600)
        }
    }

    @Test
    fun captureDialog() {
        val direct = "https://example.com/files/archive.zip"
        val streams = "http://127.0.0.1:8765/Project.zip"
        val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d8"
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            shot("dialog-direct-$theme-1280x800", populatedSample(), dark, 1280, 800, direct)
            shot("dialog-magnet-$theme-1280x800", populatedSample(), dark, 1280, 800, magnet)
            shot("dialog-streams-$theme-1280x800", populatedSample(), dark, 1280, 800, streams)
            shot("dialog-magnet-$theme-900x540", populatedSample(), dark, 900, 540, magnet)
            shot("dialog-magnet-$theme-900x600", populatedSample(), dark, 900, 600, magnet)
        }
    }
}
