package dev.anydm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One item of the API's list: a download (`DownloadSchema`) or a collection (`CollectionSchema`), by `type`. */
@Serializable
data class TaskDto(
    val type: String = "download",
    val id: String,
    @SerialName("source_url") val sourceUrl: String = "",
    val platform: String = "",
    @SerialName("media_kind") val mediaKind: String = "",
    /** A collection's kind: `playlist` or `channel`. */
    val kind: String = "",
    val title: String = "",
    val status: String = "",
    val progress: Int = 0,
    @SerialName("collection_id") val collectionId: String? = null,
    val position: Int? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    val live: LiveDto = LiveDto(),
    val site: SiteDto? = null,
    val torrent: TorrentDto? = null,
    val files: List<FileDto> = emptyList(),
    val folder: String? = null,
    val extractor: String? = null,
    val preset: String? = null,
    val counts: CountsDto? = null,
    /** A collection's: the sum of its videos' live speeds. */
    @SerialName("speed_bps") val speedBps: Long = 0,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val attempts: Int = 0,
    @SerialName("max_attempts") val maxAttempts: Int? = null,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

/** A download's numbers that live only in memory on the API: speed, ETA, and a torrent's swarm. */
@Serializable
data class LiveDto(
    @SerialName("speed_bps") val speedBps: Long = 0,
    @SerialName("eta_seconds") val etaSeconds: Int? = null,
    @SerialName("upload_speed_bps") val uploadSpeedBps: Long = 0,
    val peers: Int = 0,
)

@Serializable
data class SiteDto(
    val extractor: String = "",
    @SerialName("video_id") val videoId: String = "",
    val preset: String? = null,
)

@Serializable
data class TorrentDto(
    @SerialName("info_hash") val infoHash: String = "",
    @SerialName("uploaded_bytes") val uploadedBytes: Long = 0,
)

/** One file of a download. A site or direct download has exactly one, at index 0. */
@Serializable
data class FileDto(
    val index: Int = 0,
    val path: String = "",
    @SerialName("size") val sizeBytes: Long = 0,
    val selected: Boolean = true,
    @SerialName("downloaded_bytes") val downloadedBytes: Long = 0,
    @SerialName("mime_type") val mimeType: String? = null,
    val playback: PlaybackDto? = null,
)

/** Where a file was left in the player. */
@Serializable
data class PlaybackDto(
    @SerialName("position_seconds") val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    val watched: Boolean = false,
)

/** How a collection's videos stand. */
@Serializable
data class CountsDto(
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
    /** A collection's video: its collection. */
    @SerialName("collection_id") val collectionId: String? = null,
    val progress: Int? = null,
    @SerialName("downloaded_bytes") val downloadedBytes: Long? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    val live: LiveDto? = null,
    /** A torrent's files that moved: index and bytes. */
    val files: List<FileProgressDto>? = null,
)

@Serializable
data class FileProgressDto(
    val index: Int,
    @SerialName("downloaded_bytes") val downloadedBytes: Long,
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
