package dev.anydm.desktop.ui

import dev.anydm.api.ApiException
import dev.anydm.api.Unauthorized
import dev.anydm.model.EntryCounts
import dev.anydm.model.Task
import dev.anydm.model.TaskDto
import dev.anydm.model.TaskStatus
import dev.anydm.model.toTask
import dev.anydm.store.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class PresentTest {
    private fun task(status: String): Task = TaskDto(id = "t", kind = "file", status = status, title = "clip").toTask()

    @Test
    fun `sizes, speeds and times read as the web writes them`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("12.4 GB", formatBytes((12.4 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1.5 MB/s", formatSpeed(1_572_864))
        assertEquals("42s", formatEta(42))
        assertEquals("5m 3s", formatEta(303))
        assertEquals("1h 2m", formatEta(3_720))
    }

    @Test
    fun `sites and statuses are named as a person would`() {
        assertEquals("YouTube", siteName("Youtube"))
        assertEquals("YouTube", siteName("YoutubeTab"))
        assertEquals("X", siteName("Twitter"))
        assertEquals("Vimeo", siteName("Vimeo"))
        assertEquals("", siteName(null))
        assertEquals("Queued", statusLabel(TaskStatus.PENDING))
        assertEquals("Processing", statusLabel(TaskStatus.MUXING))
        assertEquals("Unknown", statusLabel(TaskStatus.UNKNOWN))
        assertEquals("Reconnecting…", connectionLabel(Connection.Degraded(0)))
    }

    @Test
    fun `a downloading card shows its bytes, speed and time left, and can be paused`() {
        val downloading =
            task("downloading").copy(
                progress = 40,
                downloadedBytes = 400L * 1024 * 1024,
                totalBytes = 1024L * 1024 * 1024,
                downloadSpeed = 1_572_864,
                etaSeconds = 303,
                extractor = "Youtube",
            )
        val card = cardView(downloading, 0)
        assertEquals("Downloading", card.status)
        assertEquals("YouTube · 400.0 MB / 1.0 GB", card.meta)
        assertEquals("40% · 1.5 MB/s · 5m 3s", card.detail)
        assertEquals(0.4f, card.progress)
        assertEquals(listOf(CardAction.PAUSE, CardAction.REMOVE), card.actions)
    }

    @Test
    fun `a failed card offers retry, a paused one resume, a seeding one stop`() {
        assertEquals(listOf(CardAction.RETRY, CardAction.REMOVE), cardView(task("failed"), 0).actions)
        assertEquals(listOf(CardAction.RESUME, CardAction.REMOVE), cardView(task("paused"), 0).actions)
        assertEquals(
            listOf(CardAction.PAUSE, CardAction.STOP_SEEDING, CardAction.SAVE, CardAction.REMOVE),
            cardView(task("seeding"), 0).actions,
        )
        assertEquals("Queued", cardView(task("pending"), 0).detail)
    }

    @Test
    fun `a finished media file can be played and saved, anything else only saved`() {
        val video = task("complete").copy(filename = "clip.mp4")
        assertEquals(listOf(CardAction.PLAY, CardAction.SAVE, CardAction.REMOVE), cardView(video, 0).actions)
        val iso = task("complete").copy(filename = "debian.iso")
        assertEquals(listOf(CardAction.SAVE, CardAction.REMOVE), cardView(iso, 0).actions)
        val group = TaskDto(id = "g", kind = "playlist", status = "complete", title = "29C3").toTask()
        assertEquals(listOf(CardAction.REMOVE), cardView(group, 0).actions)
    }

    @Test
    fun `a group card counts its videos`() {
        val group =
            TaskDto(id = "g", kind = "playlist", status = "downloading", title = "29C3", extractor = "YoutubeTab", preset = "1080")
                .toTask()
                .copy(downloadedBytes = (12.4 * 1024 * 1024 * 1024).toLong(), entryCounts = EntryCounts(96, 38, 57, 2, 0, 1, null))
        val card = cardView(group, 0)
        assertEquals("Playlist · YouTube · 96 videos · 1080p", card.meta)
        assertEquals("38 of 96 · 12.4 GB · 2 downloading · 55 queued · 1 failed", card.detail)
    }

    @Test
    fun `connect errors say what to check`() {
        assertEquals(
            "The server refused the key. Check it matches API_KEY in api/.env.",
            connectError(Unauthorized("bad"), "http://nas:8030"),
        )
        assertEquals("No site supports this link", connectError(ApiException("No site supports this link", 400, null), "http://a"))
        assertEquals("Couldn't reach http://nas:8030", connectError(IllegalStateException("refused"), "http://nas:8030"))
    }
}
