package dev.anydm.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun fixture(name: String): String =
    requireNotNull(FixtureTest::class.java.getResource("/fixtures/$name")) { "no fixture $name" }.readText()

private inline fun <reified T> dataOf(name: String): T {
    val envelope = AnydmJson.parseToJsonElement(fixture(name)).jsonObject
    return AnydmJson.decodeFromJsonElement(envelope.getValue("data"))
}

class FixtureTest {
    @Test
    fun `a finished direct download reads whole`() {
        val task = dataOf<TaskDto>("task_direct.json").toTask()
        assertEquals(TaskStatus.COMPLETE, task.status)
        assertEquals(TaskKind.FILE, task.kind)
        assertEquals(100, task.progress)
        assertTrue(task.title.isNotBlank())
        assertEquals(null, task.parentId)
        assertNotNull(task.completedAt)
        assertEquals(task.filename, task.title)
        assertNotNull(task.fileSize)
    }

    @Test
    fun `a collection carries its counts and folder`() {
        val group = dataOf<TaskDto>("task_group.json").toTask()
        assertEquals(TaskKind.PLAYLIST, group.kind)
        val counts = assertNotNull(group.entryCounts)
        assertEquals(2, counts.total)
        assertEquals(2, counts.paused)
        assertNotNull(group.folder)
    }

    @Test
    fun `a torrent carries its files, hash and swarm`() {
        val torrent = dataOf<TaskDto>("task_torrent.json").toTask()
        assertEquals(TaskKind.TORRENT, torrent.kind)
        assertEquals(TaskStatus.DOWNLOADING, torrent.status)
        assertEquals(listOf(false, true, false), torrent.files?.map { it.selected })
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", torrent.infoHash)
        assertTrue(torrent.peersConnected > 0)
        assertTrue(torrent.downloadSpeed > 0)
    }

    @Test
    fun `a page and the summary read`() {
        val rows = dataOf<List<TaskDto>>("page.json").map { it.toTask() }
        // Downloads and a collection in one list, each read by its type.
        assertEquals(1, rows.count { it.kind == TaskKind.PLAYLIST })
        assertEquals(1, rows.count { it.kind == TaskKind.TORRENT })
        assertTrue(rows.none { it.kind == TaskKind.UNKNOWN })
        val summary = dataOf<SummaryDto>("summary.json")
        assertTrue(summary.all >= 1)
        assertEquals(0, dataOf<BulkResultDto>("bulk.json").affected)
    }

    @Test
    fun `extract answers a video or a list`() {
        val media = dataOf<ExtractMediaDto>("extract_media.json")
        assertEquals("media", media.type)
        assertTrue(media.presets.isNotEmpty())
        val list = dataOf<PlaylistDto>("extract_playlist.json")
        assertEquals("playlist", list.type)
        assertEquals(96, list.count)
    }

    @Test
    fun `the page envelope has its meta`() {
        val envelope: JsonObject = AnydmJson.parseToJsonElement(fixture("page.json")).jsonObject
        val meta = AnydmJson.decodeFromJsonElement<PageMetaDto>(envelope.getValue("meta"))
        assertEquals(1, meta.page)
    }
}
