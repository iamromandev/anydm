package dev.anydm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A task row as the API sends it (`TaskSchema`); absent numbers read as 0. */
@Serializable
data class TaskDto(
    val id: String,
    @SerialName("source_url") val sourceUrl: String = "",
    val platform: String = "",
    val extractor: String? = null,
    @SerialName("video_id") val videoId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val position: Int? = null,
    val preset: String? = null,
    val kind: String = "",
    val title: String = "",
    val filename: String = "",
    val status: String = "",
    val progress: Int = 0,
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("speed_bps") val speedBps: Long = 0,
    @SerialName("eta_seconds") val etaSeconds: Int? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("uploaded_bytes") val uploadedBytes: Long = 0,
    @SerialName("upload_speed_bps") val uploadSpeedBps: Long = 0,
    @SerialName("peers_connected") val peersConnected: Int = 0,
    val files: List<FileDto>? = null,
    val positions: List<PositionDto>? = null,
    @SerialName("entry_counts") val entryCounts: EntryCountsDto? = null,
    val folder: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val attempts: Int = 0,
    @SerialName("max_attempts") val maxAttempts: Int? = null,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class FileDto(
    val index: Int = 0,
    val path: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val selected: Boolean = true,
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
)

@Serializable
data class PositionDto(
    @SerialName("file_index") val fileIndex: Int = 0,
    @SerialName("position_seconds") val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    val watched: Boolean = false,
)

@Serializable
data class EntryCountsDto(
    val total: Int = 0,
    val complete: Int = 0,
    val active: Int = 0,
    val downloading: Int = 0,
    val paused: Int = 0,
    val failed: Int = 0,
    val watched: Int? = null,
)

@Serializable
data class SummaryDto(
    val all: Int = 0,
    val downloading: Int = 0,
    val seeding: Int = 0,
    val completed: Int = 0,
)

/** A `disk` frame: the download volume's size, free space, and the floor the API keeps. */
@Serializable
data class DiskDto(
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("free_bytes") val freeBytes: Long = 0,
    @SerialName("min_free_bytes") val minFreeBytes: Long = 0,
)

/** A `progress` frame: only what moved. An absent field means unchanged, never zero. */
@Serializable
data class ProgressDto(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    val progress: Int? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("speed_bps") val speedBps: Long? = null,
    @SerialName("eta_seconds") val etaSeconds: Int? = null,
)

@Serializable
data class PageMetaDto(
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 0,
    val total: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
data class BulkResultDto(
    val affected: Int = 0,
)

/** `POST /extract` for one video or song. */
@Serializable
data class ExtractMediaDto(
    val type: String = "media",
    val extractor: String = "",
    val id: String = "",
    val title: String = "",
    val uploader: String = "",
    val duration: Int = 0,
    val thumbnail: String = "",
    @SerialName("webpage_url") val webpageUrl: String = "",
    val presets: List<String> = emptyList(),
    @SerialName("playlist_url") val playlistUrl: String? = null,
)

/** `POST /extract` for a playlist, or a channel and its tabs. */
@Serializable
data class PlaylistDto(
    val type: String = "playlist",
    val extractor: String = "",
    val id: String = "",
    val title: String = "",
    val uploader: String = "",
    @SerialName("webpage_url") val webpageUrl: String = "",
    val count: Int? = null,
    @SerialName("channel_tab") val channelTab: Boolean = false,
    val tabs: List<TabDto> = emptyList(),
)

@Serializable
data class TabDto(
    val name: String = "",
    val url: String = "",
)
