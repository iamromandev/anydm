package dev.anydm.engine.store

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.DEFAULT_SPONSORBLOCK_CATEGORIES
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.EmbedChoice
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.SponsorBlockChoice
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.core.SubtitleMode
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import dev.anydm.engine.store.db.Attempt as AttemptRow
import dev.anydm.engine.store.db.Download as DownloadRow
import dev.anydm.engine.store.db.File_ as FileRow
import dev.anydm.engine.store.db.Mirror as MirrorRow
import dev.anydm.engine.store.db.Segment as SegmentRow

internal class SqlDownloadStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : DownloadStore {
    private val downloadQueries = database.downloadQueries
    private val mirrorQueries = database.mirrorQueries
    private val contextQueries = database.requestContextQueries
    private val formatQueries = database.formatQueries
    private val fileQueries = database.fileQueries
    private val segmentQueries = database.segmentQueries
    private val attemptQueries = database.attemptQueries
    private val collectionItemQueries = database.collectionItemQueries
    private val torrentQueries = database.torrentQueries

    // One transaction per call: the JDBC driver opens a connection per statement outside one, and
    // last_insert_rowid() only means something on the connection that inserted. The block never suspends.
    private suspend fun <T> inTransaction(block: () -> T): T = withContext(io) { database.transactionWithResult { block() } }

    // A write that has been asked for is made, even if its caller is cancelled while it waits for the database: a
    // pause or quit saving watermarks from a finally block (FND-09) must still checkpoint. The caller may lose the
    // result, not the write.
    private val writeContext = io + NonCancellable

    private suspend fun <T> writing(block: () -> T): T = withContext(writeContext) { database.transactionWithResult { block() } }

    override suspend fun add(
        new: NewDownload,
        queuedAt: Instant?,
    ): Download = writing { insertDownload(new, queuedAt) }

    override suspend fun addCollection(
        parent: NewDownload,
        children: List<NewDownload>,
        items: List<NewItem>,
        queuedAt: Instant?,
    ): Pair<Download, List<Download>> {
        require(children.size == items.size) { "every child needs its item: ${children.size} children, ${items.size} items" }
        return writing {
            val collection = insertDownload(parent, queuedAt)
            collection to insertChildren(collection.id, children, items, queuedAt)
        }
    }

    override suspend fun addChildren(
        parent: DownloadId,
        children: List<NewDownload>,
        items: List<NewItem>,
        queuedAt: Instant?,
    ): List<Download> {
        require(children.size == items.size) { "every child needs its item: ${children.size} children, ${items.size} items" }
        return writing {
            if (downloadQueries.selectById(parent).executeAsOneOrNull() ==
                null
            ) {
                emptyList()
            } else {
                insertChildren(parent, children, items, queuedAt)
            }
        }
    }

    // Inside a transaction.
    private fun insertChildren(
        parent: DownloadId,
        children: List<NewDownload>,
        items: List<NewItem>,
        queuedAt: Instant?,
    ): List<Download> =
        children.mapIndexed { i, child ->
            val added = insertDownload(child.copy(parentId = parent), queuedAt)
            val item = items[i]
            collectionItemQueries.insert(parent, item.extractor, item.videoId, added.id, item.playlistIndex, item.title, item.duration)
            added
        }

    // Inside a transaction.
    private fun insertDownload(
        new: NewDownload,
        queuedAt: Instant?,
    ): Download {
        downloadQueries.insert(
            kind = new.kind,
            status = DownloadStatus.PENDING,
            parent_id = new.parentId,
            collection = new.collection,
            category_id = new.categoryId,
            profile_id = new.profileId,
            save_folder = new.saveFolder,
            file_name = new.fileName,
            size = new.size,
            priority = new.priority,
            speed_limit = new.speedLimit,
            attempt_count = 0,
            next_attempt_at = null,
            pause_reasons = "",
            queued_at = queuedAt,
            source_validator = null,
            checksum = new.checksum?.toString(),
            allow_duplicate = new.allowDuplicate,
            playlist_index = new.playlistIndex,
            source_url = new.sourceUrl,
            selection_range = new.selection?.range,
            selection_limit = new.selection?.limit,
        )
        val download = downloadQueries.selectLastInserted().executeAsOne().toDownload()
        new.mirrors.forEachIndexed { position, url ->
            mirrorQueries.insert(download_id = download.id, url = url, position = position)
        }
        new.context?.let { putContext(download.id, it) }
        new.format?.let { putFormat(download.id, it) }
        new.torrent?.let { torrentQueries.put(download.id, it.metadata, it.priorities.joinToString(","), it.sequential) }
        return download
    }

