package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.Mirror

/** The job, and the one [mirror] the caller chose to ask (FND-08). */
data class ProbeRequest(
    val download: Download,
    val mirror: Mirror,
) {
    init {
        require(mirror.downloadId == download.id) { "mirror ${mirror.id.value} isn't download ${download.id.value}'s" }
    }
}
