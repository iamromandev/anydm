package dev.anydm.desktop

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
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

    private fun sorted(
        all: List<dev.anydm.engine.DownloadSnapshot>,
        key: SortKey,
        descending: Boolean = false,
    ) = ids(buildRows(all, Filter.All, ListOptions(sort = key, descending = descending)))

    @Test
    fun `speed sorts fastest first when descending`() {
        val all = listOf(snapshot(1, DOWNLOADING, speed = 5), snapshot(2, DOWNLOADING, speed = 0), snapshot(3, DOWNLOADING, speed = 20))
        assertEquals(listOf(3L, 1L, 2L), sorted(all, SortKey.SPEED, descending = true))
        assertEquals(listOf(2L, 1L, 3L), sorted(all, SortKey.SPEED))
    }

    @Test
    fun `eta puts rows without one last either way`() {
        val all =
            listOf(
                snapshot(1, QUEUED, total = 100),
                snapshot(2, DOWNLOADING, total = 100, downloaded = 0, speed = 1),
                snapshot(3, DOWNLOADING, total = 100, downloaded = 90, speed = 1),
            )
        assertEquals(listOf(3L, 2L, 1L), sorted(all, SortKey.ETA))
        assertEquals(listOf(2L, 3L, 1L), sorted(all, SortKey.ETA, descending = true))
    }

    @Test
    fun `protocol groups the rows in label order (FTP, HTTP, Site, Tool, Torrent)`() {
        val all =
            listOf(
                snapshot(1, QUEUED, kind = DownloadKind.TORRENT),
                snapshot(2, QUEUED, kind = DownloadKind.DIRECT),
                snapshot(3, QUEUED, kind = DownloadKind.FTP),
                snapshot(4, QUEUED, kind = DownloadKind.DIRECT),
            )
        assertEquals(listOf(3L, 2L, 4L, 1L), sorted(all, SortKey.PROTOCOL))
        assertEquals(listOf(1L, 2L, 4L, 3L), sorted(all, SortKey.PROTOCOL, descending = true))
    }

    @Test
    fun `equal keys keep a stable order by name then id`() {
        val all = listOf(snapshot(3, QUEUED, name = "b"), snapshot(2, QUEUED, name = "b"), snapshot(1, QUEUED, name = "a"))
        assertEquals(listOf(1L, 2L, 3L), sorted(all, SortKey.PRIORITY))
        assertEquals(listOf(1L, 2L, 3L), sorted(all, SortKey.PRIORITY, descending = true))
    }

    @Test
    fun `the protocol filter shows one protocol, and combines with the filter and the query`() {
        val all =
            listOf(
                snapshot(1, QUEUED, name = "a.iso"),
                snapshot(2, QUEUED, name = "b.iso"),
                snapshot(3, FAILED, name = "c.iso"),
                snapshot(4, QUEUED, name = "ubuntu.torrent", kind = DownloadKind.TORRENT),
                snapshot(5, QUEUED, name = "debian.torrent", kind = DownloadKind.TORRENT),
            )
        assertEquals(listOf(4L, 5L), ids(buildRows(all, Filter.All, ListOptions(protocol = Protocol.TORRENT))))
        assertEquals(listOf(1L, 2L, 3L), ids(buildRows(all, Filter.All, ListOptions(protocol = Protocol.HTTP))))
        assertEquals(listOf(4L), ids(buildRows(all, Filter.All, ListOptions(protocol = Protocol.TORRENT, query = "ubuntu"))))
        assertEquals(listOf(1L, 2L), ids(buildRows(all, Filter.Status(StatusGroup.QUEUED), ListOptions(protocol = Protocol.HTTP))))
        assertEquals(emptyList(), ids(buildRows(all, Filter.All, ListOptions(protocol = Protocol.FTP))))
    }

    @Test
    fun `a kind maps to its protocol`() {
        assertEquals(Protocol.HTTP, DownloadKind.DIRECT.protocol)
        assertEquals("HTTP", Protocol.HTTP.label)
        assertEquals(Protocol.TORRENT, DownloadKind.TORRENT.protocol)
    }

    private val site =
        listOf(
            snapshot(20, DOWNLOADING, name = "site", kind = DownloadKind.SITE, collection = CollectionKind.PLAYLIST),
            snapshot(21, QUEUED, name = "t", parent = 20, playlistIndex = 0, kind = DownloadKind.TORRENT),
            snapshot(22, QUEUED, name = "d", parent = 20, playlistIndex = 1, kind = DownloadKind.DIRECT),
        )

    @Test
    fun `a parent shows when a child matches the protocol filter`() {
        assertEquals(listOf(20L), ids(buildRows(site, Filter.All, ListOptions(protocol = Protocol.TORRENT))))
        assertEquals(listOf(20L), ids(buildRows(site, Filter.All, ListOptions(protocol = Protocol.HTTP))))
        assertEquals(emptyList(), ids(buildRows(site, Filter.All, ListOptions(protocol = Protocol.FTP))))
    }

    @Test
    fun `an expanded parent shows only the children of the protocol filter`() {
        val open = setOf(DownloadId(20))
        assertEquals(listOf(20L, 21L), ids(buildRows(site, Filter.All, ListOptions(protocol = Protocol.TORRENT, expanded = open))))
        assertEquals(listOf(20L, 22L), ids(buildRows(site, Filter.All, ListOptions(protocol = Protocol.HTTP, expanded = open))))
    }

    @Test
    fun `a matching parent hides its non-matching child when expanded`() {
        val rows = buildRows(site, Filter.All, ListOptions(protocol = Protocol.SITE, expanded = setOf(DownloadId(20))))
        assertEquals(listOf(20L), ids(rows))
    }

    @Test
    fun `equal speeds order by name then id either way`() {
        val all =
            listOf(
                snapshot(3, DOWNLOADING, name = "b", speed = 5),
                snapshot(2, DOWNLOADING, name = "b", speed = 5),
                snapshot(1, DOWNLOADING, name = "A", speed = 5),
            )
        assertEquals(listOf(1L, 2L, 3L), sorted(all, SortKey.SPEED))
        assertEquals(listOf(1L, 2L, 3L), sorted(all, SortKey.SPEED, descending = true))
    }

    @Test
    fun `rows without an eta order by name then id either way`() {
        val all = listOf(snapshot(3, QUEUED, name = "b"), snapshot(2, QUEUED, name = "b"), snapshot(1, QUEUED, name = "A"))
        assertEquals(listOf(1L, 2L, 3L), sorted(all, SortKey.ETA))
        assertEquals(listOf(1L, 2L, 3L), sorted(all, SortKey.ETA, descending = true))
    }

    @Test
    fun `every download kind maps to exactly one protocol`() {
        DownloadKind.entries.forEach { kind ->
            assertEquals(1, Protocol.entries.count { kind in it.kinds }, "$kind")
        }
    }
}
