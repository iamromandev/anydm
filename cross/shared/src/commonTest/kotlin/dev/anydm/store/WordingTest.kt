package dev.anydm.store

import dev.anydm.model.EntryCounts
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskStatus.COMPLETED
import dev.anydm.model.TaskStatus.DOWNLOADING
import dev.anydm.model.TaskStatus.FAILED
import dev.anydm.model.TaskStatus.PAUSED
import dev.anydm.model.TaskStatus.PENDING
import dev.anydm.model.TaskStatus.SEEDING
import dev.anydm.model.TaskStatus.UNKNOWN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class WordingTest {
    @Test
    fun `a task that finishes, fails or seeds says so`() {
        assertEquals(Notice(Tone.SUCCESS, "Finished: clip"), transition(DOWNLOADING, task("a", COMPLETED, title = "clip")))
        assertEquals(Notice(Tone.INFO, "Seeding: iso"), transition(DOWNLOADING, task("a", SEEDING, title = "iso")))
        assertEquals(
            Notice(Tone.ERROR, "Failed: clip — Connection reset"),
            transition(DOWNLOADING, task("a", FAILED, title = "clip", error = "Connection reset")),
        )
    }

    @Test
    fun `first sight, no change and a pause say nothing`() {
        assertNull(transition(null, task("a", COMPLETED)))
        assertNull(transition(COMPLETED, task("a", COMPLETED)))
        assertNull(transition(DOWNLOADING, task("a", PAUSED)))
    }

    @Test
    fun `a group says how it ended`() {
        val counts = EntryCounts(total = 96, complete = 95, active = 0, downloading = 0, paused = 0, failed = 1, watched = null)
        assertEquals(
            Notice(Tone.ERROR, "Finished: 29C3, 95 of 96, 1 failed"),
            transition(DOWNLOADING, task("g", FAILED, kind = TaskKind.PLAYLIST, title = "29C3", entryCounts = counts)),
        )
        assertEquals(
            Notice(Tone.SUCCESS, "Finished: 29C3, 96 of 96"),
            transition(
                DOWNLOADING,
                task("g", COMPLETED, kind = TaskKind.PLAYLIST, title = "29C3", entryCounts = counts.copy(complete = 96, failed = 0)),
            ),
        )
        assertNull(transition(DOWNLOADING, task("g", PAUSED, kind = TaskKind.PLAYLIST)))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `a retry counts down, and a disk wait says what it waits for`() {
        val now = 1_000_000L
        val retrying =
            task("a", PENDING, attempts = 2, maxAttempts = 3).copy(nextAttemptAt = Instant.fromEpochMilliseconds(now + 11_200))
        assertEquals(RetryView(RetryTone.WARNING, "Retrying in 12s · attempt 2 of 3", null), retryLabel(retrying, now))
        assertEquals("Retrying… · attempt 2 of 3", retryLabel(retrying, now + 20_000)?.headline)
        val disk = retrying.copy(errorCode = "insufficient_storage", error = "Only 1 GB free")
        assertEquals(
            RetryView(RetryTone.WARNING, "Waiting for disk space · checking again in 12s", "Only 1 GB free"),
            retryLabel(disk, now),
        )
    }

    @Test
    fun `a failure says how many attempts it took, and plain waiting says nothing`() {
        val failed = task("a", FAILED, attempts = 1, errorCode = "network", error = "boom")
        assertEquals(RetryView(RetryTone.ERROR, "Failed after 1 attempt · network", "boom"), retryLabel(failed, 0))
        assertNull(retryLabel(task("a", PENDING), 0))
        assertNull(retryLabel(task("a", DOWNLOADING), 0))
    }

    @Test
    fun `remove offers to keep only what is whole`() {
        assertEquals(
            RemovePrompt("Remove from the list?", "The download stays on disk unless you ask for it to go too.", true, "Remove"),
            removePrompt(COMPLETED),
        )
        assertEquals("Remove this torrent?", removePrompt(SEEDING).heading)
        assertEquals(
            RemovePrompt("Stop and remove?", "Anything downloaded so far is discarded.", false, "Stop and remove"),
            removePrompt(DOWNLOADING),
        )
        assertEquals(false, removePrompt(UNKNOWN).canKeepFiles)
        assertEquals("Remove 1,234 videos?", removePrompt(DOWNLOADING, videos = 1234).heading)
        assertEquals("Remove 1 video?", removePrompt(COMPLETED, videos = 1).heading)
        assertEquals(true, removePrompt(DOWNLOADING, videos = 3).canKeepFiles)
    }

    @Test
    fun `counts carry thousands separators`() {
        assertEquals("7", count(7))
        assertEquals("1,000", count(1000))
        assertEquals("12,345,678", count(12_345_678))
    }
}

class BatchWordingTest {
    private fun item(outcome: dev.anydm.model.BatchOutcome) = dev.anydm.model.BatchItem("u", outcome, null, "")

    @Test
    fun `links are counted`() {
        assertEquals("1 link", linkCount(1))
        assertEquals("1,000 links", linkCount(1000))
    }

    @Test
    fun `a preview says how many it leaves out, and nothing when it shows them all`() {
        assertEquals("…and 115 more", previewMore(120))
        assertNull(previewMore(5))
        assertNull(previewMore(2))
    }

    @Test
    fun `the summary names every outcome, a zero included`() {
        val items =
            listOf(dev.anydm.model.BatchOutcome.ADDED, dev.anydm.model.BatchOutcome.ADDED, dev.anydm.model.BatchOutcome.DUPLICATE)
                .map(::item)
        assertEquals("2 added · 1 already in your list · 0 failed", batchSummary(items))
    }

    @Test
    fun `several links are two or more lines with something on them`() {
        assertEquals(true, looksLikeMany("https://a.test/1\nhttps://a.test/2"))
        assertEquals(true, looksLikeMany("https://a.test/1\r\n\r\nhttps://a.test/2\n"))
        assertEquals(false, looksLikeMany("https://a.test/1\n"))
        assertEquals(false, looksLikeMany("\n \nhttps://a.test/1\n "))
    }
}
