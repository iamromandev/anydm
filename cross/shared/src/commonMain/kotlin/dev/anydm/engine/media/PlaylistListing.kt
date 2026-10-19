package dev.anydm.engine.media

import dev.anydm.engine.core.CollectionKind

/** The most entries one listing may hold; a larger playlist is refused rather than turned into thousands of jobs. */
internal const val LISTING_CAP = 5000

/** One video of a playlist, as the flat listing names it: [extractor] and [id] identify it, [url] is its page. */
data class PlaylistEntry(
    val extractor: String,
    val id: String,
    val url: String,
    val title: String?,
    val duration: Int?,
    /** Its place in the playlist as listed, counting entries that could not be used: 1 is the first. */
    val position: Int,
)

/** A playlist or channel (MED-16). [dropped] counts the entries that could not be used (private, repeated, no address). */
data class PlaylistListing(
    val kind: CollectionKind,
    val title: String,
    val uploader: String?,
    val sourceUrl: String,
    val entries: List<PlaylistEntry>,
    val dropped: Int,
)

sealed interface PlaylistParse {
    data class Ok(
        val listing: PlaylistListing,
    ) : PlaylistParse

    /** The page is one video (or not a list at all). */
    data object NotAPlaylist : PlaylistParse

    data class Rejected(
        val reason: String,
    ) : PlaylistParse
}
