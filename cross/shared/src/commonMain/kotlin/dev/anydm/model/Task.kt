package dev.anydm.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A task's status. `UNKNOWN` is one the API invented after this build: drawn, never crashed on. */
enum class TaskStatus(
    val wire: String,
) {
    PENDING("pending"),
    DOWNLOADING("downloading"),
    MUXING("muxing"),
    PAUSED("paused"),
    SEEDING("seeding"),
    COMPLETE("complete"),
    FAILED("failed"),
    CANCELED("canceled"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(wire: String): TaskStatus = entries.firstOrNull { it != UNKNOWN && it.wire == wire } ?: UNKNOWN
    }
}

/** What a task is. A `PLAYLIST` is a group of videos (v0.5). */
enum class TaskKind(
    val wire: String,
) {
    VIDEO("video"),
    AUDIO("audio"),
    FILE("file"),
    TORRENT("torrent"),
    PLAYLIST("playlist"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(wire: String): TaskKind = entries.firstOrNull { it != UNKNOWN && it.wire == wire } ?: UNKNOWN
    }
}

/** How a group's videos stand. `watched` is only on the list's reads, never on frames. */
data class EntryCounts(
    val total: Int,
    val complete: Int,
    val active: Int,
    val downloading: Int,
    val paused: Int,
    val failed: Int,
    val watched: Int?,
)

/** One file of a torrent, and how much of it has landed. */
data class TaskFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val selected: Boolean,
    val downloadedBytes: Long,
)

/** Where one file was left in the player. */
data class Position(
    val fileIndex: Int,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val watched: Boolean,
)

/** A task as the client draws it: the web's `UiTask`, in Kotlin. */
@OptIn(ExperimentalTime::class)
data class Task(
    val id: String,
    val title: String,
    val url: String,
    val kind: TaskKind,
    val status: TaskStatus,
    val progress: Int,
    val etaSeconds: Int?,
    val error: String?,
    val errorCode: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val peersConnected: Int,
    val attempts: Int,
    val maxAttempts: Int?,
    val nextAttemptAt: Instant?,
    val platform: String,
    val extractor: String?,
    val preset: String?,
    val filename: String?,
    val fileSize: Long?,
    val infoHash: String?,
    val createdAt: Instant?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val parentId: String?,
    val position: Int?,
    val entryCounts: EntryCounts?,
    val folder: String?,
    val files: List<TaskFile>?,
    val positions: List<Position>?,
)

@OptIn(ExperimentalTime::class)
private fun instantOf(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** The API's row, flattened into what the client draws. Its title falls back as the web's does. */
@OptIn(ExperimentalTime::class)
fun TaskDto.toTask(): Task =
    Task(
        id = id,
        title = title.ifBlank { filename.ifBlank { sourceUrl } },
        url = sourceUrl,
        kind = TaskKind.of(kind),
        status = TaskStatus.of(status),
        progress = progress,
        etaSeconds = etaSeconds,
        error = error,
        errorCode = errorCode,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes ?: 0,
        downloadSpeed = speedBps,
        uploadSpeed = uploadSpeedBps,
        peersConnected = peersConnected,
        attempts = attempts,
        maxAttempts = maxAttempts,
        nextAttemptAt = instantOf(nextAttemptAt),
        platform = platform,
        extractor = extractor,
        preset = preset,
        filename = filename.ifBlank { null },
        fileSize = fileSize,
        infoHash = infoHash,
        createdAt = instantOf(createdAt),
        startedAt = instantOf(startedAt),
        completedAt = instantOf(completedAt),
        parentId = parentId,
        position = position,
        entryCounts =
            entryCounts?.let {
                EntryCounts(it.total, it.complete, it.active, it.downloading, it.paused, it.failed, it.watched)
            },
        folder = folder,
        files = files?.map { TaskFile(it.index, it.path, it.sizeBytes, it.selected, it.downloadedBytes) },
        positions = positions?.map { Position(it.fileIndex, it.positionSeconds, it.durationSeconds, it.watched) },
    )
