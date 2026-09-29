package dev.anydm.api

import dev.anydm.model.PlaylistDto

/** One line of an error's `details`: for a failed search, which source and why. */
data class ErrorDetail(
    val subject: String?,
    val description: String?,
)

/** What the API said went wrong: its own message, status code, and error type (`unsupported_url`, ...). */
open class ApiException(
    message: String,
    val code: Int?,
    val type: String?,
    val details: List<ErrorDetail> = emptyList(),
) : Exception(message)

/** The server wants a key this client doesn't have, or refused the one it has. */
class Unauthorized(
    message: String,
) : ApiException(message, 401, null)

/** A playlist or channel link: v1 leaves choosing its videos to the web app. */
class PlaylistLink(
    val listing: PlaylistDto,
) : ApiException("Playlists are added from the web app for now", null, "playlist_link")
