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
}
