package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.protocol.StepResult

/** What [dev.anydm.engine.Engine.probeLink] found out about a link without adding it (OSI-02). */
sealed interface LinkProbeResult {
    data class Probed(
        /** The link as it would be added: an `anydm://` link unwrapped, a path to a torrent as a `file:///` address. */
        val link: String,
        val kind: DownloadKind,
        val name: String?,
        val size: Long?,
        val rangesSupported: Boolean,
        /** Adding it now would be refused as a duplicate (FIL-05). */
        val duplicate: Boolean,
    ) : LinkProbeResult

    /** Not a link this engine takes. */
    data object NotALink : LinkProbeResult

    /** The link could not be read: it does not answer, it needs a login, or it is not there. */
    data class Failed(
        val failure: StepResult.Failed,
    ) : LinkProbeResult

    /** Nothing here can look at this kind of link. */
    data object Unavailable : LinkProbeResult
}
