package dev.anydm.engine.os

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DuplicateFound

/** What [dev.anydm.engine.Engine.addLink] made of a link (OSI-07). */
sealed interface AddLinkResult {
    /** The link is queued as [download]; an `IncomingLink` event said so. */
    data class Added(
        val download: Download,
    ) : AddLinkResult

    /** Not a link this engine takes: not a web, FTP or torrent address, or a torrent file that is no file. Nothing was added. */
    data object NotALink : AddLinkResult

    /** The link is already in the list or the history, or its file is already there (FIL-05). Nothing was added. */
    data class Duplicate(
        val found: DuplicateFound,
    ) : AddLinkResult

    /** The link was understood but could not be added, and why. Nothing was added. */
    data class Failed(
        val reason: String,
    ) : AddLinkResult
}
