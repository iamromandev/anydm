package dev.anydm.desktop

import dev.anydm.engine.http.MAX_CONNECTIONS
import dev.anydm.engine.http.Span
import dev.anydm.engine.torrent.PieceSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Version 2 adds each row's connections and ranges; version 1 files are still read (every row on one connection). */
const val DOWNLOADS_VERSION = 2
const val DOWNLOADS_FILE_NAME = "downloads.json"
const val MAX_DOWNLOADS_BYTES = 16 * 1024 * 1024
private const val MAX_DOWNLOADS_TEXT = 4096

/** Tolerates fields a later release may add within the same version. */
val downloadsJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

@Serializable
data class DownloadsFile(
    val version: Int = DOWNLOADS_VERSION,
    /** In list order; the queue is rebuilt on load, as no Queued row survives a restart. */
    val downloads: List<DownloadDto> = emptyList(),
)

@Serializable
data class SampleFileDto(
    val name: String,
    val bytes: Long,
)

/** A byte range of a segmented download, [end] inclusive, of which the first [done] bytes are written. */
@Serializable
data class RangeDto(
    val start: Long,
    val end: Long,
    val done: Long,
)

@Serializable
data class DownloadDto(
    /** A row without an id or a name is an unusable row (dropped on load), not an unreadable file. */
    val id: String = "",
    val url: String? = null,
    val name: String = "",
    /** Null means the default folder. */
    val folder: String? = null,
    /** Null means the server did not say ([UNKNOWN_SIZE]). */
    val total: Long? = null,
    val done: Long = 0,
    val status: DownloadStatus,
    val error: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val kind: DownloadKind = DownloadKind.Direct,
    val files: List<SampleFileDto>? = null,
    /** 1..16 ([MAX_CONNECTIONS]); a version 1 row has none and reads as 1. */
    val connections: Int = 1,
    /** In file order; null for a download that is not segmented (yet). */
    val ranges: List<RangeDto>? = null,
    /** The identity of the `.part` file the [ranges] belong to; a row with ranges and none has ranges that are not trusted. */
    val partId: String? = null,
    /** A real torrent's info hash, 40 lowercase hex digits; a row from before torrents were real has none. */
    val infoHash: String? = null,
    /** A real torrent's verified pieces, a bitfield in base64 ([PieceSet.toBase64]); none until it first starts. */
    val pieces: String? = null,
    /** How many pieces [pieces] is for: the bitfield alone cannot say (its last byte may have spare bits). */
    val pieceCount: Int? = null,
    /** A magnet row made by this build, a simulation: it keeps pausing and resuming as one. A torrent row with no info hash and no mark is older. */
    val sim: Boolean = false,
)

fun toDownloadsFile(list: List<DownloadItem>): DownloadsFile =
    DownloadsFile(
        downloads =
            list.map {
                DownloadDto(
                    it.id,
                    it.url,
                    it.name,
                    it.folder,
                    if (it.totalBytes == UNKNOWN_SIZE) null else it.totalBytes,
                    it.doneBytes,
                    it.status,
                    it.error,
                    it.etag,
                    it.lastModified,
                    it.kind,
                    it.files?.map { f -> SampleFileDto(f.name, f.bytes) },
                    it.connections,
                    it.spans?.map { r -> RangeDto(r.start, r.end, r.done) },
                    it.partId,
                    it.infoHash,
                    it.pieces?.toBase64(),
                    it.pieces?.size,
                    it.simulated,
                )
            },
    )

/** R15: a row that was Downloading or Queued comes back Paused, and nothing is moving. Sizes on disk are applied by the caller. */
fun fromDownloadsFile(file: DownloadsFile): List<DownloadItem> =
    usableRows(file).map {
        DownloadItem(
            id = it.id,
            name = it.name,
            totalBytes = it.total ?: UNKNOWN_SIZE,
            doneBytes = it.done,
            bytesPerSec = 0,
            status =
                if (it.status == DownloadStatus.Downloading ||
                    it.status == DownloadStatus.Queued
                ) {
                    DownloadStatus.Paused
                } else {
                    it.status
                },
            kind = it.kind,
            folder = it.folder,
            files = it.files?.map { f -> SampleFile(f.name, f.bytes) },
            url = it.url,
            error = it.error,
            etag = it.etag,
            lastModified = it.lastModified,
            connections = it.connections.coerceIn(1, MAX_CONNECTIONS),
            spans = it.ranges?.map { r -> Span(r.start, r.end, r.done) },
            partId = it.partId,
            infoHash = it.infoHash,
            pieces = claimOf(it),
            simulated = it.sim,
        )
    }

