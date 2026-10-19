package dev.anydm.engine.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One progress report of yt-dlp's own downloader (MED-07). Absent or unusable numbers are null. */
data class YtDlpProgress(
    val status: String?,
    val file: String?,
    val downloaded: Long?,
    val total: Long?,
    val speed: Double?,
)

/**
 * Reads the JSON lines `--progress-template "download:%(progress)j"` makes yt-dlp print: the progress dictionary its
 * hooks get (`status`, `filename`, `downloaded_bytes`, `total_bytes`, `total_bytes_estimate`, `speed`, ...). Anything
 * else on the line stream, such as warnings or a progress bar, is not progress.
 */
object ProgressTemplate {
    fun parse(line: String): YtDlpProgress? {
        val text = line.trim()
        if (!text.startsWith("{")) return null
        val obj =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        val status = obj.text("status")
        val downloaded = obj.number("downloaded_bytes")?.toLong()
        if (status == null && downloaded == null) return null
        return YtDlpProgress(
            status = status,
            file = obj.text("filename"),
            downloaded = downloaded,
            total = (obj.number("total_bytes") ?: obj.number("total_bytes_estimate"))?.toLong(),
            speed = obj.number("speed"),
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 }
}

/**
 * The bytes yt-dlp has downloaded over all its files and restarts. A download of video and audio reports each file's own
 * count in turn, and a restarted process starts its report again from what its partial file holds, so each file is
 * counted by how far it has grown since the last report and never twice.
 */
internal class ProgressTally {
    private val last = mutableMapOf<String, Long>()

    var total: Long = 0L
        private set

    /** [file] already holds [bytes] from an earlier run: they count toward the total but are not new. */
    fun seed(
        file: String,
        bytes: Long,
    ) {
        if (bytes <= 0) return
        last[file] = bytes
        total += bytes
    }

    /** The bytes [progress] adds to what was counted before. */
    fun add(progress: YtDlpProgress): Long {
        val downloaded = progress.downloaded ?: return 0L
        val key = progress.file.orEmpty()
        val before = last[key] ?: 0L
        if (downloaded <= before) return 0L
        last[key] = downloaded
        total += downloaded - before
        return downloaded - before
    }
}