    override suspend fun get(id: DownloadId): Download? =
        inTransaction { downloadQueries.selectById(id).executeAsOneOrNull()?.toDownload() }

    override suspend fun list(): List<Download> = inTransaction { downloadQueries.selectAll().executeAsList().map { it.toDownload() } }

    override suspend fun list(status: DownloadStatus): List<Download> =
        inTransaction { downloadQueries.selectByStatus(status).executeAsList().map { it.toDownload() } }

    override suspend fun update(download: Download): Boolean =
        writing {
            downloadQueries
                .update(
                    kind = download.kind,
                    status = download.status,
                    parent_id = download.parentId,
                    collection = download.collection,
                    category_id = download.categoryId,
                    profile_id = download.profileId,
                    save_folder = download.saveFolder,
                    file_name = download.fileName,
                    size = download.size,
                    priority = download.priority,
                    speed_limit = download.speedLimit,
                    attempt_count = download.attemptCount,
                    next_attempt_at = download.nextAttemptAt,
                    pause_reasons = encodePauseReasons(download.pauseReasons),
                    queued_at = download.queuedAt,
                    source_validator = download.sourceValidator,
                    checksum = download.checksum?.toString(),
                    allow_duplicate = download.allowDuplicate,
                    playlist_index = download.playlistIndex,
                    source_url = download.sourceUrl,
                    selection_range = download.selection?.range,
                    selection_limit = download.selection?.limit,
                    id = download.id,
                ).value > 0
        }

    override suspend fun children(parent: DownloadId): List<Download> =
        inTransaction { downloadQueries.selectByParent(parent).executeAsList().map { it.toDownload() } }

    override suspend fun remove(id: DownloadId): Boolean = writing { downloadQueries.delete(id).value > 0 }

    override suspend fun context(id: DownloadId): RequestContext? =
        inTransaction {
            contextQueries.selectByDownload(id).executeAsOneOrNull()?.let { row ->
                RequestContext(
                    headers =
                        row.headers.lines().filter { it.isNotEmpty() }.map {
                            it.substringBefore(
                                ": ",
                            ) to it.substringAfter(": ", "")
                        },
                    referrer = row.referrer,
                    userAgent = row.user_agent,
                    cookies = row.cookies,
                )
            }
        }

    override suspend fun format(id: DownloadId): Format? =
        inTransaction {
            formatQueries.selectByDownload(id).executeAsOneOrNull()?.let { row ->
                Format(
                    preset = row.preset,
                    height = row.height,
                    fps = row.fps,
                    hdr = row.hdr,
                    videoCodec = row.video_codec,
                    audioCodec = row.audio_codec,
                    audioOnly = row.audio_only,
                    container = row.container,
                    audioLanguage = row.audio_language,
                    audioChannels = row.audio_channels,
                    subtitles =
                        row.sub_languages?.let { languages ->
                            SubtitleChoice(
                                languages = languages.split(','),
                                includeAuto = row.sub_include_auto,
                                mode = row.sub_mode ?: SubtitleMode.SEPARATE,
                                convertTo = row.sub_convert,
                            )
                        },
                    sponsorBlock =
                        row.sb_mode?.let { mode ->
                            SponsorBlockChoice(mode, row.sb_categories?.split(',') ?: DEFAULT_SPONSORBLOCK_CATEGORIES)
                        },
                    embed = EmbedChoice(row.embed_metadata, row.embed_thumbnail, row.embed_chapters),
                )
            }
        }

