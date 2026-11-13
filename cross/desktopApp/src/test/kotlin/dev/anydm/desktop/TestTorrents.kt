package dev.anydm.desktop

import dev.anydm.engine.torrent.BValue
import dev.anydm.engine.torrent.TorrentMeta
import dev.anydm.engine.torrent.TorrentResult
import dev.anydm.engine.torrent.encodeBencode
import dev.anydm.engine.torrent.readTorrent
import dev.anydm.engine.torrent.sha1

/** `.torrent` files for the desktop tests, built in memory: the piece hashes are made up (no test here downloads their content). */
object TestTorrents {
    private fun str(s: String) = BValue.BBytes(s.encodeToByteArray())

    /** A single-file torrent named [name] when [files] has one entry and [single] is set, else a folder torrent. */
    fun bytes(
        name: String,
        files: List<Pair<String, Long>>,
        single: Boolean = false,
        trackers: List<List<String>> = listOf(listOf("http://tracker.test/announce")),
        pieceLength: Long? = null,
    ): ByteArray {
        val total = files.sumOf { it.second }
        // about a thousand pieces at most, so a 5 GB torrent has no huge `pieces` string
        val pieceLength = pieceLength ?: generateSequence(16_384L) { it * 2 }.first { total / it <= 1_000 }
        val pieceCount = ((total + pieceLength - 1) / pieceLength).toInt()
        val shape =
            if (single) {
                mapOf("length" to BValue.BInt(total))
            } else {
                mapOf(
                    "files" to
                        BValue.BList(
                            files.map { (path, size) ->
                                BValue.BDict(
                                    mapOf("length" to BValue.BInt(size), "path" to BValue.BList(path.split('/').map(::str))),
                                )
                            },
                        ),
                )
            }
        val info =
            BValue.BDict(
                mapOf(
                    "name" to str(name),
                    "piece length" to BValue.BInt(pieceLength),
                    // distinct per name, so two torrents never share an info hash by accident
                    "pieces" to BValue.BBytes(ByteArray(20 * pieceCount) { sha1(name.encodeToByteArray(), 0, name.length)[it % 20] }),
                ) + shape,
            )
        val root =
            mutableMapOf<String, BValue>("info" to info)
        if (trackers.isNotEmpty()) {
            root["announce"] = str(trackers.first().first())
            root["announce-list"] = BValue.BList(trackers.map { tier -> BValue.BList(tier.map(::str)) })
        }
        return encodeBencode(BValue.BDict(root))
    }

    fun meta(bytes: ByteArray): TorrentMeta = (readTorrent(bytes) as TorrentResult.Ok).meta
}
