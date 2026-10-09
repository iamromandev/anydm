package dev.anydm.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A task's status. `UNKNOWN` is one the API invented after this build: drawn, never crashed on. */
enum class TaskStatus(
    val wire: String,
) {
    PENDING("pending"),
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    MUXING("muxing"),
    PAUSED("paused"),
    SEEDING("seeding"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN(""),
    ;

    companion object {
        fun of(wire: String): TaskStatus = entries.firstOrNull { it != UNKNOWN && it.wire == wire } ?: UNKNOWN
    }
}

/** What a task is: a download's `media_kind`, or `TORRENT`. A `PLAYLIST` is a collection's own row. */
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

/** How a collection's videos stand. `watched` is only on the list's reads, never on frames. */
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
    val category: CategoryRefDto?,
    val files: List<TaskFile>?,
    val positions: List<Position>?,
)

@OptIn(ExperimentalTime::class)
private fun instantOf(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** An API list item, flattened into what the client draws. Its title falls back as the web's does. */
@OptIn(ExperimentalTime::class)
fun TaskDto.toTask(): Task {
    if (type == "collection") return toCollectionTask()
    val isTorrent = platform == "torrent"
    // A site or direct download has exactly one file, at index 0.
    val single = if (isTorrent) null else files.firstOrNull { it.index == 0 }
    return Task(
        id = id,
        title = title.ifBlank { single?.path?.ifBlank { null } ?: url },
        url = url,
        kind = if (isTorrent) TaskKind.TORRENT else TaskKind.of(mediaKind),
        status = TaskStatus.of(status),
        progress = progress,
        etaSeconds = live.etaSeconds,
        error = error,
        errorCode = errorCode,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes ?: 0,
        downloadSpeed = live.speedBps,
        uploadSpeed = live.uploadSpeedBps,
        peersConnected = live.peers,
        attempts = attempts,
        maxAttempts = maxAttempts,
        nextAttemptAt = instantOf(nextAttemptAt),
        platform = platform,
        extractor = site?.extractor,
        preset = site?.preset,
        filename = single?.path?.ifBlank { null },
        // Zero until it finishes: the size is the finished file's.
        fileSize = single?.sizeBytes?.takeIf { it > 0 },
        infoHash = torrent?.infoHash,
        createdAt = instantOf(createdAt),
        startedAt = instantOf(startedAt),
        completedAt = instantOf(completedAt),
        parentId = collectionId,
        position = position,
        entryCounts = null,
        folder = null,
        category = category,
        files = if (isTorrent) files.map { TaskFile(it.index, it.path, it.sizeBytes, it.selected, it.downloadedBytes) } else null,
        positions =
            files.mapNotNull { file ->
                file.playback?.let { Position(file.index, it.positionSeconds, it.durationSeconds, it.watched) }
            },
    )
}

/** A collection, as the playlist row the group card draws. */
@OptIn(ExperimentalTime::class)
private fun TaskDto.toCollectionTask(): Task =
    Task(
        id = id,
        title = title.ifBlank { url },
        url = url,
        kind = TaskKind.PLAYLIST,
        status = TaskStatus.of(status),
        progress = progress,
        etaSeconds = null,
        error = null,
        errorCode = null,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes ?: 0,
        downloadSpeed = speedBps,
        uploadSpeed = 0,
        peersConnected = 0,
        attempts = 0,
        maxAttempts = null,
        nextAttemptAt = null,
        platform = "site",
        extractor = extractor,
        preset = preset,
        filename = null,
        fileSize = null,
        infoHash = null,
        createdAt = instantOf(createdAt),
        startedAt = null,
        completedAt = null,
        parentId = null,
        position = null,
        entryCounts =
            counts?.let {
                EntryCounts(it.total, it.complete, it.active, it.downloading, it.paused, it.failed, it.watched)
            },
        folder = folder,
        category = category,
        files = null,
        positions = null,
    )
