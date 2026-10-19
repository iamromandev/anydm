package dev.anydm.engine

import dev.anydm.engine.core.Download
import dev.anydm.engine.protocol.StepResult

/** What [Engine.addPlaylist] made of a playlist or channel URL (MED-16). */
sealed interface AddPlaylistResult {
    /** The collection and its children; [skipped] were kept out by the duplicate check, [dropped] had no usable address. */
    data class Added(
        val collection: Download,
        val children: List<Download>,
        val skipped: Int,
        val dropped: Int,
    ) : AddPlaylistResult

    /** The address is one video. */
    data object NotAPlaylist : AddPlaylistResult

    /** The range and limit chose no item. */
    data object EmptySelection : AddPlaylistResult

    /** yt-dlp could not list the page, or the listing was refused (too many items). */
    data class Failed(
        val failure: StepResult.Failed,
    ) : AddPlaylistResult

    /** This engine has no way to list playlists. */
    data object Unavailable : AddPlaylistResult
}
