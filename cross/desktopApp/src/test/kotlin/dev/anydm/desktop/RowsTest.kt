package dev.anydm.desktop

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.ItemCounts
import kotlin.test.Test
import kotlin.test.assertEquals

class RowsTest {
    private fun ids(rows: List<Row>) = rows.map { it.id.value }

    private val playlist =
        listOf(
            snapshot(10, DOWNLOADING, name = "list", collection = CollectionKind.PLAYLIST, items = ItemCounts(3, 1, 1, 0, 1, 0)),
            snapshot(13, QUEUED, name = "c", parent = 10, playlistIndex = 2),
            snapshot(11, COMPLETED, name = "a", parent = 10, playlistIndex = 0),
            snapshot(12, DOWNLOADING, name = "b", parent = 10, playlistIndex = 1),
        )

    @Test
    fun `engine order unless sorted`() {
        val all = listOf(snapshot(2, QUEUED, name = "b"), snapshot(1, QUEUED, name = "a"))
        assertEquals(listOf(2L, 1L), ids(buildRows(all, Filter.All, ListOptions())))
        assertEquals(listOf(1L, 2L), ids(buildRows(all, Filter.All, ListOptions(sort = SortKey.NAME))))
        assertEquals(listOf(2L, 1L), ids(buildRows(all, Filter.All, ListOptions(sort = SortKey.NAME, descending = true))))
    }

    @Test
    fun `unknown sizes sort last either way`() {
        val all = listOf(snapshot(1, QUEUED), snapshot(2, QUEUED, size = 10), snapshot(3, QUEUED, size = 30))
        assertEquals(listOf(2L, 3L, 1L), ids(buildRows(all, Filter.All, ListOptions(sort = SortKey.SIZE))))
        assertEquals(listOf(3L, 2L, 1L), ids(buildRows(all, Filter.All, ListOptions(sort = SortKey.SIZE, descending = true))))
    }

    @Test
    fun `the filter and the query narrow the rows`() {
        val all =
            listOf(
                snapshot(1, QUEUED, name = "Ubuntu.ISO", category = CategoryId(1)),
                snapshot(2, FAILED, name = "song.mp3"),
                snapshot(3, QUEUED, name = "notes.txt"),
            )
        assertEquals(listOf(1L, 3L), ids(buildRows(all, Filter.Status(StatusGroup.QUEUED), ListOptions())))
        assertEquals(listOf(1L), ids(buildRows(all, Filter.InCategory(CategoryId(1)), ListOptions())))
        assertEquals(listOf(1L), ids(buildRows(all, Filter.All, ListOptions(query = "iso"))))
    }

    @Test
    fun `a collection hides its items until expanded, then shows them in playlist order`() {
        val closed = buildRows(playlist, Filter.All, ListOptions())
        assertEquals(listOf(10L), ids(closed))
        assertEquals(true, closed.single().expandable)

        val open = buildRows(playlist, Filter.All, ListOptions(expanded = setOf(DownloadId(10))))
        assertEquals(listOf(10L, 11L, 12L, 13L), ids(open))
        assertEquals(listOf(0, 1, 1, 1), open.map { it.depth })
    }

    @Test
    fun `a collection shows when one of its items matches, with only the matching items`() {
        val rows = buildRows(playlist, Filter.Status(StatusGroup.COMPLETED), ListOptions(expanded = setOf(DownloadId(10))))
        assertEquals(listOf(10L, 11L), ids(rows))
    }
}
