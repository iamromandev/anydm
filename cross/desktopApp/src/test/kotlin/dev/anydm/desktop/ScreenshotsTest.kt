package dev.anydm.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
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
        act: ComposeUiTest.() -> Unit = {},
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(state, dark) }
        act()
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

    @Test
    fun captureNetworksMenu() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            shot("networks-open-$theme-1280x800", populatedSample(), dark, 1280, 800) { openNetworks() }
            shot("networks-off-$theme-1280x800", populatedSample(), dark, 1280, 800) {
                openNetworks()
                onNodeWithTag("network-dot-usb").performClick()
            }
            shot("networks-rename-$theme-1280x800", populatedSample(), dark, 1280, 800) {
                openNetworks()
                onNodeWithTag("network-rename-wifi").performClick()
                onNodeWithTag("network-rename-field").performTextReplacement("Home office")
            }
            shot("networks-rename-error-$theme-1280x800", populatedSample(), dark, 1280, 800) {
                openNetworks()
                onNodeWithTag("network-rename-wifi").performClick()
                onNodeWithTag("network-rename-field").performTextReplacement("usb tethering")
                onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Enter) }
            }
            shot("networks-open-$theme-900x600", populatedSample(), dark, 900, 600) { openNetworks() }
            shot("networks-empty-$theme-1280x800", emptySample(), dark, 1280, 800) { openNetworks() }
        }
    }

    @Test
    fun captureLimits() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("limits-general-$size", populatedSample(), dark, w, h) { openLimits() }
                shot("limits-general-bottom-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("schedule-enabled").performScrollTo().performClick()
                    onNodeWithTag("limits-reset-defaults").performScrollTo()
                }
                shot("limits-wifi-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("limits-nav-wifi").performClick()
                    onNodeWithTag("net-speed-limit").performClick()
                    onNodeWithTag("net-speed-field").performTextInput("10")
                    onNodeWithTag("net-data-limit").performClick()
                    onNodeWithTag("net-data-field").performTextInput("5")
                }
                shot("limits-wifi-bottom-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("limits-nav-wifi").performClick()
                    onNodeWithTag("remove-limits").performScrollTo()
                }
                shot("limits-error-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("total-limit").performClick()
                    onNodeWithTag("schedule-enabled").performScrollTo().performClick()
                    onNodeWithTag("schedule-end").performTextReplacement("08:00")
                }
            }
        }
    }

    private fun ComposeUiTest.openLimits() {
        openNetworks()
        onNodeWithTag("networks-limits-link").performClick()
    }

    private fun ComposeUiTest.openNetworks() = onNodeWithTag("network-chip").performClick()
}
