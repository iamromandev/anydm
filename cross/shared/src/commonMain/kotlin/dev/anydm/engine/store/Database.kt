package dev.anydm.engine.store

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.AudioChannels
import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Container
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.HdrChoice
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.core.QualityPreset
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.SubtitleFormat
import dev.anydm.engine.core.SubtitleMode
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.store.db.AnydmDb
import kotlin.enums.EnumEntries
import kotlin.time.Instant
import dev.anydm.engine.store.db.Attempt as AttemptRow
import dev.anydm.engine.store.db.Category as CategoryRow
import dev.anydm.engine.store.db.Collection_item as CollectionItemRow
import dev.anydm.engine.store.db.Download as DownloadRow
import dev.anydm.engine.store.db.Extraction as ExtractionRow
import dev.anydm.engine.store.db.File_ as FileRow
import dev.anydm.engine.store.db.Format as FormatRow
import dev.anydm.engine.store.db.History as HistoryRow
import dev.anydm.engine.store.db.Mirror as MirrorRow
import dev.anydm.engine.store.db.Profile as ProfileRow
import dev.anydm.engine.store.db.Proxy as ProxyRow
import dev.anydm.engine.store.db.Request_context as RequestContextRow
import dev.anydm.engine.store.db.Schedule_rule as ScheduleRuleRow
import dev.anydm.engine.store.db.Segment as SegmentRow
import dev.anydm.engine.store.db.Tool as ToolRow
import dev.anydm.engine.store.db.Torrent as TorrentRow

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
        categoryAdapter =
            CategoryRow.Adapter(
                idAdapter = IdAdapter(::CategoryId, CategoryId::value),
                priorityAdapter = IntAdapter("category.priority"),
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
                playlist_indexAdapter = IntAdapter("download.playlist_index"),
                selection_limitAdapter = IntAdapter("download.selection_limit"),
                start_atAdapter = InstantAdapter,
                proxyAdapter = ProxyChoiceAdapter("download.proxy"),
            ),
        collection_itemAdapter =
            CollectionItemRow.Adapter(
                collection_idAdapter = downloadId,
                child_idAdapter = downloadId,
                playlist_indexAdapter = IntAdapter("collection_item.playlist_index"),
                durationAdapter = IntAdapter("collection_item.duration"),
            ),
        torrentAdapter = TorrentRow.Adapter(download_idAdapter = downloadId),
        profileAdapter =
            ProfileRow.Adapter(
                idAdapter = IdAdapter(::ProfileId, ProfileId::value),
                category_idAdapter = IdAdapter(::CategoryId, CategoryId::value),
                proxyAdapter = ProxyChoiceAdapter("profile.proxy"),
            ),
        proxyAdapter =
            ProxyRow.Adapter(
                idAdapter = IdAdapter(::ProxyId, ProxyId::value),
                typeAdapter = EnumNameAdapter("proxy.type", ProxyType.entries),
                portAdapter = IntAdapter("proxy.port"),
            ),
        historyAdapter =
            HistoryRow.Adapter(
                download_idAdapter = downloadId,
                finished_atAdapter = InstantAdapter,
            ),
        extractionAdapter = ExtractionRow.Adapter(download_idAdapter = downloadId, extracted_atAdapter = InstantAdapter),
        formatAdapter =
            FormatRow.Adapter(
                download_idAdapter = downloadId,
                presetAdapter = EnumNameAdapter("format.preset", QualityPreset.entries),
                heightAdapter = IntAdapter("format.height"),
                fpsAdapter = IntAdapter("format.fps"),
                hdrAdapter = EnumNameAdapter("format.hdr", HdrChoice.entries),
                audio_onlyAdapter = EnumNameAdapter("format.audio_only", AudioFormat.entries),
                containerAdapter = EnumNameAdapter("format.container", Container.entries),
                audio_channelsAdapter = EnumNameAdapter("format.audio_channels", AudioChannels.entries),
                sub_modeAdapter = EnumNameAdapter("format.sub_mode", SubtitleMode.entries),
                sub_convertAdapter = EnumNameAdapter("format.sub_convert", SubtitleFormat.entries),
                sb_modeAdapter = EnumNameAdapter("format.sb_mode", SponsorBlockMode.entries),
            ),
        toolAdapter = ToolRow.Adapter(checked_atAdapter = InstantAdapter),
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
        schedule_ruleAdapter =
            ScheduleRuleRow.Adapter(
                idAdapter = IdAdapter(::ScheduleRuleId, ScheduleRuleId::value),
                start_minuteAdapter = IntAdapter("schedule_rule.start_minute"),
                end_minuteAdapter = IntAdapter("schedule_rule.end_minute"),
            ),
        request_contextAdapter = RequestContextRow.Adapter(download_idAdapter = downloadId),
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

/** A [ProxyChoice] as its text (`INHERIT`, `DIRECT`, `SYSTEM`, `PROXY:<id>`). */
internal class ProxyChoiceAdapter(
    private val column: String,
) : ColumnAdapter<ProxyChoice, String> {
    override fun decode(databaseValue: String): ProxyChoice =
        ProxyChoice.decode(databaseValue)
            ?: throw IncompatibleStoreException("$column holds '$databaseValue', which this version of anydm doesn't know")

    override fun encode(value: ProxyChoice): String = value.encode()
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
