package dev.anydm.engine.torrent

/** Where a torrent comes from (TOR-01): the job's mirror address, read. */
sealed interface TorrentSource {
    data class Magnet(
        val uri: String,
    ) : TorrentSource

    data class Url(
        val url: String,
    ) : TorrentSource

    data class LocalFile(
        val path: String,
    ) : TorrentSource

    companion object {
        /** The source [text] names, or null when it is none of a magnet link, an http(s) address or a `file:///` path. */
        fun parse(text: String): TorrentSource? {
            val trimmed = text.trim()
            return when {
                trimmed.startsWith("magnet:?", ignoreCase = true) -> {
                    if (hasInfoHash(trimmed)) Magnet(trimmed) else null
                }

                trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> {
                    val rest = trimmed.substringAfter("://")
                    if (rest.isBlank() || rest.startsWith("/")) null else Url(trimmed)
                }

                trimmed.startsWith("file://", ignoreCase = true) -> {
                    localFile(trimmed.substring("file://".length))
                }

                else -> {
                    null
                }
            }
        }

        // An `xt=urn:btih:` parameter with a hash: 40 hex digits, or 32 base32 characters.
        private fun hasInfoHash(magnet: String): Boolean =
            magnet.substringAfter('?').split('&').any { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                key.equals("xt", ignoreCase = true) && value.startsWith("urn:btih:", ignoreCase = true) && validHash(value.substring(9))
            }

        private fun validHash(hash: String): Boolean =
            (hash.length == 40 && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) ||
                (hash.length == 32 && hash.all { it in 'A'..'Z' || it in 'a'..'z' || it in '2'..'7' })

        private fun localFile(afterScheme: String): LocalFile? {
            if (!afterScheme.startsWith("/")) return null
            val decoded = percentDecode(afterScheme) ?: return null
            // file:///C:/dir/x: the drive's own slash is not part of the path.
            val path = if (Regex("^/[A-Za-z]:/.*").matches(decoded)) decoded.substring(1) else decoded
            return LocalFile(path)
        }

        private fun percentDecode(text: String): String? {
            val bytes = ArrayList<Byte>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    val hex = text.substring(i + 1, minOf(i + 3, text.length))
                    val value = hex.toIntOrNull(16)
                    if (hex.length != 2 || value == null) return null
                    bytes += value.toByte()
                    i += 3
                } else {
                    val width = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
                    bytes += text.substring(i, i + width).encodeToByteArray().toList()
                    i += width
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
