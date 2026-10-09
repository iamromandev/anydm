package dev.anydm.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.model.CategoryDto
import dev.anydm.model.DOWNLOADS_CATEGORY_ID
import dev.anydm.store.CategoryState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CategoriesUiTest {
    private val state =
        CategoryState(
            categories =
                listOf(
                    CategoryDto(DOWNLOADS_CATEGORY_ID, "Downloads", "downloads", "", 0, builtin = true, count = 3),
                    CategoryDto("c1", "Music", "music", "music", 1, count = 0),
                ),
            loaded = true,
        )

    @Test
    fun `add sends the name and folder`() =
        runComposeUiTest {
            val created = mutableListOf<Pair<String, String>>()
            setContent {
                DesktopTheme(
                    dark = false,
                ) { CategoriesContent(state, { n, f -> created += n to f }, { _, _, _ -> }, { _, _ -> }, {}) }
            }
            onNodeWithTag("category-new-name").performTextInput("Lectures")
            onNodeWithTag("category-new-folder").performTextInput("edu")
            onNodeWithText("Add").performClick()
            assertEquals(listOf("Lectures" to "edu"), created)
        }

    @Test
    fun `Downloads has no delete and its folder is fixed`() =
        runComposeUiTest {
            setContent { DesktopTheme(dark = false) { CategoriesContent(state, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}) } }
            onAllNodesWithContentDescription("Delete Downloads").assertCountEquals(0)
            onNodeWithContentDescription("Delete Music").assertIsDisplayed()
            onNodeWithTag("category-folder-$DOWNLOADS_CATEGORY_ID").assertIsNotEnabled()
        }

    @Test
    fun `up and down and a refusal`() =
        runComposeUiTest {
            val moves = mutableListOf<Pair<Int, Int>>()
            setContent {
                DesktopTheme(dark = false) {
                    CategoriesContent(
                        state.copy(
                            error = "Music still holds 1 download; move them first",
                        ),
                        { _, _ -> },
                        { _, _, _ -> },
                        { i, d ->
                            moves +=
                                i to d
                        },
                        {},
                    )
                }
            }
            onNodeWithContentDescription("Move Music up").performClick()
            assertEquals(listOf(1 to -1), moves)
            onNodeWithText("Music still holds 1 download; move them first").assertIsDisplayed()
        }
}
