package dev.anydm.engine.tools

enum class ArchiveKind { NONE, ZIP, TAR_XZ }

/** One download of a tool: an archive, or a bare binary when [archive] is [ArchiveKind.NONE]. */
data class Artifact(
    val url: String,
    /** Lowercase hex SHA-256 of the file as downloaded; the engine verifies it as the job's checksum (HTTP-14). */
    val sha256: String,
    val archive: ArchiveKind,
) {
    init {
        require(url.startsWith("https://")) { "an artifact must come over https: $url" }
        require(
            sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' },
        ) { "sha256 must be 64 lowercase hex digits: $sha256" }
    }

    /** The last path segment of [url], without a query. */
    val fileName: String get() = url.substringBefore('?').substringAfterLast('/')
}

/** A pinned version of a tool for one platform: the files that make it up (ffmpeg and ffprobe can come separately). */
data class CatalogEntry(
    val version: String,
    val artifacts: List<Artifact>,
) {
    init {
        require(artifacts.isNotEmpty()) { "an entry needs at least one artifact" }
        require(ToolVersion.parse(version) != null) { "the version must be dotted numbers: $version" }
    }
}

enum class YtDlpChannel { STABLE, NIGHTLY }

/** The release asset yt-dlp publishes for [platform], or null where it publishes none. */
fun ytDlpAsset(platform: Platform): String? =
    when (platform.os) {
        OsFamily.MACOS -> "yt-dlp_macos"
        OsFamily.WINDOWS -> if (platform.arch == Arch.X64) "yt-dlp.exe" else null
        OsFamily.LINUX -> if (platform.arch == Arch.X64) "yt-dlp_linux" else "yt-dlp_linux_aarch64"
    }

/**
 * What the engine knows about getting each tool (BIN-02): pinned versions with their SHA-256 for ffmpeg and Deno, and the
 * oldest version of each that a copy already on the machine may be (BIN-03). yt-dlp has no pinned entry: its release
 * and sums are read from its channel (see ReleaseSource).
 */
class ToolCatalog(
    private val entries: Map<Pair<Tool, Platform>, CatalogEntry>,
    private val minimums: Map<Tool, ToolVersion>,
) {
    fun entryFor(
        tool: Tool,
        platform: Platform,
    ): CatalogEntry? = entries[tool to platform]

    fun minimum(tool: Tool): ToolVersion = requireNotNull(minimums[tool]) { "no minimum version for ${tool.id}" }

    companion object {
        private const val DENO = "https://github.com/denoland/deno/releases/download/v2.9.7/"
        private const val FFMPEG = "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-09-30-13-08/"

        private fun deno(
            triple: String,
            sha256: String,
        ) = CatalogEntry("2.9.7", listOf(Artifact("${DENO}deno-$triple.zip", sha256, ArchiveKind.ZIP)))

        private fun ffmpeg(
            platform: String,
            extension: String,
            kind: ArchiveKind,
            sha256: String,
        ) = CatalogEntry("8.1.3", listOf(Artifact("${FFMPEG}ffmpeg-n8.1.3-9-g29e619e767-$platform-lgpl-8.1.$extension", sha256, kind)))

        /**
         * Deno 2.9.7 from the Deno releases, sums checked against the publisher's own `.sha256sum` files; ffmpeg 8.1.3 LGPL
         * static builds for Windows and Linux from a BtbN month-end autobuild (BtbN keeps daily builds for about two weeks and month-end ones for good, so these URLs last), sums checked
         * against its `checksums.sha256`. No pinned ffmpeg for macOS: there is no official build, so there a system copy
         * (Homebrew) is the only way and the engine can't install one.
         */
        val Pinned =
            ToolCatalog(
                entries =
                    mapOf(
                        (Tool.DENO to Platform(OsFamily.MACOS, Arch.ARM64)) to
                            deno("aarch64-apple-darwin", "5cd46d6268f6f78f5d88bdc7159d20bd44cdaa4b3303474839f87ec6fe7ae25c"),
                        (Tool.DENO to Platform(OsFamily.MACOS, Arch.X64)) to
                            deno("x86_64-apple-darwin", "95daaff11c116a52ad54785e7914c8e9c9cdcaba793c5ed929c74ca2d8e6259a"),
                        (Tool.DENO to Platform(OsFamily.WINDOWS, Arch.X64)) to
                            deno("x86_64-pc-windows-msvc", "a0c3101b4158d1dfb7d6a78a7bf0f3de80c96bb423c152beec8beb22786f2238"),
                        (Tool.DENO to Platform(OsFamily.LINUX, Arch.X64)) to
                            deno("x86_64-unknown-linux-gnu", "c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490"),
                        (Tool.DENO to Platform(OsFamily.LINUX, Arch.ARM64)) to
                            deno("aarch64-unknown-linux-gnu", "c832298b1ad4422481334855f6003e0f54145762c5a134f20a489511d2f65bbf"),
                        (Tool.FFMPEG to Platform(OsFamily.WINDOWS, Arch.X64)) to
                            ffmpeg("win64", "zip", ArchiveKind.ZIP, "4a7642b2264c03e8a0ce8a3825b933ee5580656f45695a086fe7e294045ffc0a"),
                        (Tool.FFMPEG to Platform(OsFamily.LINUX, Arch.X64)) to
                            ffmpeg(
                                "linux64",
                                "tar.xz",
                                ArchiveKind.TAR_XZ,
                                "dfa863a00ca81f1bdf58a372b18cff4820f0017e55de32778de8ecd8ed92a02e",
                            ),
                        (Tool.FFMPEG to Platform(OsFamily.LINUX, Arch.ARM64)) to
                            ffmpeg(
                                "linuxarm64",
                                "tar.xz",
                                ArchiveKind.TAR_XZ,
                                "8463881ca7a25015ca6e00f5c91dd2f1637eb73d33c8230413cb74db760a86b3",
                            ),
                    ),
                minimums =
                    mapOf(
                        Tool.YT_DLP to requireNotNull(ToolVersion.parse("2025.01.01")),
                        Tool.FFMPEG to requireNotNull(ToolVersion.parse("6.0")),
                        Tool.DENO to requireNotNull(ToolVersion.parse("2.0.0")),
                    ),
            )
    }
}