    private fun putFormat(
        id: DownloadId,
        format: Format,
    ) {
        formatQueries.put(
            download_id = id,
            preset = format.preset,
            height = format.height,
            fps = format.fps,
            hdr = format.hdr,
            video_codec = format.videoCodec,
            audio_codec = format.audioCodec,
            audio_only = format.audioOnly,
            container = format.container,
            audio_language = format.audioLanguage,
            audio_channels = format.audioChannels,
            sub_languages = format.subtitles?.languages?.joinToString(","),
            sub_include_auto = format.subtitles?.includeAuto ?: false,
            sub_mode = format.subtitles?.mode,
            sub_convert = format.subtitles?.convertTo,
            sb_mode = format.sponsorBlock?.mode,
            sb_categories = format.sponsorBlock?.categories?.joinToString(","),
            embed_metadata = format.embed.metadata,
            embed_thumbnail = format.embed.thumbnail,
            embed_chapters = format.embed.chapters,
        )
    }

    override suspend fun setContext(
        id: DownloadId,
        context: RequestContext?,
    ): Boolean =
        writing {
            if (downloadQueries.selectById(id).executeAsOneOrNull() == null) return@writing false
            if (context == null) contextQueries.delete(id) else putContext(id, context)
            true
        }

    private fun putContext(
        id: DownloadId,
        context: RequestContext,
    ) {
        contextQueries.put(
            download_id = id,
            headers = context.headers.joinToString("\n") { (name, value) -> "$name: $value" },
            referrer = context.referrer,
            user_agent = context.userAgent,
            cookies = context.cookies,
        )
    }

    override suspend fun allMirrors(): List<Mirror> = inTransaction { mirrorQueries.selectAll().executeAsList().map { it.toMirror() } }

    override suspend fun mirrors(id: DownloadId): List<Mirror> =
        inTransaction { mirrorQueries.selectByDownload(id).executeAsList().map { it.toMirror() } }

    override suspend fun updateMirror(mirror: Mirror): Boolean =
        writing { mirrorQueries.update(url = mirror.url, position = mirror.position, id = mirror.id).value > 0 }

    override suspend fun replaceFiles(
        id: DownloadId,
        files: List<NewFile>,
    ): List<File> {
        require(files.map { it.index }.toSet().size == files.size) { "file indexes must differ" }
        return writing {
            // Checked here, not left to the foreign key, so that an empty list can't hide the mistake.
            check(downloadQueries.selectById(id).executeAsOneOrNull() != null) { "download ${id.value} does not exist" }
            fileQueries.deleteByDownload(id)
            files.forEach {
                fileQueries.insert(
                    download_id = id,
                    file_index = it.index,
                    path = it.path,
                    size = it.size,
                    selected = it.selected,
                )
            }
            fileQueries.selectByDownload(id).executeAsList().map { it.toFile() }
        }
    }

    override suspend fun files(id: DownloadId): List<File> =
        inTransaction { fileQueries.selectByDownload(id).executeAsList().map { it.toFile() } }

    override suspend fun addFile(
        id: DownloadId,
        path: String,
        size: Long?,
    ): File =
        writing {
            check(downloadQueries.selectById(id).executeAsOneOrNull() != null) { "download ${id.value} does not exist" }
            val index = fileQueries.nextIndex(id).executeAsOne().toInt()
            fileQueries.insert(download_id = id, file_index = index, path = path, size = size, selected = true)
            fileQueries
                .selectByDownload(id)
                .executeAsList()
                .first { it.file_index == index }
                .toFile()
        }

    override suspend fun removeFile(id: FileId) {
        writing { fileQueries.deleteById(id) }
    }

    override suspend fun setPlacedPath(
        id: FileId,
        path: String,
    ) {
        writing { fileQueries.setPlacedPath(path, id) }
    }

