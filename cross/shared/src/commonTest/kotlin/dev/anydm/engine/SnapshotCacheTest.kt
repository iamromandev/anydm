package dev.anydm.engine

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Instant

class SnapshotCacheTest {
    private fun job(
        id: Long,
        status: DownloadStatus = DownloadStatus.QUEUED,
    ) = Download(DownloadId(id), DownloadKind.DIRECT, status = status, size = 1_000)

    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun attempt(
        id: Long,
        ended: Boolean = true,
        errorClass: ErrorClass? = null,
        message: String? = null,
    ) = Attempt(AttemptId(id), DownloadId(1), t0, endedAt = if (ended) t0 else null, errorClass = errorClass, message = message)

    private fun files(written: Long) =
        listOf(
            FileSnapshot(
                File(FileId(1), DownloadId(1), 0, "a.bin", 1_000, true),
                listOf(Segment(SegmentId(1), FileId(1), 0, 1_000, written)),
            ),
        )

    private fun SnapshotCache.with(vararg jobs: Download) = apply { load(jobs.toList(), emptyMap(), emptyMap(), emptyMap()) }

    @Test
    fun aChangedJobReplacesOnlyItsRow() {
        val cache = SnapshotCache().with(job(1), job(2))

        cache.jobChanged(job(2, DownloadStatus.PAUSED))

        cache.list().map { it.download.status } shouldBe listOf(DownloadStatus.QUEUED, DownloadStatus.PAUSED)
    }

    @Test
    fun lastFailureIsTheLatestEndedAttemptWithAProblem() {
        val cache = SnapshotCache().with(job(1))
        cache.attemptChanged(attempt(1, errorClass = ErrorClass.TRANSIENT, message = "reset"))
        cache.attemptChanged(attempt(2))
        cache.attemptChanged(attempt(3, ended = false, message = "source changed"))

        cache.list().single().lastFailure shouldBe attempt(1, errorClass = ErrorClass.TRANSIENT, message = "reset")
        cache
            .list()
            .single()
            .attempts
            .map { it.id.value } shouldBe listOf(1L, 2L, 3L)
    }

    @Test
    fun anAttemptThatChangesReplacesItself() {
        val cache = SnapshotCache().with(job(1))
        cache.attemptChanged(attempt(1, ended = false))
        cache.attemptChanged(attempt(1, errorClass = ErrorClass.AUTH, message = "denied"))

        cache
            .list()
            .single()
            .attempts.size shouldBe 1
        cache
            .list()
            .single()
            .lastFailure
            ?.message shouldBe "denied"
    }

    @Test
    fun progressShowsWhileRunningAndSavedBytesAfter() {
        val cache = SnapshotCache().with(job(1, DownloadStatus.DOWNLOADING))
        cache.filesChanged(DownloadId(1), files(written = 300))

        cache.progress(DownloadId(1), 450, 2_000)
        cache.list().single().let {
            it.downloaded shouldBe 450L
            it.total shouldBe 2_000L
        }

        cache.stoppedRunning(DownloadId(1))
        cache.list().single().let {
            it.downloaded shouldBe 300L
            it.total shouldBe 1_000L
            it.speed shouldBe 0L
        }
    }

    @Test
    fun theSpeedWindowKeepsTheLastSixty() {
        val cache = SnapshotCache().with(job(1, DownloadStatus.DOWNLOADING))

        for (sample in 1L..61L) cache.speed(DownloadId(1), sample)

        cache.list().single().speedSamples shouldBe (2L..61L).toList()
        cache.list().single().speed shouldBe 61L
    }

    @Test
    fun aRemovedJobNeverComesBack() {
        val cache = SnapshotCache().with(job(1))

        cache.jobRemoved(DownloadId(1))
        cache.jobChanged(job(1))
        cache.progress(DownloadId(1), 10, null)
        cache.speed(DownloadId(1), 5)

        cache.list() shouldBe emptyList()
    }

    @Test
    fun snapshotsAreInIdOrder() {
        val cache = SnapshotCache().with(job(3))
        cache.jobChanged(job(1))
        cache.jobChanged(job(2))

        cache.list().map { it.download.id.value } shouldBe listOf(1L, 2L, 3L)
    }
}
