package dev.anydm.engine.http

import dev.anydm.engine.torrent.PieceSet
import dev.anydm.engine.torrent.TorrentFile

fun interface Clock {
    fun nowMs(): Long
}

/** A byte range of the file: [start]..[end] inclusive, of which the first [done] bytes are on disk. */
data class Span(
    val start: Long,
    val end: Long,
    val done: Long,
) {
    /** The next byte to fetch. */
    val next: Long get() = start + done

    val finished: Boolean get() = next > end
}

/** The smallest range worth a connection of its own, and the smallest piece a split may take. */
const val MIN_SPLIT = 1L shl 20
const val MAX_CONNECTIONS = 16
const val MAX_TOTAL_CONNECTIONS = 64

/** How often a range may be retried before the download fails. */
const val RANGE_RETRIES = 3

/**
 * A segmented `.part` file is `total + TRAILER_BYTES` long: the bytes after the data are the file's identity (a random token),
 * which travels with the saved ranges, so ranges are only ever trusted next to the very file they were written for.
 */
const val TRAILER_BYTES = 16

/** A range's failure count starts again once the range has moved forward this far since the count began. */
const val RETRY_RESET_BYTES = 1L shl 20

/** The wait before the first retry of a failed range; it doubles with each failure in a row, up to [MAX_BACKOFF_MS]. */
const val RETRY_BACKOFF_MS = 250L
const val MAX_BACKOFF_MS = 4_000L

/** Counted failures a run puts up with, over all its ranges, before the download fails. */
const val MAX_RUN_FAILURES = 50

/** How long a connection that gave its permit away waits before it asks for one again. */
const val YIELD_RETRY_MS = 500L

/** How a segmented run handles failures and a lost permit; the defaults are for real use, tests pass tiny values. */
data class RetryPolicy(
    val backoffMs: Long = RETRY_BACKOFF_MS,
    val resetBytes: Long = RETRY_RESET_BYTES,
    val maxRunFailures: Int = MAX_RUN_FAILURES,
    val yieldRetryMs: Long = YIELD_RETRY_MS,
)

/** The wait before a range is tried again after its [failures]th failure in a row: [backoffMs], doubling, to 16 times that. */
fun retryDelayMs(
    failures: Int,
    backoffMs: Long,
): Long {
    val cap = backoffMs * (MAX_BACKOFF_MS / RETRY_BACKOFF_MS)
    val shift = (failures - 1).coerceIn(0, 30)
    return minOf(backoffMs * (1L shl shift), cap)
}

data class JobSpec(
    val id: String,
    val url: String,
    val folder: String,
    val name: String,
    val done: Long,
    val etag: String?,
    val lastModified: String?,
    /** The size the server last reported, when known: a `.part` file of exactly this size is complete. */
    val total: Long? = null,
    /** How many connections may fetch the file at once; 1 is the plain single-connection download. */
    val connections: Int = 1,
    /** The ranges of a segmented download with what is on disk; null: not segmented yet (or a v1 row). */
    val spans: List<Span>? = null,
    /** The identity of the `.part` file the [spans] belong to; ranges without it (or with another one) are not trusted. */
    val partId: String? = null,
)

sealed interface EngineEvent {
    val id: String

    data class Started(
        override val id: String,
        val name: String,
        val total: Long?,
        val etag: String?,
        val lastModified: String?,
        val done: Long,
        /** Non-null when the segmented path was chosen. */
        val spans: List<Span>? = null,
        /** Non-null with [spans]: the identity of the `.part` file, already on disk. */
        val partId: String? = null,
        /** A torrent's verified pieces as the run starts with them. */
        val pieces: PieceSet? = null,
    ) : EngineEvent

    /**
     * A magnet job has fetched and checked its metadata and saved it as the `.torrent` copy (the job's `torrentPath`): the real name,
     * total size and files of the torrent. Comes before `Started`, and only for a job that had no copy when it began.
     */
    data class Metadata(
        override val id: String,
        val name: String,
        val total: Long,
        val files: List<TorrentFile>,
    ) : EngineEvent

    data class Progress(
        override val id: String,
        val done: Long,
        val total: Long?,
        val bytesPerSec: Long,
        val spans: List<Span>? = null,
        /** Connections open right now. */
        val connections: Int = 1,
        val partId: String? = null,
        /** A torrent's verified pieces. */
        val pieces: PieceSet? = null,
        /** A torrent's pieces with a block requested or received that are not verified yet. */
        val inFlight: Set<Int> = emptySet(),
        /** A torrent's peers that have shaken hands and are connected. */
        val peers: Int = 0,
    ) : EngineEvent

    data class Finished(
        override val id: String,
        val done: Long,
        /** Set only when the final name is not the one the job was started with (it was taken by then). */
        val name: String? = null,
    ) : EngineEvent

    data class Failed(
        override val id: String,
        val reason: String,
        val done: Long,
        /** The ranges at the failure; null keeps the row's own (see also [cleared]). */
        val spans: List<Span>? = null,
        val partId: String? = null,
        /** The engine deleted the `.part` file and left none: whatever ranges the row holds are gone. */
        val cleared: Boolean = false,
        /** A torrent's verified pieces at the failure; null keeps the row's own. */
        val pieces: PieceSet? = null,
    ) : EngineEvent

    /**
     * A stopped segmented job has closed (and forced) its part file; [spans] are exact. A stopped torrent has closed its files and
     * says its verified [pieces] (with no [spans]). Not emitted for a one-connection job.
     */
    data class Stopped(
        override val id: String,
        val done: Long,
        val spans: List<Span> = emptyList(),
        val partId: String? = null,
        val pieces: PieceSet? = null,
    ) : EngineEvent
}

data class ProbeResult(
    val name: String,
    val total: Long?,
    val resumable: Boolean,
)

sealed interface ProbeOutcome {
    data class Ok(
        val r: ProbeResult,
    ) : ProbeOutcome

    data class Failed(
        val reason: String,
    ) : ProbeOutcome
}

object Reasons {
    fun http(code: Int) = "HTTP $code"

    const val NETWORK = "Network error"
    const val CERT = "Network error: certificate"
    const val DISK_FULL = "Disk full"
    const val NOT_WRITABLE = "Can't write to folder"
    const val CHANGED = "File changed on server"
}