    override suspend fun replaceSegments(
        fileId: FileId,
        segments: List<NewSegment>,
    ): List<Segment> =
        writing {
            check(fileQueries.selectById(fileId).executeAsOneOrNull() != null) { "file ${fileId.value} does not exist" }
            segmentQueries.deleteByFile(fileId)
            segments.forEach {
                segmentQueries.insert(file_id = fileId, start_byte = it.start, end_byte = it.end, watermark = it.start)
            }
            segmentQueries.selectByFile(fileId).executeAsList().map { it.toSegment() }
        }

    override suspend fun segments(fileId: FileId): List<Segment> =
        inTransaction { segmentQueries.selectByFile(fileId).executeAsList().map { it.toSegment() } }

    override suspend fun saveWatermarks(watermarks: Map<SegmentId, Long>) {
        writing { watermarks.forEach { (id, watermark) -> segmentQueries.setWatermark(watermark = watermark, id = id) } }
    }

    override suspend fun addAttempt(new: NewAttempt): Attempt =
        writing {
            attemptQueries.insert(
                download_id = new.downloadId,
                mirror_id = new.mirrorId,
                started_at = new.startedAt,
                ended_at = null,
                error_class = null,
                message = null,
            )
            attemptQueries.selectLastInserted().executeAsOne().toAttempt()
        }

    override suspend fun updateAttempt(attempt: Attempt): Boolean =
        writing {
            attemptQueries
                .update(
                    mirror_id = attempt.mirrorId,
                    started_at = attempt.startedAt,
                    ended_at = attempt.endedAt,
                    error_class = attempt.errorClass,
                    message = attempt.message,
                    id = attempt.id,
                ).value > 0
        }

    override suspend fun attempts(id: DownloadId): List<Attempt> =
        inTransaction { attemptQueries.selectByDownload(id).executeAsList().map { it.toAttempt() } }

    override suspend fun openAttempts(): List<Attempt> =
        inTransaction { attemptQueries.selectOpen().executeAsList().map { it.toAttempt() } }

    private fun DownloadRow.toDownload() =
        Download(
            id = id,
            kind = kind,
            status = status,
            parentId = parent_id,
            collection = collection,
            categoryId = category_id,
            profileId = profile_id,
            saveFolder = save_folder,
            fileName = file_name,
            size = size,
            priority = priority,
            speedLimit = speed_limit,
            attemptCount = attempt_count,
            nextAttemptAt = next_attempt_at,
            pauseReasons = decodePauseReasons(pause_reasons),
            queuedAt = queued_at,
            sourceValidator = source_validator,
            checksum =
                checksum?.let {
                    Checksum.parse(it) ?: throw IncompatibleStoreException("checksum holds '$it', which this version of anydm doesn't know")
                },
            allowDuplicate = allow_duplicate,
            playlistIndex = playlist_index,
            sourceUrl = source_url,
            selection = if (selection_range == null && selection_limit == null) null else ItemSelection(selection_range, selection_limit),
        )

    private fun MirrorRow.toMirror() = Mirror(id = id, downloadId = download_id, url = url, position = position)

    private fun FileRow.toFile() =
        File(
            id = id,
            downloadId = download_id,
            index = file_index,
            path = path,
            size = size,
            selected = selected,
            placedPath = placed_path,
        )

    private fun SegmentRow.toSegment() = Segment(id = id, fileId = file_id, start = start_byte, end = end_byte, watermark = watermark)

    private fun AttemptRow.toAttempt() =
        Attempt(
            id = id,
            downloadId = download_id,
            startedAt = started_at,
            mirrorId = mirror_id,
            endedAt = ended_at,
            errorClass = error_class,
            message = message,
        )
}

private fun encodePauseReasons(reasons: Set<PauseReason>): String = reasons.sortedBy { it.ordinal }.joinToString(",") { it.name }

// A name this version doesn't know is a newer version's reason: read as none, so the job still loads.
private fun decodePauseReasons(stored: String): Set<PauseReason> {
    val known = PauseReason.entries.associateBy { it.name }
    return stored.split(',').mapNotNullTo(mutableSetOf()) { known[it] }
}
