package dev.anydm.engine.store

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.store.db.AnydmDb
import kotlin.enums.EnumEntries
import kotlin.time.Instant
import dev.anydm.engine.store.db.Attempt as AttemptRow
import dev.anydm.engine.store.db.Download as DownloadRow
import dev.anydm.engine.store.db.File_ as FileRow
import dev.anydm.engine.store.db.Mirror as MirrorRow
import dev.anydm.engine.store.db.Segment as SegmentRow

internal fun createDatabase(driver: SqlDriver): AnydmDb {
    val downloadId = IdAdapter(::DownloadId, DownloadId::value)
    val fileId = IdAdapter(::FileId, FileId::value)
    val mirrorId = IdAdapter(::MirrorId, MirrorId::value)
    return AnydmDb(
        driver = driver,
        attemptAdapter =
            AttemptRow.Adapter(
                idAdapter = IdAdapter(::AttemptId, AttemptId::value),
                download_idAdapter = downloadId,
                mirror_idAdapter = mirrorId,
                started_atAdapter = InstantAdapter,
                ended_atAdapter = InstantAdapter,
                error_classAdapter = EnumNameAdapter("attempt.error_class", ErrorClass.entries),
            ),
        downloadAdapter =
            DownloadRow.Adapter(
                idAdapter = downloadId,
                kindAdapter = EnumNameAdapter("download.kind", DownloadKind.entries),
                statusAdapter = EnumNameAdapter("download.status", DownloadStatus.entries),
                parent_idAdapter = downloadId,
                collectionAdapter = EnumNameAdapter("download.collection", CollectionKind.entries),
                category_idAdapter = IdAdapter(::CategoryId, CategoryId::value),
                profile_idAdapter = IdAdapter(::ProfileId, ProfileId::value),
                priorityAdapter = IntAdapter("download.priority"),
                attempt_countAdapter = IntAdapter("download.attempt_count"),
                next_attempt_atAdapter = InstantAdapter,
                queued_atAdapter = InstantAdapter,
            ),
        file_Adapter =
            FileRow.Adapter(
                idAdapter = fileId,
                download_idAdapter = downloadId,
                file_indexAdapter = IntAdapter("file.file_index"),
            ),
        mirrorAdapter =
            MirrorRow.Adapter(
                idAdapter = mirrorId,
                download_idAdapter = downloadId,
                positionAdapter = IntAdapter("mirror.position"),
            ),
        segmentAdapter =
            SegmentRow.Adapter(
                idAdapter = IdAdapter(::SegmentId, SegmentId::value),
                file_idAdapter = fileId,
            ),
    )
}

/** A value-class id as its Long. */
internal class IdAdapter<T : Any>(
    private val wrap: (Long) -> T,
    private val unwrap: (T) -> Long,
) : ColumnAdapter<T, Long> {
    override fun decode(databaseValue: Long): T = wrap(databaseValue)

    override fun encode(value: T): Long = unwrap(value)
}

/** An enum by name, never by ordinal. A name this version doesn't know is an error, not a guess. */
internal class EnumNameAdapter<T : Enum<T>>(
    private val column: String,
    entries: EnumEntries<T>,
) : ColumnAdapter<T, String> {
    private val byName = entries.associateBy { it.name }

    override fun decode(databaseValue: String): T =
        byName[databaseValue]
            ?: throw IncompatibleStoreException("$column holds '$databaseValue', which this version of anydm doesn't know")

    override fun encode(value: T): String = value.name
}

/** Epoch milliseconds; anything finer is dropped. */
internal object InstantAdapter : ColumnAdapter<Instant, Long> {
    override fun decode(databaseValue: Long): Instant = Instant.fromEpochMilliseconds(databaseValue)

    override fun encode(value: Instant): Long = value.toEpochMilliseconds()
}

internal class IntAdapter(
    private val column: String,
) : ColumnAdapter<Int, Long> {
    override fun decode(databaseValue: Long): Int =
        if (databaseValue in Int.MIN_VALUE..Int.MAX_VALUE) {
            databaseValue.toInt()
        } else {
            throw IncompatibleStoreException("$column holds $databaseValue, which doesn't fit an Int")
        }

    override fun encode(value: Int): Long = value.toLong()
}
