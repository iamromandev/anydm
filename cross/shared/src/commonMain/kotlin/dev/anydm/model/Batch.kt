package dev.anydm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a batch is made of: a pasted list, or one pattern such as `img[001-120].png`. */
enum class BatchKind { LIST, PATTERN }

/** What `POST /download/batch/preview` names, without adding anything. */
@Serializable
data class BatchPreview(
    val count: Int = 0,
    val urls: List<String> = emptyList(),
)

/** One link's answer from `POST /download/batch`, as sent. */
@Serializable
data class BatchItemDto(
    val url: String = "",
    val result: String = "",
    @SerialName("download_id") val downloadId: String? = null,
    val message: String? = null,
)

enum class BatchOutcome { ADDED, DUPLICATE, ERROR }

/** One link's answer: [downloadId] is the new download, or for a duplicate the one that already has it. */
data class BatchItem(
    val url: String,
    val outcome: BatchOutcome,
    val downloadId: String?,
    val message: String,
)

/** An outcome this client doesn't know reads as an error, never as added. */
fun BatchItemDto.toItem(): BatchItem =
    BatchItem(
        url = url,
        outcome =
            when (result) {
                "added" -> BatchOutcome.ADDED
                "duplicate" -> BatchOutcome.DUPLICATE
                else -> BatchOutcome.ERROR
            },
        downloadId = downloadId,
        message = message.orEmpty(),
    )
