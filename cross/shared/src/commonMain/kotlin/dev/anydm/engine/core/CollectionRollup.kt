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

/** How many of a collection's items are in each state (MED-16); a cancelled item counts only in [total]. */
data class ItemCounts(
    val total: Int,
    val pending: Int,
    val active: Int,
    val paused: Int,
    val completed: Int,
    val failed: Int,
)

fun itemCounts(statuses: List<DownloadStatus>): ItemCounts =
    ItemCounts(
        total = statuses.size,
        pending = statuses.count { it == PENDING || it == QUEUED },
        active = statuses.count { it == DOWNLOADING || it == MUXING },
        paused = statuses.count { it == PAUSED },
        completed = statuses.count { it == COMPLETED || it == SEEDING },
        failed = statuses.count { it == FAILED },
    )

/**
 * A collection's one state, derived from its items' (MED-16) and never stored: done when all are; running while any is;
 * waiting while any waits; else failed, paused or cancelled, in that order. No items: PENDING.
 */
fun collectionStatus(children: List<DownloadStatus>): DownloadStatus =
    when {
        children.isEmpty() -> PENDING
        children.all { it == COMPLETED || it == SEEDING } -> COMPLETED
        children.any { it == DOWNLOADING || it == MUXING } -> DOWNLOADING
        children.any { it == PENDING || it == QUEUED } -> QUEUED
        children.any { it == FAILED } -> FAILED
        children.any { it == PAUSED } -> PAUSED
        else -> CANCELLED
    }
