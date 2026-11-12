package dev.anydm.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

const val DOWNLOADS_VERSION = 1
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
        )
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
        else -> null
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
        f.version < DOWNLOADS_VERSION -> "unknown downloads version ${f.version}"
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