/** The most pieces a saved claim may be for: a 10 MiB `.torrent` holds at most this many 20-byte hashes. */
private const val MAX_SAVED_PIECES = 10 * 1024 * 1024 / 20

/**
 * The pieces a saved torrent row claims. A claim that does not decode for its count is an empty one, never null: null would make
 * the engine take the row for one that never ran and pick new file names, leaving the files it made before behind.
 */
private fun claimOf(d: DownloadDto): PieceSet? {
    val text = d.pieces ?: return null
    val count = d.pieceCount?.takeIf { it in 0..MAX_SAVED_PIECES } ?: return PieceSet.empty(0)
    return PieceSet.fromBase64(count, text) ?: PieceSet.empty(count)
}

/** Any case of the scheme, any length of link (signed links are long): the file's own size cap is the limit. */
private fun isHttpUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/** Null when the row is usable, else the first reason it is not (never quoting the file). */
private fun rowProblem(d: DownloadDto): String? =
    when {
        d.id.isBlank() || d.id.length > MAX_DOWNLOADS_TEXT -> "bad download id"
        d.name.isBlank() || d.name.length > MAX_DOWNLOADS_TEXT -> "bad download name"
        d.folder != null && !isUsableFolder(d.folder) -> "bad download folder"
        d.done < 0 || (d.total != null && d.total < 0) -> "negative download size"
        d.kind == DownloadKind.Direct && (d.url == null || !isHttpUrl(d.url)) -> "bad download link"
        d.infoHash != null && !isInfoHashHex(d.infoHash) -> "bad info hash"
        d.ranges != null && !rangesUsable(d.ranges, d.total) -> "bad download ranges"
        else -> null
    }

/**
 * Ranges that partition the file: some, in file order, the first at byte 0 and each starting right after the one before, each
 * with `done` inside its length, and the last ending at the [total] when it is known. A gap or a short list would leave bytes
 * that nothing fetches, and the file would be renamed as complete.
 */
private fun rangesUsable(
    ranges: List<RangeDto>,
    total: Long?,
): Boolean {
    if (ranges.isEmpty()) return false
    var next = 0L
    for (r in ranges) {
        if (r.start != next || r.end < r.start || r.done < 0 || r.done > r.end - r.start + 1) return false
        next = r.end + 1
    }
    return total == null || next == total
}

/** The rows worth keeping: one unusable row (or a repeated id) is left out, so it cannot take the whole list with it. */
fun usableRows(f: DownloadsFile): List<DownloadDto> {
    val seen = HashSet<String>()
    return f.downloads.filter { rowProblem(it) == null && seen.add(it.id) }
}

/** Null when the file is usable, else why not. Only what makes the whole file untrustworthy counts; a bad row is dropped by [usableRows]. */
fun validateDownloads(f: DownloadsFile): String? =
    when {
        f.version > DOWNLOADS_VERSION -> "newer downloads version ${f.version}"
        f.version < 1 -> "unknown downloads version ${f.version}"
        else -> null
    }

typealias DownloadsLoad = JsonLoad<DownloadsFile>

/** Both calls block on the disk: call them off the UI thread. */
interface DownloadsStore {
    fun load(): DownloadsLoad

    fun save(file: DownloadsFile): SaveResult
}

/** `downloads.json` in [dir]; see [JsonFileStore] for the write and recovery rules. */
class FileDownloadsStore(
    dir: Path,
    files: FileOps = RealFileOps,
) : DownloadsStore {
    private val store =
        JsonFileStore(dir, DOWNLOADS_FILE_NAME, DownloadsFile.serializer(), downloadsJson, ::validateDownloads, MAX_DOWNLOADS_BYTES, files)

    override fun load(): DownloadsLoad = store.load()

    override fun save(file: DownloadsFile): SaveResult = store.save(file)
}

/** Downloads kept in memory: the default for [AppStores], so a test or a preview never writes a file. */
class InMemoryDownloadsStore(
    initial: DownloadsFile? = null,
) : DownloadsStore {
    @Volatile
    private var file: DownloadsFile? = initial
    val saves = CopyOnWriteArrayList<DownloadsFile>()

    override fun load(): DownloadsLoad = file?.let { JsonLoad.Loaded(it) } ?: JsonLoad.Missing

    override fun save(file: DownloadsFile): SaveResult {
        this.file = file
        saves.add(file)
        return SaveResult.Ok
    }
}
