package dev.anydm.desktop

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HomeScreenTest {
    private fun ComposeUiTest.populated(dark: Boolean = true) = setContent { HomeScreen(populatedSample(), dark) }

    private fun ComposeUiTest.speed(
        id: String,
        text: String,
    ) = onNodeWithTag("speed-$id").assertTextEquals(text)

    @Test
    fun populatedScreenShowsHeadersRowsAndFooter() =
        runComposeUiTest {
            populated()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
            onNodeWithText("FINISHED 1").assertIsDisplayed()
            onNodeWithText("Project.zip").assertIsDisplayed()
            onNodeWithText("report.pdf").assertIsDisplayed()
            onNodeWithText("88% · 176.0 MB of 200.0 MB · 6s").assertIsDisplayed()
            onNodeWithText("1 downloading").assertIsDisplayed()
            onNodeWithText("2 networks").assertIsDisplayed()
            onNodeWithTag("chip-speed", useUnmergedTree = true).assertTextEquals("14.0 MB/s")
            onNodeWithTag("new-download").assertIsDisplayed()
            onNodeWithTag("slow-mode").assertIsDisplayed()
            onNodeWithTag("unit-mbps").assertIsDisplayed()
            onNodeWithTag("theme-toggle").assertIsDisplayed()
            onNodeWithText("78.5 GB free").assertIsDisplayed()
            onNodeWithText("ZIP").assertIsDisplayed()
            onNodeWithText("PDF").assertIsDisplayed()
        }

    @Test
    fun clearFinishedRemovesOnlyTheFinishedSection() =
        runComposeUiTest {
            populated()
            onNodeWithTag("clear-finished").performClick()
            onNodeWithText("FINISHED 1").assertDoesNotExist()
            onNodeWithText("report.pdf").assertDoesNotExist()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
        }

    @Test
    fun mbpsSwitchConvertsEverySpeed() =
        runComposeUiTest {
            populated()
            speed("1", "4.0 MB/s")
            onNodeWithTag("unit-mbps").performClick()
            speed("1", "32.0 Mbps")
            onNodeWithTag("chip-speed", useUnmergedTree = true).assertTextEquals("112.0 Mbps")
            onNodeWithTag("unit-mbps-bytes").performClick()
            speed("1", "4.0 MB/s")
        }

    @Test
    fun pauseShowsPausedAndResumeRestores() =
        runComposeUiTest {
            populated()
            onNodeWithTag("pause-1").performClick()
            speed("1", "Paused")
            onNodeWithContentDescription("Resume").assertIsDisplayed()
            onNodeWithTag("pause-1").performClick()
            speed("1", "4.0 MB/s")
            onNodeWithContentDescription("Pause").assertIsDisplayed()
        }

    @Test
    fun finishedFilterShowsOnlyFinished() =
        runComposeUiTest {
            populated()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-finished").performClick()
            onNodeWithText("FINISHED 1").assertIsDisplayed()
            onNodeWithText("DOWNLOADING 1").assertDoesNotExist()
            onNodeWithTag("filter").assertTextEquals("Finished")
        }

    @Test
    fun downloadingFilterShowsOnlyDownloading() =
        runComposeUiTest {
            populated()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-downloading").performClick()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
            onNodeWithText("FINISHED 1").assertDoesNotExist()
        }

    @Test
    fun filterMatchingNothingShowsNothingHere() =
        runComposeUiTest {
            populated()
            onNodeWithTag("clear-finished").performClick()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-finished").performClick()
            onNodeWithText("Nothing here").assertIsDisplayed()
            onNodeWithText("No downloads yet").assertDoesNotExist()
        }

    @Test
    fun emptyStateShowsMessageAndNoSections() =
        runComposeUiTest {
            setContent { HomeScreen(emptySample(), true) }
            onNodeWithText("No downloads yet").assertIsDisplayed()
            onNodeWithText("Paste a link, drop a .torrent, or press New download").assertIsDisplayed()
            onNodeWithTag("clear-finished").assertDoesNotExist()
            onAllNodes(hasText("DOWNLOADING")).assertCountEquals(0)
            onAllNodes(hasText("FINISHED")).assertCountEquals(0)
            onAllNodesWithTag("select-all-downloading").assertCountEquals(0)
            onAllNodesWithTag("select-all-finished").assertCountEquals(0)
            onNodeWithTag("status-text").assertDoesNotExist()
        }

    @Test
    fun themeToggleSwitchesPalette() =
        runComposeUiTest {
            populated(dark = true)

            fun palette(name: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name)

            fun pixel() = onNodeWithTag("home-root").captureToImage().toPixelMap()[600, 400]
            onNodeWithTag("home-root").assert(palette("dark"))
            val darkPixel = pixel()
            onNodeWithTag("theme-toggle").performClick()
            onNodeWithTag("home-root").assert(palette("light"))
            assertEquals(lightColors().bg, pixel())
            assertEquals(darkColors().bg, darkPixel)
            onNodeWithTag("theme-toggle").performClick()
            onNodeWithTag("home-root").assert(palette("dark"))
        }

    @Test
    fun selectAllSelectsEveryRowInTheGroup() =
        runComposeUiTest {
            populated()
            onNodeWithTag("select-all-finished").performClick()
            onNodeWithTag("select-2").assertIsOn()
            onNodeWithTag("select-all-finished").assertIsOn()
            onNodeWithTag("select-all-finished").performClick()
            onNodeWithTag("select-2").assertIsOff()
        }

    @Test
    fun longNameKeepsSpeedAndPauseVisible() =
        runComposeUiTest {
            val long =
                populatedSample().let { s ->
                    s.copy(downloads = s.downloads.map { it.copy(name = "a".repeat(300) + ".zip") })
                }
            setContent { HomeScreen(long, true) }
            onNodeWithTag("speed-1").assertIsDisplayed()
            onNodeWithTag("pause-1").assertIsDisplayed()
        }
}
