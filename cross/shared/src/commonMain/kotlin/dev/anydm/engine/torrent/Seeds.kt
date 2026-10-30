package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.TorrentRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/** The ratio and the minutes that apply to a torrent: its own rule over the settings (TOR-05); null for a limit that is off. */
internal fun seedLimits(
    row: TorrentRow?,
    settings: Settings,
): Pair<Double?, Int?> =
    (row?.seedRatio ?: SeedRule.Inherit).effective(settings.seedRatio) to
        (row?.seedMinutes ?: SeedRule.Inherit).effective(settings.seedMinutes)

/**
 * The torrents of finished downloads that stay in the session to upload (TOR-05). One loop, started with the first seed,
 * checks them every [seedPollEvery] against their rules, read fresh each time, and ends the ones that are done or broken.
 * [save] stores a torrent's resume data. Every seed ends once, as an event on [ends].
 */
internal class Seeds(
    private val torrents: suspend (DownloadId) -> TorrentRow?,
    private val settings: suspend () -> Settings,
    private val resumeEvery: Duration,
    private val seedPollEvery: Duration,
    private val secondsPerMinute: Int,
    private val scope: CoroutineScope,
    private val save: suspend (DownloadId, RunningTorrent) -> Unit,
    /** Told about each healthy seed at every check, to keep its live values (TOR-07). */
    private val observe: suspend (DownloadId, RunningTorrent) -> Unit,
    /** Told when a seed has left the session, ended or not. */
    private val onLeave: suspend (DownloadId) -> Unit,
) {
    private class Seed(
        val handle: RunningTorrent,
        /** What the meter has been told of the torrent's payload so far. */
        var up: Long,
        var down: Long,
    ) {
        var savedAt = TimeSource.Monotonic.markNow()
    }

    private val lock = Mutex()
    private val seeds = mutableMapOf<DownloadId, Seed>()
    private val finalSamples = mutableListOf<SeedSample>()
    private var loop: Job? = null
    private val events = Channel<SeedEnd>(Channel.UNLIMITED)

    val ends: Flow<SeedEnd> get() = events.receiveAsFlow()

    suspend fun has(id: DownloadId): Boolean = lock.withLock { id in seeds }

    /** The torrent of the seed of [id], or null. */
    suspend fun handle(id: DownloadId): RunningTorrent? = lock.withLock { seeds[id]?.handle }

    /** Starts keeping [handle] as the seed of [id]; [up] and [down] are what the meter has counted of it already. */
    suspend fun add(
        id: DownloadId,
        handle: RunningTorrent,
        up: Long,
        down: Long,
    ) {
        lock.withLock {
            seeds[id] = Seed(handle, up, down)
            if (loop == null) loop = scope.launch { run() }
        }
    }

    /** Ends the seed of [id] for [reason]; false if it isn't one (it may just have ended). */
    suspend fun end(
        id: DownloadId,
        reason: SeedEndReason,
    ): Boolean {
        val seed = lock.withLock { seeds.remove(id) } ?: return false
        withContext(NonCancellable) { leave(id, seed) }
        events.send(SeedEnd.Ended(id, reason))
        return true
    }

    /** Takes the seed of [id], if it has one, out of the session without an end: its job is being run again. */
    suspend fun drop(id: DownloadId) {
        val seed = lock.withLock { seeds.remove(id) } ?: return
        withContext(NonCancellable) { leave(id, seed) }
    }

    private suspend fun fail(
        id: DownloadId,
        message: String,
    ) {
        val seed = lock.withLock { seeds.remove(id) } ?: return
        withContext(NonCancellable) { leave(id, seed) }
        events.send(SeedEnd.Failed(id, message))
    }

    /** Saves the last resume data, counts what the seed moved since the last [traffic], and takes it out of the session. */
    private suspend fun leave(
        id: DownloadId,
        seed: Seed,
    ) {
        save(id, seed.handle)
        val stats = seed.handle.stats()
        val up = stats.payloadUp - seed.up
        val down = stats.payloadDown - seed.down
        if (up > 0 || down > 0) lock.withLock { finalSamples += SeedSample(id, maxOf(up, 0), maxOf(down, 0)) }
        try {
            seed.handle.remove()
        } finally {
            onLeave(id)
        }
    }

    /** What each seed moved since the last call, and what the seeds that ended moved before they did. */
    suspend fun traffic(): List<SeedSample> =
        lock.withLock {
            val out = finalSamples.toMutableList()
            finalSamples.clear()
            for ((id, seed) in seeds) {
                val stats = seed.handle.stats()
                val up = stats.payloadUp - seed.up
                val down = stats.payloadDown - seed.down
                seed.up = stats.payloadUp
                seed.down = stats.payloadDown
                if (up > 0 || down > 0) out += SeedSample(id, maxOf(up, 0), maxOf(down, 0))
            }
            out
        }

    /** Stops the loop; the seeds stay in the session for the caller to close. */
    suspend fun stop() {
        val running = lock.withLock { loop.also { loop = null } }
        running?.cancelAndJoin()
    }

    private suspend fun run() {
        while (true) {
            delay(seedPollEvery)
            try {
                poll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The store or a handle failed this time; the next poll looks again.
            }
        }
    }

    private suspend fun poll() {
        val now = lock.withLock { seeds.toList() }
        if (now.isEmpty()) return
        val current = settings()
        for ((id, seed) in now) {
            val error = seed.handle.progress().error
            if (error != null) {
                fail(id, error.message)
                continue
            }
            observe(id, seed.handle)
            val (ratio, minutes) = seedLimits(torrents(id), current)
            val stats = seed.handle.stats()
            val reached =
                (ratio == null && minutes == null) ||
                    (ratio != null && stats.wanted > 0 && stats.allTimeUp.toDouble() / stats.wanted >= ratio) ||
                    (minutes != null && stats.seedingSeconds >= minutes.toLong() * secondsPerMinute)
            if (reached) {
                end(id, SeedEndReason.RULE)
            } else if (seed.savedAt.elapsedNow() >= resumeEvery) {
                save(id, seed.handle)
                seed.savedAt = TimeSource.Monotonic.markNow()
            }
        }
    }
}
