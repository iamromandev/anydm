package dev.anydm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /search/sources`: whether search is on, and who it asks. */
@Serializable
data class SearchSources(
    val enabled: Boolean = false,
    val indexers: List<String> = emptyList(),
)

/** One result. The API leaves null fields out, so each optional field reads as `null`. */
@Serializable
data class FoundTorrent(
    val title: String = "",
    @SerialName("size") val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    /** ISO 8601, as the source dated it. */
    val published: String? = null,
    val category: String = "other",
    @SerialName("info_hash") val infoHash: String? = null,
    val magnet: String? = null,
    /** A `.torrent` on the indexer, fetched through `POST /search/torrent`. */
    val link: String? = null,
    /** The source that copy came from; the server picked it. */
    @SerialName("copy_from") val copyFrom: String = "",
    val indexers: List<String> = emptyList(),
)

@Serializable
data class IndexerError(
    val indexer: String = "",
    val message: String = "",
)

@Serializable
data class SearchAnswer(
    val results: List<FoundTorrent> = emptyList(),
    val errors: List<IndexerError> = emptyList(),
    /** Every source the request went to, whether it answered or failed. */
    val asked: List<String> = emptyList(),
    @SerialName("took_ms") val tookMs: Long = 0,
)

/** `POST /search/torrent`'s answer: one of the two. */
@Serializable
data class FetchedDto(
    val torrent: String? = null,
    val magnet: String? = null,
)
