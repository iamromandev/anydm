package dev.anydm.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
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
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(state, dark) }
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
}
