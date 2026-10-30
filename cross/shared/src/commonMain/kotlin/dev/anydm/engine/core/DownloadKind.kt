package dev.anydm.engine.core

/** What kind of source a [Download] comes from; it picks the protocol driver. */
enum class DownloadKind {
    /** A plain file URL fetched over HTTP(S). */
    DIRECT,

    /** A media page that needs extraction before there is anything to fetch. */
    SITE,

    /** A torrent or magnet link. */
    TORRENT,

    /** FTP, including FTPS and SFTP. */
    FTP,

    /** A runtime tool (yt-dlp, ffmpeg, Deno) the engine installs for its own use (BIN-01). */
    TOOL,
}
