@file:Suppress("ktlint:standard:max-line-length")

package dev.anydm.engine.media

/** Recorded-shape `yt-dlp -J` output for the media tests. Stream URLs are `$base/v/<format id>`. */
internal object MediaFixtures {
    private fun format(
        base: String,
        id: String,
        ext: String,
        vcodec: String,
        acodec: String,
        height: Int? = null,
        fps: Int? = null,
        tbr: Double? = null,
        size: Long? = null,
        protocol: String = "https",
        extra: String = "",
    ) = buildString {
        append("""{"format_id":"$id","url":"$base/v/$id","ext":"$ext","protocol":"$protocol","vcodec":"$vcodec","acodec":"$acodec"""")
        if (height != null) append(""","height":$height""")
        if (fps != null) append(""","fps":$fps""")
        if (tbr != null) append(""","tbr":$tbr""")
        if (size != null) append(""","filesize":$size""")
        append(""","http_headers":{"User-Agent":"UA-$id"}""")
        append(extra)
        append("}")
    }

    private fun page(
        formats: List<String>,
        extra: String = "",
        title: String = "Test Video",
    ) = """{"id":"abc123","title":"$title","uploader":"Some Channel","upload_date":"20260131","extractor_key":"Youtube",""" +
        """"unknown_future_key":{"a":1},$extra"formats":[${formats.joinToString(",")}]}"""

    fun splitVideoJson(base: String = "https://cdn.test"): String =
        page(
            listOf(
                format(base, "137", "mp4", "avc1.640028", "none", 1080, 30, 4000.0, 40_000_000, extra = ""","dynamic_range":"SDR""""),
                format(base, "248", "webm", "vp9", "none", 1080, 30, 2500.0, 25_000_000),
                format(base, "136", "mp4", "avc1.4d401f", "none", 720, 30, 2000.0, 20_000_000),
                format(base, "160", "mp4", "avc1.4d400c", "none", 144, 30, 100.0, 1_000_000),
                format(base, "140", "m4a", "none", "mp4a.40.2", tbr = 130.0, size = 3_000_000, extra = ""","abr":128.0,"language":"en""""),
                format(base, "251", "webm", "none", "opus", tbr = 160.0, size = 3_500_000, extra = ""","abr":160.0"""),
                format(base, "18", "mp4", "avc1.42001E", "mp4a.40.2", 360, 30, 500.0, 9_000_000),
                format(base, "95", "mp4", "avc1.4d401f", "mp4a.40.2", 720, 30, protocol = "m3u8_native"),
                format(base, "sb0", "mhtml", "none", "none", protocol = "mhtml"),
            ),
        )

    fun mixedOnlyJson(base: String = "https://cdn.test"): String =
        page(listOf(format(base, "22", "mp4", "avc1.64001F", "mp4a.40.2", 720, 30, 1500.0, 15_000_000)))

    fun audioOnlyJson(base: String = "https://cdn.test"): String =
        page(
            listOf(
                format(base, "140", "m4a", "none", "mp4a.40.2", tbr = 130.0, size = 3_000_000, extra = ""","abr":128.0"""),
                format(base, "251", "webm", "none", "opus", tbr = 160.0, size = 3_500_000, extra = ""","abr":160.0"""),
            ),
        )

    fun hlsOnlyJson(base: String = "https://cdn.test"): String =
        page(listOf(format(base, "95", "mp4", "avc1.4d401f", "mp4a.40.2", 720, 30, protocol = "m3u8_native")))

    fun liveJson(base: String = "https://cdn.test"): String =
        page(
            listOf(format(base, "95", "mp4", "avc1.4d401f", "mp4a.40.2", 720, 30, protocol = "m3u8_native")),
            extra = """"is_live":true,""",
        )

    fun playlistJson(): String = """{"_type":"playlist","id":"PL1","title":"A playlist","entries":[]}"""

    fun directJson(base: String = "https://cdn.test"): String =
        """{"id":"d1","title":"Direct","extractor_key":"Generic","url":"$base/v/direct","ext":"mp4","protocol":"https"}"""

    fun withTitle(
        title: String,
        base: String = "https://cdn.test",
    ): String = page(listOf(format(base, "22", "mp4", "avc1.64001F", "mp4a.40.2", 720, 30, 1500.0, 15_000_000)), title = title)

    fun withoutUrl(base: String = "https://cdn.test"): String =
        page(
            listOf(
                """{"format_id":"bad","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"none"}""",
                format(base, "22", "mp4", "avc1.64001F", "mp4a.40.2", 720, 30, 1500.0, 15_000_000),
            ),
        )

    fun approximateSize(base: String = "https://cdn.test"): String =
        page(
            listOf(
                """{"format_id":"x","url":"$base/v/x","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"none","filesize_approx":777}""",
            ),
        )

    fun hdrStream(base: String = "https://cdn.test"): String =
        page(listOf(format(base, "337", "webm", "vp9.2", "none", 1080, 60, 5000.0, 50_000_000, extra = ""","dynamic_range":"HDR10"""")))

    /** Streams of every route: DASH with and without a fragment list, rtmp, two HLS protocols, and a DRM one. */
    fun routesJson(base: String = "https://cdn.test"): String =
        """{"id":"r1","title":"Routes","extractor_key":"Generic","formats":[""" +
            """{"format_id":"dash-v","url":"$base/m/manifest.mpd","ext":"mp4","protocol":"http_dash_segments","vcodec":"avc1","acodec":"none","height":720,""" +
            """"fragment_base_url":"$base/m/","fragments":[{"path":"init.mp4"},{"path":"seg-1.m4s","duration":4.0},{"url":"https://other.test/seg-2.m4s","duration":4.0}]},""" +
            """{"format_id":"dash-nofrag","url":"$base/m/x.mpd","ext":"mp4","protocol":"http_dash_segments","vcodec":"avc1","acodec":"none","height":480},""" +
            """{"format_id":"rtmp","url":"rtmp://x.test/y","ext":"flv","protocol":"rtmp","vcodec":"avc1","acodec":"mp4a"},""" +
            """{"format_id":"hls","url":"$base/h/index.m3u8","ext":"mp4","protocol":"m3u8","vcodec":"avc1","acodec":"mp4a","height":360},""" +
            """{"format_id":"hls-native","url":"$base/h/native.m3u8","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1","acodec":"mp4a","height":240},""" +
            """{"format_id":"plain","url":"$base/v/plain","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"mp4a","height":144},""" +
            """{"format_id":"drm","url":"$base/d","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"none","height":1080,"has_drm":true}]}"""

    /** One muxed HLS stream whose playlist is at `$base/hls/index.m3u8`. */
    fun hlsPageJson(base: String = "https://cdn.test"): String =
        """{"id":"h1","title":"Test Video","extractor_key":"Generic","formats":[""" +
            """{"format_id":"hls-720","url":"$base/hls/index.m3u8","ext":"mp4","protocol":"m3u8_native","vcodec":"avc1.64001F","acodec":"mp4a.40.2","height":720,"tbr":1500}]}"""

    /** A video-only DASH stream and an audio-only one, each with its own fragment list under `$base/dash/`. */
    fun dashPageJson(base: String = "https://cdn.test"): String =
        """{"id":"d1","title":"Dash","extractor_key":"Generic","formats":[""" +
            """{"format_id":"dash-v","url":"$base/dash/v.mpd","ext":"mp4","protocol":"http_dash_segments","vcodec":"avc1.64001F","acodec":"none","height":720,"tbr":1500,""" +
            """"fragment_base_url":"$base/dash/","fragments":[{"path":"v-init.mp4"},{"path":"v-1.m4s","duration":4.0},{"path":"v-2.m4s","duration":4.0}]},""" +
            """{"format_id":"dash-a","url":"$base/dash/a.mpd","ext":"m4a","protocol":"http_dash_segments","vcodec":"none","acodec":"mp4a.40.2","tbr":128,""" +
            """"fragment_base_url":"$base/dash/","fragments":[{"path":"a-init.mp4"},{"path":"a-1.m4s","duration":4.0}]}]}"""

    /** A plain video-only stream (`$base/v/137`) and an HLS audio-only one (`$base/hls/index.m3u8`). */
    fun mixedRoutesJson(base: String = "https://cdn.test"): String =
        """{"id":"m1","title":"Test Video","extractor_key":"Generic","formats":[""" +
            """{"format_id":"137","url":"$base/v/137","ext":"mp4","protocol":"https","vcodec":"avc1.640028","acodec":"none","height":1080,"tbr":4000,"filesize":4000},""" +
            """{"format_id":"hls-a","url":"$base/hls/index.m3u8","ext":"m4a","protocol":"m3u8_native","vcodec":"none","acodec":"mp4a.40.2","tbr":128}]}"""

    /** One muxed stream only yt-dlp's downloader can fetch (rtmp). */
    fun rtmpMuxedJson(): String =
        """{"id":"r1","title":"Test Video","extractor_key":"Generic","formats":[""" +
            """{"format_id":"rtmp-1","url":"rtmp://x.test/live/a","ext":"flv","protocol":"rtmp","vcodec":"avc1","acodec":"mp4a","height":480}]}"""

    /** A video-only and an audio-only rtmp stream: a merge through yt-dlp. */
    fun rtmpPairJson(): String =
        """{"id":"r2","title":"Test Video","extractor_key":"Generic","formats":[""" +
            """{"format_id":"rv","url":"rtmp://x.test/live/v","ext":"mp4","protocol":"rtmp","vcodec":"avc1.64001F","acodec":"none","height":720,"tbr":1500},""" +
            """{"format_id":"ra","url":"rtmp://x.test/live/a","ext":"m4a","protocol":"rtmp","vcodec":"none","acodec":"mp4a.40.2","tbr":128}]}"""

    /** A page with subtitles (manual and automatic), chapters, thumbnails, a duration, and a six-channel audio stream. */
    fun extrasJson(base: String = "https://cdn.test"): String =
        """{"id":"e1","title":"Extras","uploader":"Chan","upload_date":"20260131","extractor_key":"Youtube","duration":125.5,""" +
            """"description":"About it","webpage_url":"https://www.example.com/watch?v=e1",""" +
            """"subtitles":{"en":[{"ext":"vtt","url":"$base/s/en.vtt","name":"English"},{"ext":"srt","url":"$base/s/en.srt"}],""" +
            """"fr":[{"ext":"vtt","url":"$base/s/fr.vtt"},{"ext":"vtt"}]},""" +
            """"automatic_captions":{"en-orig":[{"ext":"vtt","url":"$base/a/en-orig.vtt"}],""" +
            """"en":[{"ext":"vtt","url":"$base/a/en.vtt"},{"ext":"json3","url":"$base/a/en.json3"}],"de":[{"ext":"vtt","url":"$base/a/de.vtt"}]},""" +
            """"chapters":[{"start_time":0,"end_time":60,"title":"Intro"},{"start_time":60,"end_time":125.5,"title":"Main"},""" +
            """{"start_time":90,"end_time":80,"title":"Bad"}],""" +
            """"thumbnails":[{"url":"$base/t/a.webp","width":1280,"height":720,"preference":-1},""" +
            """{"url":"$base/t/b.jpg?x=1","width":1280,"height":720,"preference":0},{"url":"$base/t/c.jpg","width":320,"height":180}],""" +
            """"formats":[{"format_id":"140","url":"$base/v/140","ext":"m4a","protocol":"https","vcodec":"none","acodec":"mp4a.40.2",""" +
            """"audio_channels":6,"language":"en"}]}"""

    /** A page with only a root thumbnail. */
    fun rootThumbnailJson(base: String = "https://cdn.test"): String =
        """{"id":"t1","title":"Thumb","thumbnail":"$base/t/root.png","formats":[""" +
            """{"format_id":"22","url":"$base/v/22","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"mp4a"}]}"""

    /** [splitVideoJson] plus a 720p AV1 video-only stream `av1`. */
    fun av1Json(base: String = "https://cdn.test"): String =
        splitVideoJson(base).replace(
            "\"formats\":[",
            """"formats":[{"format_id":"av1","url":"$base/v/av1","ext":"mp4","protocol":"https","vcodec":"av01.0.08M.08","acodec":"none",""" +
                """"height":720,"fps":30,"tbr":1000,"filesize":1000},""",
        )

    /** [extrasJson] plus a 720p video-only stream `v1`, so a video can be chosen. */
    fun extrasVideoJson(base: String = "https://cdn.test"): String =
        extrasJson(base).replace(
            "\"formats\":[",
            """"formats":[{"format_id":"v1","url":"$base/v/v1","ext":"mp4","protocol":"https","vcodec":"avc1.64001F","acodec":"none",""" +
                """"height":720,"fps":30,"tbr":1500,"filesize":1000},""",
        )

    /** [extrasVideoJson] with `v1` carrying sound itself, so the choice is one muxed stream. */
    fun extrasMuxedJson(base: String = "https://cdn.test"): String =
        extrasVideoJson(
            base,
        ).replace("\"vcodec\":\"avc1.64001F\",\"acodec\":\"none\"", "\"vcodec\":\"avc1.64001F\",\"acodec\":\"mp4a.40.2\"")

    /** A flat playlist: two good entries, a private one with no address, a repeat of the first, a null, and one with no ie_key. */
    fun flatPlaylistJson(webpageUrl: String = "https://www.youtube.com/playlist?list=PL1"): String =
        """{"_type":"playlist","id":"PL1","title":"My List","uploader":"Chan","extractor_key":"YoutubeTab","webpage_url":"$webpageUrl",""" +
            """"entries":[""" +
            """{"_type":"url","ie_key":"Youtube","id":"a1","url":"https://www.youtube.com/watch?v=a1","title":"One","duration":61},""" +
            """{"ie_key":"Youtube","id":"a2","url":"https://www.youtube.com/watch?v=a2","title":"Two","duration":null},""" +
            """{"ie_key":"Youtube","id":"a3","title":"[Private video]"},""" +
            """{"ie_key":"Youtube","id":"a1","url":"https://www.youtube.com/watch?v=a1","title":"One again"},""" +
            """null,""" +
            """{"ie_key":"Youtube","id":"a5","url":"file:///etc/passwd","title":"Local"},""" +
            """{"id":"a4","url":"https://www.youtube.com/watch?v=a4","title":"Four","duration":125.5}]}"""

    /** A playlist page with [count] distinct entries. */
    fun hugePlaylistJson(count: Int): String =
        """{"_type":"playlist","title":"Big","extractor_key":"YoutubeTab","webpage_url":"https://www.youtube.com/playlist?list=PL9","entries":[""" +
            (1..count).joinToString(",") { """{"ie_key":"Youtube","id":"v$it","url":"https://www.youtube.com/watch?v=v$it"}""" } + "]}"

    /** A channel's root page: it lists its tabs (playlists), not videos; [withVideo] adds one real video. */
    fun channelTabsJson(withVideo: Boolean = false): String =
        """{"_type":"playlist","id":"UC1","title":"Chan","extractor_key":"YoutubeTab","webpage_url":"https://www.youtube.com/@chan","entries":[""" +
            """{"_type":"playlist","ie_key":"YoutubeTab","id":"UC1","url":"https://www.youtube.com/@chan/videos","title":"Chan - Videos"},""" +
            """{"ie_key":"YoutubeTab","id":"UC1s","url":"https://www.youtube.com/@chan/shorts","title":"Chan - Shorts"}""" +
            (if (withVideo) """,{"ie_key":"Youtube","id":"v9","url":"https://www.youtube.com/watch?v=v9","title":"Nine"}""" else "") + "]}"

    /** A playlist whose only entry has a bare id in `url` and its address in `webpage_url`. */
    fun bareUrlPlaylistJson(): String =
        """{"_type":"playlist","title":"Bare","extractor_key":"Soundcloud","entries":[""" +
            """{"ie_key":"Soundcloud","id":"s1","url":"s1","webpage_url":"https://soundcloud.com/a/s1","title":"One"}]}"""
}
