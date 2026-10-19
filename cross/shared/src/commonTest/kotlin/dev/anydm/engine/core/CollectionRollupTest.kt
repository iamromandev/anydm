package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CollectionRollupTest {
    @Test
    fun theStatusFollowsTheChildren() {
        collectionStatus(emptyList()) shouldBe PENDING
        collectionStatus(listOf(COMPLETED, COMPLETED)) shouldBe COMPLETED
        collectionStatus(listOf(COMPLETED, SEEDING)) shouldBe COMPLETED
        collectionStatus(listOf(COMPLETED, DOWNLOADING, QUEUED)) shouldBe DOWNLOADING
        collectionStatus(listOf(FAILED, MUXING)) shouldBe DOWNLOADING
        collectionStatus(listOf(COMPLETED, QUEUED, FAILED)) shouldBe QUEUED
        collectionStatus(listOf(PENDING, PAUSED)) shouldBe QUEUED
        collectionStatus(listOf(COMPLETED, FAILED, PAUSED)) shouldBe FAILED
        collectionStatus(listOf(COMPLETED, PAUSED, PAUSED)) shouldBe PAUSED
        // One video the user cancelled does not make a finished playlist cancelled.
        collectionStatus(listOf(COMPLETED, CANCELLED)) shouldBe COMPLETED
        collectionStatus(listOf(CANCELLED, CANCELLED)) shouldBe CANCELLED
    }

    @Test
    fun theCountsGroupTheStatuses() {
        itemCounts(listOf(COMPLETED, SEEDING, DOWNLOADING, MUXING, QUEUED, PENDING, PAUSED, FAILED, CANCELLED)) shouldBe
            ItemCounts(total = 9, pending = 2, active = 2, paused = 1, completed = 2, failed = 1)
        itemCounts(emptyList()) shouldBe ItemCounts(0, 0, 0, 0, 0, 0)
    }
}
