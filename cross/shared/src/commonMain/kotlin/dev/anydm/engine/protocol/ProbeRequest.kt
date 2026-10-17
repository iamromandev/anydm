package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.RequestContext

/** The job, the one [mirror] the caller chose to ask (FND-08), and what its requests carry besides the URL (HTTP-10). */
data class ProbeRequest(
    val download: Download,
    val mirror: Mirror,
    val context: RequestContext? = null,
) {
    init {
        require(mirror.downloadId == download.id) { "mirror ${mirror.id.value} isn't download ${download.id.value}'s" }
    }
}
