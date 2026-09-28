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
    }

    @Test
    fun `a group carries its counts and folder`() {
        val group = dataOf<TaskDto>("task_group.json").toTask()
        assertEquals(TaskKind.PLAYLIST, group.kind)
        val counts = assertNotNull(group.entryCounts)
        assertEquals(2, counts.total)
        assertEquals(2, counts.paused)
        assertNotNull(group.folder)
    }

    @Test
    fun `a torrent carries its files`() {
        val torrent = dataOf<TaskDto>("task_torrent.json").toTask()
        assertEquals(TaskStatus.SEEDING, torrent.status)
        assertEquals(listOf(true, false), torrent.files?.map { it.selected })
        assertEquals(3, torrent.peersConnected)
    }

    @Test
    fun `a page and the summary read`() {
        val rows = dataOf<List<TaskDto>>("page.json")
        assertTrue(rows.isNotEmpty())
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
