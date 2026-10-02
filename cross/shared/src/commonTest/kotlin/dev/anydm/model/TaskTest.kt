package dev.anydm.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskTest {
    private fun dto(json: String): TaskDto = AnydmJson.decodeFromString(json)

    @Test
    fun `a status or kind this build doesn't know reads as unknown`() {
        val task = dto("""{"id":"t","status":"teleporting","media_kind":"hologram"}""").toTask()
        assertEquals(TaskStatus.UNKNOWN, task.status)
        assertEquals(TaskKind.UNKNOWN, task.kind)
    }

    @Test
    fun `the title falls back to the file name, then the link`() {
        assertEquals(
            "a.mp4",
            dto("""{"id":"t","source_url":"https://x/a","files":[{"index":0,"path":"a.mp4"}]}""").toTask().title,
        )
        assertEquals("https://x/a", dto("""{"id":"t","source_url":"https://x/a"}""").toTask().title)
    }

    @Test
    fun `fields the API added later are ignored`() {
        assertEquals("t", dto("""{"id":"t","brand_new_field":{"x":1}}""").toTask().id)
    }

    @Test
    fun `a video knows its collection`() {
        val video = dto("""{"id":"v","collection_id":"g","position":3}""").toTask()
        assertEquals("g", video.parentId)
        assertEquals(3, video.position)
    }

    @Test
    fun `a direct download reads its one file`() {
        val task =
            dto(
                """{"type":"download","id":"d1","source_url":"https://e.com/a.mp4","platform":"direct",
                   "media_kind":"file","status":"complete","downloaded_bytes":10,"total_bytes":10,
                   "live":{"speed_bps":0},"files":[{"index":0,"path":"a.mp4","size_bytes":10,
                   "playback":{"position_seconds":4.0,"duration_seconds":60.0,"watched":false}}]}""",
            ).toTask()
        assertEquals(TaskKind.FILE, task.kind)
        assertEquals("a.mp4", task.filename)
        assertEquals(10L, task.fileSize)
        assertEquals(listOf(Position(0, 4.0, 60.0, false)), task.positions)
        assertEquals(null, task.files)
    }

    @Test
    fun `a torrent reads its live numbers and its hash`() {
        val task =
            dto(
                """{"type":"download","id":"t1","platform":"torrent","media_kind":"file","status":"downloading",
                   "downloaded_bytes":10,"torrent":{"info_hash":"aa","uploaded_bytes":5},
                   "live":{"speed_bps":900,"eta_seconds":40,"upload_speed_bps":7,"peers":3},
                   "files":[{"index":1,"path":"bbb.mp4","size_bytes":100}]}""",
            ).toTask()
        assertEquals(TaskKind.TORRENT, task.kind)
        assertEquals(900, task.downloadSpeed)
        assertEquals(7, task.uploadSpeed)
        assertEquals(3, task.peersConnected)
        assertEquals(40, task.etaSeconds)
        assertEquals("aa", task.infoHash)
        assertEquals(1, task.files!!.single().index)
    }

    @Test
    fun `a collection is a playlist row`() {
        val task =
            dto(
                """{"type":"collection","id":"c1","kind":"playlist","title":"Talks","status":"paused",
                   "folder":"Talks","speed_bps":40,"counts":{"total":2,"paused":2,"watched":1}}""",
            ).toTask()
        assertEquals(TaskKind.PLAYLIST, task.kind)
        assertEquals(EntryCounts(2, 0, 0, 0, 2, 0, 1), task.entryCounts)
        assertEquals(40, task.downloadSpeed)
        assertEquals("Talks", task.folder)
    }

    @Test
    fun `a channel's tab is a playlist row too`() {
        assertEquals(TaskKind.PLAYLIST, dto("""{"type":"collection","id":"c","kind":"channel"}""").toTask().kind)
    }
}
