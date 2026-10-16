package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.Mirror

/** One run, after the runner stored the plan and opened the partials. It keeps its own copy of the file list. */
class Transfer(
    val download: Download,
    val mirror: Mirror,
    val probe: ProbeResult,
    files: List<TransferFile>,
) {
    val files: List<TransferFile> = files.toList()

    override fun equals(other: Any?): Boolean =
        other is Transfer && download == other.download && mirror == other.mirror && probe == other.probe && files == other.files

    override fun hashCode(): Int = listOf(download, mirror, probe, files).hashCode()

    override fun toString(): String = "Transfer(download=$download, mirror=$mirror, probe=$probe, files=$files)"
}
