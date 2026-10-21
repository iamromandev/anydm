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
import dev.anydm.engine.torrent.TorrentLive
import dev.anydm.engine.torrent.TorrentSummary
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

    private fun summary(peers: Int = 2) = TorrentSummary(peers, 1, 1.5, 100, 20, 7, 0.5, 0)

    private fun live(
        peers: Int = 2,
        done: List<Long> = listOf(400L),
    ) = TorrentLive(summary(peers), done)

    @Test
    fun aTorrentSummaryShowsInTheSnapshotAndClears() {
        val cache = SnapshotCache().with(job(1, DownloadStatus.DOWNLOADING))
        cache.list().single().torrent shouldBe null

        cache.torrentLive(DownloadId(1), live()) shouldBe true
        cache.list().single().torrent shouldBe summary()
        cache.liveTorrentIds() shouldBe setOf(DownloadId(1))

        cache.torrentLive(DownloadId(1), null) shouldBe true
        cache.list().single().torrent shouldBe null
        cache.liveTorrentIds() shouldBe emptySet()
    }

    @Test
    fun liveFileDoneOverridesTheStoredDone() {
        val cache = SnapshotCache().with(job(1))
        val file = File(FileId(1), DownloadId(1), 0, "a.bin", 1_000, true)
        cache.filesChanged(DownloadId(1), listOf(FileSnapshot(file, emptyList(), done = 100)))
        cache
            .list()
            .single()
            .files
            .single()
            .written shouldBe 100L
        cache.list().single().downloaded shouldBe 100L

        cache.torrentLive(DownloadId(1), live(done = listOf(650L)))

        cache
            .list()
            .single()
            .files
            .single()
            .written shouldBe 650L
        cache.list().single().downloaded shouldBe 650L
        // gone from the session: back to what was stored
        cache.torrentLive(DownloadId(1), null)
        cache
            .list()
            .single()
            .files
            .single()
            .written shouldBe 100L
    }

    @Test
    fun aLiveFileDoneOfTheWrongLengthIsIgnored() {
        val cache = SnapshotCache().with(job(1))
        val file = File(FileId(1), DownloadId(1), 0, "a.bin", 1_000, true)
        cache.filesChanged(DownloadId(1), listOf(FileSnapshot(file, emptyList(), done = 100)))

        cache.torrentLive(DownloadId(1), live(done = listOf(1L, 2L)))

        cache
            .list()
            .single()
            .files
            .single()
            .written shouldBe 100L
        cache.list().single().torrent shouldBe summary()
    }

    @Test
    fun aStoppedTorrentsDownloadedIsTheSumOfItsDone() {
        val cache = SnapshotCache().with(job(1, DownloadStatus.PAUSED))
        val a = File(FileId(1), DownloadId(1), 0, "a.bin", 1_000, true)
        val b = File(FileId(2), DownloadId(1), 1, "b.bin", 1_000, true)
        cache.filesChanged(DownloadId(1), listOf(FileSnapshot(a, emptyList(), 300), FileSnapshot(b, emptyList(), 50)))

        cache.list().single().downloaded shouldBe 350L
        cache.list().single().torrent shouldBe null
    }

    @Test
    fun aPartialSelectionNeverShowsMoreThanItsTotal() {
        val cache = SnapshotCache().with(job(1, DownloadStatus.SEEDING))
        val wanted = File(FileId(1), DownloadId(1), 0, "a.bin", 300, true)
        val skipped = File(FileId(2), DownloadId(1), 1, "b.bin", 700, false)
        cache.filesChanged(DownloadId(1), listOf(FileSnapshot(wanted, emptyList(), 300), FileSnapshot(skipped, emptyList(), 120)))

        // the skipped file shares pieces with the wanted one, so libtorrent reports some of its bytes: they are not downloaded
        cache.list().single().downloaded shouldBe 300L
        cache.torrentLive(DownloadId(1), live(done = listOf(300L, 150L)))
        cache.list().single().downloaded shouldBe 300L
    }

    @Test
    fun torrentLiveReportsNoChangeForAnEqualSummary() {
        val cache = SnapshotCache().with(job(1))

        cache.torrentLive(DownloadId(1), live()) shouldBe true
        cache.torrentLive(DownloadId(1), live()) shouldBe false
        cache.torrentLive(DownloadId(1), live(peers = 3)) shouldBe true
        cache.torrentLive(DownloadId(99), live()) shouldBe false
        cache.torrentLive(DownloadId(2), null) shouldBe false
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

    @Test
    fun downloadOfIsTheRowAsLastReportedAndNullOnceRemoved() {
        val cache = SnapshotCache().with(job(1))
        cache.downloadOf(DownloadId(2)) shouldBe null

        cache.jobChanged(job(1, DownloadStatus.PAUSED))
        cache.downloadOf(DownloadId(1)) shouldBe job(1, DownloadStatus.PAUSED)

        cache.jobRemoved(DownloadId(1))
        cache.downloadOf(DownloadId(1)) shouldBe null
    }
}
