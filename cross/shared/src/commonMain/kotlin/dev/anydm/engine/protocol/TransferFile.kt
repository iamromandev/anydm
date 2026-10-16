package dev.anydm.engine.protocol

import dev.anydm.engine.core.File
import dev.anydm.engine.core.Segment
import kotlinx.io.files.Path

/**
 * One file of a run: row, segments (watermarks as the run started), the partial (`<name>.<download id>.anydm`) and the
 * final target. It keeps its own copy of the segment list.
 */
class TransferFile(
    val file: File,
    segments: List<Segment>,
    val partial: Path,
    val target: Path,
) {
    val segments: List<Segment> = segments.toList()

    override fun equals(other: Any?): Boolean =
        other is TransferFile && file == other.file && segments == other.segments && partial == other.partial && target == other.target

    override fun hashCode(): Int = listOf(file, segments, partial, target).hashCode()

    override fun toString(): String = "TransferFile(file=$file, segments=$segments, partial=$partial, target=$target)"
}
