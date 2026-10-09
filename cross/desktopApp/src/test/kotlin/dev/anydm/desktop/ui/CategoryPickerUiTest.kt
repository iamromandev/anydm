package dev.anydm.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.model.CategoryDto
import dev.anydm.model.CategoryRefDto
import dev.anydm.model.DOWNLOADS_CATEGORY_ID
import dev.anydm.model.TaskDto
import dev.anydm.model.toTask
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CategoryPickerUiTest {
    @Test
    fun `the current category is chosen and Move sends the new one`() =
        runComposeUiTest {
            val task = TaskDto(id = "d1", title = "a.iso", category = CategoryRefDto(DOWNLOADS_CATEGORY_ID, "Downloads")).toTask()
            val moved = mutableListOf<String>()
            setContent {
                DesktopTheme(dark = false) {
                    CategoryPickerDialog(
                        task,
                        listOf(CategoryDto(DOWNLOADS_CATEGORY_ID, "Downloads"), CategoryDto("c1", "Music", folder = "music")),
                        onCancel = {},
                        onMove = { moved += it },
                    )
                }
            }
            onNodeWithText("Move to category").assertIsDisplayed()
            onNodeWithText("Music").performClick()
            onNodeWithText("Move").performClick()
            assertEquals(listOf("c1"), moved)
        }

    @Test
    fun `Move with the category already chosen only closes`() =
        runComposeUiTest {
            val task = TaskDto(id = "d1", title = "a.iso", category = CategoryRefDto(DOWNLOADS_CATEGORY_ID, "Downloads")).toTask()
            val moved = mutableListOf<String>()
            var closed = 0
            setContent {
                DesktopTheme(dark = false) {
                    CategoryPickerDialog(
                        task,
                        listOf(CategoryDto(DOWNLOADS_CATEGORY_ID, "Downloads")),
                        onCancel = { closed += 1 },
                        onMove = { moved += it },
                    )
                }
            }
            onNodeWithText("Move").performClick()
            assertEquals(emptyList(), moved)
            assertEquals(1, closed)
        }
}
