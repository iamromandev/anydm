package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.Mirror

/**
 * One run, after the runner stored the plan and opened the partials. It keeps its own copy of the lists. [mirrors] are
 * the job's other mirrors not gone this round, by position, never [mirror] itself: a protocol may fetch from those that
 * serve the same content too (HTTP-12).
 */
class Transfer(
    val download: Download,
    val mirror: Mirror,
    val probe: ProbeResult,
    files: List<TransferFile>,
    mirrors: List<Mirror> = emptyList(),
) {
    val files: List<TransferFile> = files.toList()
    val mirrors: List<Mirror> = mirrors.toList()

    init {
        for (other in this.mirrors) {
            require(other.downloadId == download.id) { "mirror ${other.id.value} isn't download ${download.id.value}'s" }
            require(other.id != mirror.id) { "mirror ${other.id.value} is the transfer's own" }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Transfer &&
            download == other.download &&
            mirror == other.mirror &&
            probe == other.probe &&
            files == other.files &&
            mirrors == other.mirrors

    override fun hashCode(): Int = listOf(download, mirror, probe, files, mirrors).hashCode()

    override fun toString(): String = "Transfer(download=$download, mirror=$mirror, probe=$probe, files=$files, mirrors=$mirrors)"
}
