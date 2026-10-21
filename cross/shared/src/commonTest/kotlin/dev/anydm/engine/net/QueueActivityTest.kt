package dev.anydm.engine.net

import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.PauseReason.GLOBAL
import dev.anydm.engine.core.PauseReason.LOW_DISK
import dev.anydm.engine.core.PauseReason.NETWORK
import dev.anydm.engine.core.PauseReason.SCHEDULE
import dev.anydm.engine.core.PauseReason.TOOLS
import dev.anydm.engine.core.PauseReason.USER
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** What counts as busy for the after-queue action (NET-10) and as awake for keep awake (NET-11). */
class QueueActivityTest {
    private val t0 = Instant.parse("2026-10-21T12:00:00Z")

    private fun job(
        status: DownloadStatus,
        reasons: Set<PauseReason> = emptySet(),
        collection: CollectionKind? = null,
        nextAttemptAt: Instant? = null,
        startAt: Instant? = null,
    ) = Download(
        id = DownloadId(1),
        kind = DownloadKind.DIRECT,
        status = status,
        pauseReasons = reasons,
        collection = collection,
        nextAttemptAt = nextAttemptAt,
        startAt = startAt,
    )

    private fun busy(vararg jobs: Download) = QueueActivity.busy(jobs.toList())

    @Test
    fun waitingAndRunningAreBusy() {
        busy(job(PENDING)) shouldBe true
        busy(job(QUEUED)) shouldBe true
        busy(job(QUEUED, nextAttemptAt = t0 + 1.hours)) shouldBe true
        busy(job(QUEUED, startAt = t0 + 1.hours)) shouldBe true
        busy(job(DOWNLOADING)) shouldBe true
        busy(job(MUXING)) shouldBe true
    }

    @Test
    fun pausedOnlyForNetworkOrScheduleIsBusy() {
        busy(job(PAUSED, setOf(NETWORK))) shouldBe true
        busy(job(PAUSED, setOf(SCHEDULE))) shouldBe true
        busy(job(PAUSED, setOf(NETWORK, SCHEDULE))) shouldBe true
    }

    @Test
    fun anyOtherPauseIsNotBusy() {
        busy(job(PAUSED, setOf(USER))) shouldBe false
        busy(job(PAUSED, setOf(GLOBAL))) shouldBe false
        busy(job(PAUSED, setOf(USER, NETWORK))) shouldBe false
        busy(job(PAUSED, setOf(LOW_DISK))) shouldBe false
        busy(job(PAUSED, setOf(TOOLS))) shouldBe false
        busy(job(PAUSED, setOf(TOOLS, SCHEDULE))) shouldBe false
    }

    @Test
    fun finishedAndSeedingAreNotBusy() {
        busy(job(SEEDING)) shouldBe false
        busy(job(COMPLETED)) shouldBe false
        busy(job(FAILED)) shouldBe false
        busy(job(CANCELLED)) shouldBe false
    }

    @Test
    fun aCollectionRowIsIgnored() {
        busy(job(DOWNLOADING, collection = CollectionKind.PLAYLIST)) shouldBe false
    }

    @Test
    fun anEmptyListIsNotBusy() {
        QueueActivity.busy(emptyList()) shouldBe false
    }

    @Test
    fun awakeIsOnlyDownloadingOrMuxing() {
        listOf(job(PENDING), job(QUEUED), job(SEEDING), job(PAUSED, setOf(NETWORK))).forEach {
            QueueActivity.awake(listOf(it)) shouldBe false
        }
        QueueActivity.awake(listOf(job(DOWNLOADING))) shouldBe true
        QueueActivity.awake(listOf(job(MUXING))) shouldBe true
        QueueActivity.awake(listOf(job(DOWNLOADING, collection = CollectionKind.PLAYLIST))) shouldBe false
    }
}
