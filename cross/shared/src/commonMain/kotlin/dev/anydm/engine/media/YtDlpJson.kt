package dev.anydm.engine.media

import dev.anydm.engine.core.CollectionKind
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
                subtitles = subtitlesOf(root["subtitles"], auto = false) + subtitlesOf(root["automatic_captions"], auto = true),
                chapters = chaptersOf(root["chapters"]),
                thumbnails = thumbnailsOf(root),
                duration = root.number("duration"),
                description = root.text("description"),
                webpageUrl = root.text("webpage_url") ?: root.text("original_url"),
            ),
        )
    }

    /**
     * A flat playlist listing (`--flat-playlist -J`). An entry needs an id and an http(s) address (`url`, else
     * `webpage_url`); others (private videos, repeats, nulls, addresses of another kind, and nested playlists such as a
     * channel's tabs) are counted in `dropped`. Each entry keeps its [PlaylistEntry.position] in the listing as given. A
     * page that lists only other playlists is refused. A channel is told by its address.
     */
    fun parsePlaylist(
        json: String,
        sourceUrl: String,
    ): PlaylistParse {
        val root =
            try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return PlaylistParse.Rejected("yt-dlp printed something that isn't JSON")
        val type = root.text("_type")
        if (type != "playlist" && type != "multi_video") return PlaylistParse.NotAPlaylist
        val title = root.text("title") ?: return PlaylistParse.Rejected("yt-dlp gave no title for this playlist")
        val raw = (root["entries"] as? JsonArray).orEmpty()
        // The listing is bounded when asked for (--playlist-end); far more than that is something else.
        if (raw.size > LISTING_CAP + 1) {
            return PlaylistParse.Rejected("the playlist has ${raw.size} items; the limit is $LISTING_CAP")
        }
        val fallback = root.text("extractor_key")
        val seen = HashSet<Pair<String, String>>()
        val entries = ArrayList<PlaylistEntry>()
        var nested = 0
        raw.forEachIndexed { index, element ->
            val obj = element as? JsonObject
            if (obj != null && isNestedPlaylist(obj, fallback)) {
                nested++
                return@forEachIndexed
            }
            val entry = obj?.let { entryOf(it, fallback, index + 1) }
            if (entry != null && seen.add(entry.extractor to entry.id)) entries += entry
        }
        if (entries.isEmpty() && nested > 0) {
            return PlaylistParse.Rejected(
                "this page lists other playlists (a channel's tabs), not videos; add one of them, for a channel its /videos page",
            )
        }
        val page = root.text("webpage_url") ?: sourceUrl
        return PlaylistParse.Ok(
            PlaylistListing(
                kind = if (isChannelPage(page)) CollectionKind.CHANNEL else CollectionKind.PLAYLIST,
                title = title,
                uploader = root.text("uploader") ?: root.text("channel"),
                sourceUrl = sourceUrl,
                entries = entries,
                dropped = raw.size - entries.size,
            ),
        )
    }

    // An entry that is itself a playlist: marked so, or a tab of the page that lists it (YouTube's tab extractor makes both).
    private fun isNestedPlaylist(
        obj: JsonObject,
        listingExtractor: String?,
    ): Boolean {
        if (obj.text("_type") == "playlist") return true
        val key = obj.text("ie_key") ?: return false
        return listingExtractor != null && key.endsWith("Tab") && key.equals(listingExtractor, ignoreCase = true)
    }

    private fun entryOf(
        obj: JsonObject,
        fallbackExtractor: String?,
        position: Int,
    ): PlaylistEntry? {
        val id = obj.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val url =
            listOfNotNull(obj.text("url"), obj.text("webpage_url")).firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                ?: return null
        val extractor = (obj.text("ie_key") ?: fallbackExtractor ?: "unknown").lowercase()
        return PlaylistEntry(extractor, id, url, obj.text("title"), obj.number("duration")?.toInt(), position)
    }

    private fun isChannelPage(url: String): Boolean {
        val path = url.substringAfter("://").substringAfter('/', "")
        return path.startsWith("@") || path.startsWith("channel/") || path.startsWith("c/") || path.startsWith("user/")
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
            channels = obj.number("audio_channels")?.toInt(),
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

    /** `lang -> [ {ext, url, name?} ]`; an entry without an extension or an address is skipped. */
    private fun subtitlesOf(
        element: JsonElement?,
        auto: Boolean,
    ): List<SubtitleTrack> =
        (element as? JsonObject).orEmpty().flatMap { (lang, entries) ->
            (entries as? JsonArray).orEmpty().mapNotNull { entry ->
                val track = entry as? JsonObject ?: return@mapNotNull null
                val ext = track.text("ext")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = track.text("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SubtitleTrack(lang, track.text("name"), auto, ext, url)
            }
        }

    private fun chaptersOf(element: JsonElement?): List<Chapter> =
        (element as? JsonArray).orEmpty().mapNotNull { entry ->
            val chapter = entry as? JsonObject ?: return@mapNotNull null
            val start = chapter.number("start_time") ?: return@mapNotNull null
            val end = chapter.number("end_time") ?: return@mapNotNull null
            val title = chapter.text("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (start < 0 || end <= start) null else Chapter(start, end, title)
        }

    private fun thumbnailsOf(root: JsonObject): List<Thumbnail> {
        val listed =
            (root["thumbnails"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val thumbnail = entry as? JsonObject ?: return@mapNotNull null
                val url = thumbnail.text("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Thumbnail(
                    url,
                    extensionOf(url),
                    thumbnail.number("width")?.toInt(),
                    thumbnail.number("height")?.toInt(),
                    thumbnail.number("preference")?.toInt(),
                )
            }
        if (listed.isNotEmpty()) return listed
        return listOfNotNull(root.text("thumbnail")?.takeIf { it.isNotBlank() }?.let { Thumbnail(it, extensionOf(it), null, null, null) })
    }

    /** The extension of the file name in [url]'s path, lowercase; empty when there is none. */
    private fun extensionOf(url: String): String =
        url
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()

    private fun headersOf(element: JsonElement?): List<Pair<String, String>> =
        (element as? JsonObject).orEmpty().mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}

/**
 * [json] without the `cookies` yt-dlp puts on the page and on each format when it read a cookie jar: they are the user's
 * session, and the extraction is kept in the database until the job ends. Text that isn't JSON comes back as it was.
 */
internal fun withoutSessionCookies(json: String): String {
    val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return json

    fun scrub(obj: JsonObject) = JsonObject(obj.filterKeys { it != "cookies" })
    val cleaned =
        root.mapValues { (key, value) ->
            if ((key == "formats" || key == "requested_formats") && value is JsonArray) {
                JsonArray(value.map { if (it is JsonObject) scrub(it) else it })
            } else {
                value
            }
        }
    return Json.encodeToString(JsonObject.serializer(), scrub(JsonObject(cleaned)))
}
