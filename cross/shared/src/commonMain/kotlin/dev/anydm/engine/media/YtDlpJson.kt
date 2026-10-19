package dev.anydm.engine.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What reading yt-dlp's `-J` output gave. */
sealed interface MediaParse {
    data class Ok(
        val info: MediaInfo,
    ) : MediaParse

    /** Not something B downloads: [reason] says why, for the user. */
    data class Rejected(
        val reason: String,
    ) : MediaParse
}

/** Reads `yt-dlp -J` output (MED-01). Keys it doesn't know are ignored. */
object YtDlpJson {
    fun parse(json: String): MediaParse {
        val root =
            try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return MediaParse.Rejected("yt-dlp printed something that isn't JSON")
        val type = root.text("_type")
        if (type == "playlist" || type == "multi_video") return MediaParse.Rejected("playlists and channels aren't supported yet")
        if (root.flag("is_live") || root.text("live_status") == "is_live") return MediaParse.Rejected("live streams aren't supported")
        val title = root.text("title") ?: return MediaParse.Rejected("yt-dlp gave no title for this page")
        val listed = (root["formats"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::streamOf) }
        val streams = listed.ifEmpty { listOfNotNull(streamOf(root, fallbackId = "direct")) }
        return MediaParse.Ok(
            MediaInfo(
                id = root.text("id") ?: title,
                title = title,
                uploader = root.text("uploader") ?: root.text("channel"),
                date = root.text("upload_date"),
                extractor = root.text("extractor_key") ?: root.text("extractor") ?: "unknown",
                streams = streams,
            ),
        )
    }

    private fun streamOf(
        obj: JsonObject,
        fallbackId: String? = null,
    ): MediaStream? {
        val url = obj.text("url")?.takeIf { it.isNotBlank() } ?: return null
        val formatId = obj.text("format_id") ?: fallbackId ?: return null
        return MediaStream(
            formatId = formatId,
            url = url,
            ext = obj.text("ext") ?: "bin",
            protocol = obj.text("protocol") ?: "https",
            videoCodec = codec(obj, "vcodec"),
            audioCodec = codec(obj, "acodec"),
            height = obj.number("height")?.toInt(),
            fps = obj.number("fps"),
            hdr = obj.text("dynamic_range")?.let { it != "SDR" } ?: false,
            bitrate = obj.number("tbr") ?: obj.number("abr") ?: obj.number("vbr"),
            size = (obj.number("filesize") ?: obj.number("filesize_approx"))?.toLong(),
            headers = headersOf(obj["http_headers"]),
            language = obj.text("language"),
            fragments = fragmentsOf(obj),
            drm = obj.flag("has_drm"),
        )
    }

    /** yt-dlp says "none" for no codec, and says nothing when it doesn't know. */
    private fun codec(
        obj: JsonObject,
        key: String,
    ): String? =
        when (val value = obj.text(key)) {
            null -> "unknown"
            "none" -> null
            else -> value
        }

    /** DASH's `fragments`: each by `url` or by `path`, both read against `fragment_base_url` when there is one. */
    private fun fragmentsOf(obj: JsonObject): List<FragmentRef> {
        val base = obj.text("fragment_base_url")
        return (obj["fragments"] as? JsonArray).orEmpty().mapNotNull { item ->
            val fragment = item as? JsonObject ?: return@mapNotNull null
            val ref = fragment.text("url") ?: fragment.text("path") ?: return@mapNotNull null
            FragmentRef(if (base != null) resolveUrl(base, ref) else ref, fragment.number("duration"))
        }
    }

    private fun headersOf(element: JsonElement?): List<Pair<String, String>> =
        (element as? JsonObject).orEmpty().mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
