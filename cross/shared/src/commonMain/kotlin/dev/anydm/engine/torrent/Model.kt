package dev.anydm.engine.torrent

/** The size of a block, the unit of a request: 16 KiB. Every piece is a whole number of blocks except a short last one. */
const val BLOCK = 16 * 1024

/** How many peer connections one torrent may hold. */
const val MAX_PEERS_PER_TORRENT = 50

/** How many peer connections all torrents together may hold. */
const val MAX_PEERS_TOTAL = 200

/** How many block requests may be outstanding to one unchoked peer. */
const val MAX_OUTSTANDING = 16

/** Send a keep-alive after this long without writing to a peer. */
const val KEEPALIVE_MS = 120_000L

/** Drop a peer that has sent nothing for this long. */
const val PEER_SILENCE_MS = 180_000L

/** Fail with [NO_PEERS] when no peer was found or connected for this long. */
const val NO_PEERS_MS = 120_000L

/** A peer that contributed to this many failed pieces is dropped and not reconnected. */
const val MAX_BAD_PIECES = 3

/** A peer lost to a network error after it was connected is tried again after this long. */
const val RECONNECT_WAIT_MS = 5_000L

/** How many times in a row (with no block delivered in between) such a peer is tried again before it is let go. */
const val MAX_RECONNECTS = 3

/** The most unverified piece data one torrent buffers, unless a single piece is bigger: see [bufferCap]. */
const val MAX_BUFFERED = 16 * 1024 * 1024

/**
 * A peer that has requests outstanding and delivers no block for this long is stalled. The torrent run then calls
 * [Picker.release] for it and marks it snubbed for [SNUB_MS].
 */
const val REQUEST_TIMEOUT_MS = 60_000L

/** How long a stalled (snubbed) peer is asked for nothing more. It stays connected and its pieces still count. */
const val SNUB_MS = 120_000L

/**
 * The most unverified piece data a torrent with pieces of [pieceLength] bytes may buffer: [MAX_BUFFERED], or one whole piece when
 * that is bigger (pieces go up to 64 MiB), so a piece can always be assembled and verified. The torrent run stops asking for new
 * blocks once it holds this much.
 */
fun bufferCap(pieceLength: Long): Long = maxOf(MAX_BUFFERED.toLong(), pieceLength)

/** Announces to one tracker are at least this far apart, whatever it asks for. */
const val MIN_ANNOUNCE_MS = 60_000L

/** The time the `completed` and `stopped` announces have, together, to reach the trackers when a torrent ends or is paused. */
const val FAREWELL_MS = 2_000L

/** The port told to trackers: we do not listen, and no peer can connect to us, so the number is only a well-known one. */
const val ANNOUNCE_PORT = 6881

/** Our peer ids start with this, then 12 random bytes. */
const val PEER_ID_PREFIX = "-AD0001-"

/** Row error: no tracker gave a peer and none connected in time. */
const val NO_PEERS = "No peers"

/** Row error: the saved `.torrent` is gone or unreadable. */
const val CANT_RESUME = "Can't resume: add the torrent again"

/** Row error when every tracker answered with [reason]. */
fun trackerError(reason: String): String = "Tracker error: $reason"

/** One block of one piece: [length] bytes at [offset] within piece [piece]. */
data class Block(
    val piece: Int,
    val offset: Int,
    val length: Int,
)

/** What the torrent engine is asked to run. */
data class TorrentJob(
    val id: String,
    /** The saved copy of the `.torrent` file; the engine reads the metainfo from it when the job starts. */
    val torrentPath: String,
    val folder: String,
    /** The root file or folder name under [folder]: the torrent's name, or the one an earlier run was told to use. */
    val name: String,
    /**
     * The verified pieces the row has, or null for a row that has never run. A non-null set also says the files of [name] are this
     * row's own (a resume), so their names are kept as they are; null picks a free name.
     */
    val pieces: PieceSet?,
    /** Our 20-byte peer id: [PEER_ID_PREFIX] and 12 random bytes, one per app run. */
    val peerId: ByteArray,
    /** Peers to connect to (for now the only source of peers). */
    val peers: List<PeerAddr> = emptyList(),
    /**
     * What the row shows as done. The engine reports it back from an event that comes before the metainfo is read (a pause, or a
     * failure to read it), when it cannot work the figure out from [pieces]: so such an event never resets the row to 0.
     */
    val doneBytes: Long = 0,
)
