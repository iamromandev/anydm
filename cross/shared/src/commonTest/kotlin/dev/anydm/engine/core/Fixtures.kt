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

internal fun aDownload(status: DownloadStatus = PENDING): Download =
    Download(
        id = DownloadId(1),
        kind = DownloadKind.DIRECT,
        status = status,
        fileName = "file.zip",
        size = 1_000,
        priority = 2,
    )

// FND-02 restated by hand, independently of DownloadStatus.next: reading the production table would
// prove nothing.
internal val expectedTransitions: Map<DownloadStatus, Set<DownloadStatus>> =
    mapOf(
        PENDING to setOf(QUEUED, PAUSED, FAILED, CANCELLED),
        QUEUED to setOf(PENDING, DOWNLOADING, PAUSED, CANCELLED),
        DOWNLOADING to setOf(PENDING, QUEUED, MUXING, PAUSED, SEEDING, FAILED, CANCELLED),
        MUXING to setOf(QUEUED, PAUSED, SEEDING, COMPLETED, FAILED, CANCELLED),
        PAUSED to setOf(PENDING, QUEUED, CANCELLED),
        SEEDING to setOf(COMPLETED, FAILED),
        COMPLETED to emptySet(),
        FAILED to setOf(PENDING, QUEUED, CANCELLED),
        CANCELLED to emptySet(),
    )
