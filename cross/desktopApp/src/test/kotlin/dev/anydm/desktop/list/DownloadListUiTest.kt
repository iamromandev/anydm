package dev.anydm.desktop.list

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.model.TaskDto
import dev.anydm.model.toTask
import dev.anydm.store.ListFilter
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DownloadListUiTest {
    private val group = TaskDto(id = "g", kind = "playlist", status = "downloading", title = "29C3").toTask()
    private val video = TaskDto(id = "v1", kind = "video", status = "complete", title = "pointer", parentId = "g").toTask()

    @Test
    fun `an empty view says so`() =
        runComposeUiTest {
            setContent { DesktopTheme(dark = false) { list(tasks = emptyList(), filter = ListFilter.SEEDING) } }
            onNodeWithText("Nothing seeding.").assertExists()
        }

    @Test
    fun `a group asks to open, and shows its videos once open`() =
        runComposeUiTest {
            val opened = mutableListOf<String>()
            setContent { DesktopTheme(dark = false) { list(tasks = listOf(group), onExpand = { opened += it }) } }
            onNodeWithContentDescription("Show videos").performClick()
            assertEquals(listOf("g"), opened)
        }

    @Test
    fun `an open group's videos are listed under it`() =
        runComposeUiTest {
            setContent { DesktopTheme(dark = false) { list(tasks = listOf(group), entries = mapOf("g" to listOf(video))) } }
            onNodeWithText("pointer").assertExists()
            onNodeWithContentDescription("Hide videos").assertExists()
        }

    @androidx.compose.runtime.Composable
    private fun list(
        tasks: List<dev.anydm.model.Task>,
        entries: Map<String, List<dev.anydm.model.Task>> = emptyMap(),
        filter: ListFilter = ListFilter.ALL,
        onExpand: (String) -> Unit = {},
    ) = DownloadList(
        tasks = tasks,
        entries = entries,
        now = 0,
        saving = emptyMap(),
        filter = filter,
        page = 1,
        totalPages = 1,
        loadingMore = false,
        onLoadMore = {},
        onExpand = onExpand,
        onCollapse = {},
        onAction = { _, _ -> },
    )
}
