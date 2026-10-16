package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId

/**
 * A PlanStore in memory that behaves like the SQL one in what the step runner relies on: ids are never reused, files
 * come back by index and segments by start, a watermark for a removed segment is skipped, an out-of-range watermark
 * throws (through Segment) and then none of the batch is applied, and the replace calls throw for a parent that doesn't
 * exist. Downloads exist once [register]ed (there is no DownloadStore behind it). It is not the SQL store: there are no
 * transactions or cancellation rules here, and it is single-threaded (runTest).
 */
internal class InMemoryPlanStore : PlanStore {
    private var lastId = 0L
    private val downloads = mutableSetOf<DownloadId>()
    private val fileRows = mutableMapOf<DownloadId, List<File>>()
    private val segmentRows = mutableMapOf<FileId, List<Segment>>()

    /** Makes [id] a download that files can belong to. */
    fun register(id: DownloadId) {
        downloads += id
    }

    override suspend fun replaceFiles(
        id: DownloadId,
        files: List<NewFile>,
    ): List<File> {
        check(id in downloads) { "download ${id.value} does not exist" }
        require(files.map { it.index }.toSet().size == files.size) { "file indexes must differ" }
        fileRows[id].orEmpty().forEach { segmentRows.remove(it.id) }
        return files
            .sortedBy { it.index }
            .map { File(FileId(++lastId), id, it.index, it.path, it.size, it.selected) }
            .also { fileRows[id] = it }
    }

    override suspend fun files(id: DownloadId): List<File> = fileRows[id].orEmpty()

    override suspend fun replaceSegments(
        fileId: FileId,
        segments: List<NewSegment>,
    ): List<Segment> {
        check(fileRows.values.any { rows -> rows.any { it.id == fileId } }) { "file ${fileId.value} does not exist" }
        return segments
            .sortedBy { it.start }
            .map { Segment(SegmentId(++lastId), fileId, it.start, it.end) }
            .also { segmentRows[fileId] = it }
    }

    override suspend fun segments(fileId: FileId): List<Segment> = segmentRows[fileId].orEmpty()

    override suspend fun saveWatermarks(watermarks: Map<SegmentId, Long>) {
        // Every new row is built (and so checked) before any is stored: all or nothing.
        val updated = segmentRows.mapValues { (_, rows) -> rows.map { s -> watermarks[s.id]?.let { s.copy(watermark = it) } ?: s } }
        segmentRows.putAll(updated)
    }

    /** Bytes the saved watermarks vouch for, over every file. */
    fun written(): Long = segmentRows.values.sumOf { rows -> rows.sumOf { it.written } }
}
