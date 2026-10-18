package dev.anydm.engine.tools

enum class OsFamily { WINDOWS, MACOS, LINUX }

enum class Arch { X64, ARM64 }

data class Platform(
    val os: OsFamily,
    val arch: Arch,
) {
    companion object
}

/** A program the engine runs but doesn't ship (BIN-01..BIN-04). One tool can bring several executables. */
enum class Tool(
    val id: String,
    val executables: List<String>,
    /** What to pass to ask it for its version: ffmpeg knows only the single-dash form. */
    val versionArgument: String = "--version",
) {
    YT_DLP("yt-dlp", listOf("yt-dlp")),
    FFMPEG("ffmpeg", listOf("ffmpeg", "ffprobe"), versionArgument = "-version"),
    DENO("deno", listOf("deno")),
    ;

    /** The file name of [executable] on [os]: Windows adds `.exe`. */
    fun fileName(
        executable: String,
        os: OsFamily,
    ): String = if (os == OsFamily.WINDOWS) "$executable.exe" else executable

    companion object {
        fun byId(id: String): Tool? = entries.firstOrNull { it.id == id }
    }
}
