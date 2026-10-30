package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.ftp.FtpUrl
import dev.anydm.engine.http.percentDecodedBytes
import dev.anydm.engine.torrent.TorrentSource

/** A string the app was handed, understood (OSI-07): the [link] to add, in the form the engine takes, and what it makes. */
data class ParsedLink(
    val link: String,
    val kind: DownloadKind,
)

/**
 * Tells what a link handed to the app is, wherever it came from (a click in the browser, a `magnet:` link the OS opened, an
 * argument of a second launch). A web address is a direct download, `ftp`/`ftps`/`sftp` an FTP one, a magnet link or a
 * `.torrent` (an address, a `file:///` path or a plain path) a torrent. `anydm://add?url=<link>` opens the link it carries,
 * once: that link is not unwrapped again. Anything else is nothing, so a stray argument or a link of another kind
 * (`javascript:`, `file:///etc/passwd`) never becomes a download.
 */
object LinkIntake {
    fun parse(raw: String): ParsedLink? = parse(raw.trim(), unwrap = true)

    private fun parse(
        text: String,
        unwrap: Boolean,
    ): ParsedLink? =
        when {
            text.isEmpty() -> {
                null
            }

            text.startsWith("anydm://", ignoreCase = true) -> {
                if (unwrap) unwrapped(text) else null
            }

            text.startsWith("magnet:", ignoreCase = true) -> {
                TorrentSource.parse(text)?.let { ParsedLink(text, DownloadKind.TORRENT) }
            }

            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> {
                web(text)
            }

            text.startsWith("file://", ignoreCase = true) -> {
                if (endsInTorrent(text) &&
                    !text.substring(7).startsWith("//")
                ) {
                    torrent(text)
                } else {
                    null
                }
            }

            FtpUrl.parse(text) != null -> {
                ParsedLink(text, DownloadKind.FTP)
            }

            endsInTorrent(text) -> {
                fileLink(text)?.let(::torrent)
            }

            else -> {
                null
            }
        }

    private fun web(text: String): ParsedLink? {
        val rest = text.substringAfter("://")
        if (rest.isBlank() || rest.startsWith("/")) return null
        return if (endsInTorrent(text.substringBefore('#').substringBefore('?'))) torrent(text) else ParsedLink(text, DownloadKind.DIRECT)
    }

    private fun torrent(text: String): ParsedLink? = TorrentSource.parse(text)?.let { ParsedLink(text, DownloadKind.TORRENT) }

    // The whole text: a file name may hold a `#` or a `?`, and a file address has no query to cut.
    private fun endsInTorrent(text: String): Boolean = text.endsWith(".torrent", ignoreCase = true)

    // anydm://add?url=<percent-encoded link>
    private fun unwrapped(text: String): ParsedLink? {
        val rest = text.substring("anydm://".length)
        if (!rest.substringBefore('?').trimEnd('/').equals("add", ignoreCase = true)) return null
        val value =
            rest
                .substringAfter('?', "")
                .split('&')
                .firstOrNull { it.startsWith("url=") }
                ?.substring(4) ?: return null
        val link = percentDecodedBytes(value)?.decodeToString() ?: return null
        return parse(link.trim(), unwrap = false)
    }

    // A path on disk as a file:/// address: forward slashes, the drive's letter after the root, anything odd percent-encoded.
    private fun fileLink(path: String): String? {
        val slashed = path.replace('\\', '/')
        // A network share (\\\\host\\share): opening it would reach out to a host the text names.
        if (slashed.startsWith("//")) return null
        val rooted =
            when {
                slashed.startsWith("/") -> slashed
                Regex("^[A-Za-z]:/.*").matches(slashed) -> "/$slashed"
                else -> return null
            }
        val encoded =
            buildString {
                for (byte in rooted.encodeToByteArray()) {
                    val c = byte.toInt().toChar()
                    if (byte >= 0 && (c.isLetterOrDigit() || c in "/-._~:")) {
                        append(c)
                    } else {
                        append('%').append((byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
                    }
                }
            }
        return "file://$encoded"
    }
}
