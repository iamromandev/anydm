package dev.anydm.desktop.list

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.desktop.ui.CardAction
import dev.anydm.desktop.ui.DetailTone
import dev.anydm.desktop.ui.Glyph
import dev.anydm.desktop.ui.IconKind
import dev.anydm.desktop.ui.RowView
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DownloadRowUiTest {
    private val view =
        RowView(
            title = "Big Buck Bunny",
            detail = "YouTube · 412.0 MB of 690.0 MB",
            tone = DetailTone.NORMAL,
            icon = IconKind.SITE,
            glyph = Glyph.DOWN,
            progress = 0.6f,
            hover = listOf(CardAction.PAUSE, CardAction.REMOVE),
            menu = listOf(CardAction.PAUSE, CardAction.COPY_LINK, CardAction.REMOVE),
            expandable = false,
        )

    @Test
    fun `hovering shows the row's two actions, and they act`() =
        runComposeUiTest {
            val done = mutableListOf<CardAction>()
            setContent { DesktopTheme(dark = false) { DownloadRow(view) { done += it } } }
            onNodeWithContentDescription("Pause").assertDoesNotExist()
            onNodeWithTag("row").performMouseInput { enter(center) }
            onNodeWithContentDescription("Pause").performClick()
            assertEquals(listOf(CardAction.PAUSE), done)
        }

    @Test
    fun `a right-click opens the menu, and its items act`() =
        runComposeUiTest {
            val done = mutableListOf<CardAction>()
            setContent { DesktopTheme(dark = false) { DownloadRow(view) { done += it } } }
            onNodeWithTag("row").performMouseInput { rightClick(center) }
            onNodeWithText("Copy link").performClick()
            assertEquals(listOf(CardAction.COPY_LINK), done)
        }

    @Test
    fun `a group's chevron opens it`() =
        runComposeUiTest {
            var opened = 0
            setContent {
                DesktopTheme(dark = true) {
                    DownloadRow(view.copy(expandable = true), onExpand = { opened += 1 }) {}
                }
            }
            onNodeWithContentDescription("Show videos").performClick()
            assertEquals(1, opened)
            onNodeWithText("Big Buck Bunny").assertExists()
        }

    @Test
    fun `a click and a right-click report their gestures, and a selected row says so`() =
        runComposeUiTest {
            val pressed = mutableListOf<Gesture>()
            setContent { DesktopTheme(dark = false) { DownloadRow(view, selected = true, onPress = { pressed += it }) {} } }
            onNodeWithTag("row").assertIsSelected()
            onNodeWithTag("row").performMouseInput { click(center) }
            onNodeWithTag("row").performMouseInput { rightClick(center) }
            assertEquals(listOf(Gesture.CLICK, Gesture.CONTEXT), pressed)
        }
}
