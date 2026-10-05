package dev.anydm.desktop.ui

import dev.anydm.model.EntryCounts
import dev.anydm.model.SiteDto
import dev.anydm.model.Task
import dev.anydm.model.TaskDto
import dev.anydm.model.toTask
import dev.anydm.store.retryLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class RowViewTest {
    private val mb = 1024L * 1024

    private fun task(
        status: String,
        kind: String = "file",
        extractor: String? = null,
        sourceUrl: String = "https://a.example/f",
    ): Task =
        TaskDto(
            id = "t",
            platform = if (kind == "torrent") "torrent" else "site",
            mediaKind = kind,
            status = status,
            title = "clip",
            site = extractor?.let { SiteDto(extractor = it) },
            sourceUrl = sourceUrl,
        ).toTask()

    @Test
    fun `a download in progress says how far, how fast and how long, with its bar`() {
        val row =
            rowView(
                task("downloading", kind = "video", extractor = "Youtube")
                    .copy(downloadedBytes = 412 * mb, totalBytes = 690 * mb, downloadSpeed = 8 * mb, etaSeconds = 34, progress = 60),
                0,
            )
        assertEquals("YouTube · 412.0 MB of 690.0 MB · 8.0 MB/s · 34s left", row.detail)
        assertEquals(IconKind.SITE, row.icon)
        assertEquals(Glyph.DOWN, row.glyph)
        assertEquals(0.6f, row.progress)
        assertEquals(DetailTone.NORMAL, row.tone)
        assertEquals(listOf(CardAction.PAUSE, CardAction.REMOVE), row.hover)
    }

    @Test
    fun `finished, seeding and paused rows read as the OS's own lists do`() {
        val done = rowView(task("completed").copy(totalBytes = 690 * mb), 0)
        assertEquals("690.0 MB · Finished", done.detail)
        assertEquals(Glyph.DONE, done.glyph)
        assertNull(done.progress)
        assertEquals(listOf(CardAction.SAVE, CardAction.REMOVE), done.hover)

        val seeding = rowView(task("seeding", kind = "torrent").copy(uploadSpeed = 1 * mb), 0)
        assertEquals("Seeding · ↑ 1.0 MB/s", seeding.detail)
        assertEquals(IconKind.TORRENT, seeding.icon)
        assertEquals(Glyph.SEEDING, seeding.glyph)

        val paused = rowView(task("paused").copy(downloadedBytes = 5 * mb, totalBytes = 10 * mb, progress = 50), 0)
        assertEquals("Paused · 5.0 MB of 10.0 MB", paused.detail)
        assertEquals(Glyph.PAUSED, paused.glyph)
        assertEquals(listOf(CardAction.RESUME, CardAction.REMOVE), paused.hover)
    }

    @Test
    fun `a failure is red, a retry wait is its headline`() {
        val failed = rowView(task("failed"), 0)
        assertEquals(DetailTone.ERROR, failed.tone)
        assertEquals(Glyph.RETRY, failed.glyph)
        val waiting =
            task("pending").copy(attempts = 1, maxAttempts = 3, error = "timeout", nextAttemptAt = Instant.fromEpochMilliseconds(12_000))
        val row = rowView(waiting, 0)
        assertEquals("Retrying in 12s · attempt 1 of 3", row.detail)
        assertEquals(retryLabel(waiting, 0)?.headline, row.detail)
        assertEquals(DetailTone.WARN, row.tone)
        assertEquals(Glyph.RETRY, row.glyph)
        assertNull(row.progress)
    }

    @Test
    fun `the menu holds every action in the OS's order, Copy link before Remove`() {
        val row = rowView(task("seeding", kind = "torrent").copy(filename = "a.mkv"), 0)
        assertEquals(
            listOf(CardAction.PAUSE, CardAction.STOP_SEEDING, CardAction.PLAY, CardAction.SAVE, CardAction.COPY_LINK, CardAction.REMOVE),
            row.menu,
        )
        assertEquals("Save to Downloads", actionLabel(CardAction.SAVE))
        assertEquals("Remove…", actionLabel(CardAction.REMOVE))
    }

    @Test
    fun `a group expands and reads as its counts`() {
        val group =
            TaskDto(type = "collection", id = "g", kind = "playlist", status = "downloading", title = "29C3")
                .toTask()
                .copy(
                    entryCounts =
                        EntryCounts(
                            total = 96,
                            complete = 38,
                            active = 57,
                            downloading = 2,
                            paused = 0,
                            failed = 1,
                            watched = null,
                        ),
                )
        val row = rowView(group, 0)
        assertEquals(IconKind.GROUP, row.icon)
        assertEquals(true, row.expandable)
        assertEquals(cardView(group, 0).detail, row.detail)
        assertEquals(38f / 96, row.progress)
    }
}
