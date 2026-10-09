package dev.anydm.api

import dev.anydm.model.PlaylistDto

/**
 * One line of an error's `details`: for a failed search, which source and why; for a refused
 * duplicate, the download's id, its title and (in [fields]) its status.
 */
data class ErrorDetail(
    val subject: String?,
    val description: String?,
    val fields: List<String> = emptyList(),
)

/** The download an add was refused for, because the list already holds it. */
data class Duplicate(
    val id: String,
    val title: String,
    val status: String,
)

/** What the API said went wrong: its own message, status code, and error type (`unsupported_url`, ...). */
open class ApiException(
    message: String,
    val code: Int?,
    val type: String?,
    val details: List<ErrorDetail> = emptyList(),
) : Exception(message) {
    /**
     * The download a 409 names, or null for any other failure: a 409 with no such detail (pausing a
     * paused download) is not a duplicate.
     */
    val duplicate: Duplicate?
        get() {
            if (code != 409) return null
            val detail = details.firstOrNull()
            val id = detail?.subject ?: return null
            return Duplicate(id, detail.description.orEmpty(), detail.fields.firstOrNull().orEmpty())
        }
}

/** The server wants a key this client doesn't have, or refused the one it has. */
class Unauthorized(
    message: String,
) : ApiException(message, 401, null)

/** A playlist or channel link: v1 leaves choosing its videos to the web app. */
class PlaylistLink(
    val listing: PlaylistDto,
) : ApiException("Playlists are added from the web app for now", null, "playlist_link")
