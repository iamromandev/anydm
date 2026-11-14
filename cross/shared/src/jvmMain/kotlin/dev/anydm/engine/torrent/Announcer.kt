package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** What a round of announces tells the torrent run. */
internal sealed interface AnnounceReport {
    /** A tracker answered with these peers (possibly none). */
    class Peers(
        val addrs: List<PeerAddr>,
    ) : AnnounceReport

    /** Every tracker of every tier answered with a failure reason; [reason] is the first one's. */
    class Failed(
        val reason: String,
    ) : AnnounceReport

    /** No tracker answered with peers or with a failure reason from every one: nothing new, and what was said before is out of date. */
    data object NoNews : AnnounceReport
}

/** The waits of an [Announcer]: [minMs] between announces to one tracker, [tickMs] between looks at the clock, [farewellMs] for the last ones. */
internal data class AnnounceTiming(
    val minMs: Long = MIN_ANNOUNCE_MS,
    val tickMs: Long = 250,
    val farewellMs: Long = FAREWELL_MS,
    /** How long a tracker may be silent before the next one is asked too. */
    val hedgeMs: Long = HEDGE_MS,
)

/** How many trackers of one round are asked at the same time. */
private const val MAX_PARALLEL = 8

/** A tracker that has not answered after this long does not keep the next one from being asked (UDP trackers wait up to 225 s). */
private const val HEDGE_MS = 10_000L

/**
 * Announces one torrent to its trackers (BEP 12): the tiers in the order of the file, each tier shuffled once, the trackers of a tier
 * tried in order. A round asks tracker after tracker until one answers with peers, and asks the next one too when one has been silent
 * for [AnnounceTiming.hedgeMs]: a tracker that never answers must not keep the others from being asked. The first that answered well
 * moves to the front of its tier, so the next round asks it first. A tracker that answered with no peers does not end the round,
 * another may have some.
 *
 * The first announces carry `started` until a tracker has heard it; after that they carry no event, and come at the interval the
 * tracker gave: never sooner than [AnnounceTiming.minMs] and its `min interval`. A round where no tracker answered is repeated after
 * [AnnounceTiming.minMs]. Every wait is read on [clock], looked at every [AnnounceTiming.tickMs].
 *
 * What comes back goes to [report]: the peers of a good answer, or the failure reason when every tracker said no (one that could not be
 * reached, or answered with something unusable, is not a "no": a round with one is [AnnounceReport.NoNews]). Everything a tracker says is untrusted;
 * [client] is where it is checked.
 *
 * [farewell] sends the last announces once the run is over, and is the only part that outlives [run].
 */
internal class Announcer(
    private val client: TrackerClient,
    private val clock: Clock,
    tiers: List<List<String>>,
    private val infoHash: ByteArray,
    private val peerId: ByteArray,
    private val total: Long,
    /** The bytes verified so far. */
    private val downloaded: () -> Long,
    random: Random,
    private val timing: AnnounceTiming,
    /** Trackers (by URL) that heard `started` for this torrent from an earlier announcer of the same job: they are sent no second one. */
    private val alreadyStarted: Set<String> = emptySet(),
    private val report: suspend (AnnounceReport) -> Unit,
) {
    private val tiers: List<MutableList<String>> = tiers.map { it.toMutableList().apply { if (size > 1) shuffle(random) } }
    private val key = random.nextInt()

    /** Trackers that gave a good answer in this run: the ones that are told when it ends. */
    private val heard = LinkedHashSet<String>()
    private var startedHeard = false

    /** Trackers that answered an announce carrying `started`, plus those that had heard it before this announcer. */
    private val startedBy = LinkedHashSet<String>(alreadyStarted)

    /** The trackers that have heard `started` for this torrent. */
    fun startedTrackers(): Set<String> = synchronized(startedBy) { startedBy.toSet() }

    private fun request(event: AnnounceEvent?): AnnounceRequest {
        val done = downloaded().coerceIn(0, total)
        return AnnounceRequest(infoHash, peerId, ANNOUNCE_PORT, done, total - done, event, key = key)
    }

    /** Announces until cancelled. */
    suspend fun run() {
        var next = clock.nowMs()
        while (true) {
            if (clock.nowMs() >= next) {
                // the wait counts from the end of the round: a round of minutes (a tracker that is slow to answer) is not followed at once
                val wait = round()
                next = clock.nowMs() + wait
            }
            delay(timing.tickMs)
        }
    }

    /** What one tracker said, told to the round by the coroutine that asked. */
    private class Answer(
        val tier: MutableList<String>,
        val url: String,
        val reply: TrackerReply?,
        /** The request carried `started`. */
        val started: Boolean,
    )

    /**
     * One round of announces: reports what it found and returns the wait until the next one. The trackers are asked in the order of
     * the tiers (BEP 12), one after the other: the next when the one before has answered with no peers or failed, or has been silent
     * for [AnnounceTiming.hedgeMs] (a UDP tracker may take 225 s to give up, and a live one must not wait behind it); those out are
     * waited for meanwhile, at most [MAX_PARALLEL] at a time. The round ends at the first answer with peers, or when everyone has
     * been asked and has answered or failed.
     */
    private suspend fun round(): Long =
        coroutineScope {
            val event = if (startedHeard) null else AnnounceEvent.Started
            val answers = Channel<Answer>(Channel.UNLIMITED)
            val gate = Semaphore(MAX_PARALLEL)
            val order = tiers.flatMap { tier -> tier.toList().map { tier to it } }
            var next = 0
            var startedAt = 0L
            var waiting = 0
            val reasons = ArrayList<String>()
            val peers = LinkedHashSet<PeerAddr>()
            var intervalS: Int? = null
            var minIntervalS: Int? = null
            var answered = false

            fun startNext() {
                val (tier, url) = order[next++]
                startedAt = clock.nowMs()
                waiting++
                // a tracker that heard `started` before is not told twice
                val sent = event.takeUnless { url in alreadyStarted }
                launch { gate.withPermit { answers.send(Answer(tier, url, ask(url, request(sent)), sent != null)) } }
            }

            while (waiting > 0 || next < order.size) {
                if (next < order.size && (waiting == 0 || clock.nowMs() - startedAt >= timing.hedgeMs)) {
                    startNext()
                    continue
                }
                val a = withTimeoutOrNull(timing.tickMs) { answers.receive() } ?: continue
                waiting--
                when (val reply = a.reply) {
                    is AnnounceResult -> {
                        if (!answered) {
                            // the first to answer well sets the pace, and moves to the front of its tier
                            answered = true
                            intervalS = reply.intervalS
                            minIntervalS = reply.minIntervalS
                            a.tier.remove(a.url)
                            a.tier.add(0, a.url)
                        }
                        synchronized(heard) { heard.add(a.url) }
                        if (a.started) synchronized(startedBy) { startedBy.add(a.url) }
                        peers.addAll(reply.peers)
                        if (reply.peers.isNotEmpty()) break
                    }

                    is TrackerFailure -> {
                        if (!reply.malformed) reasons.add(reply.reason)
                    }

                    null -> {
                        Unit
                    }
                }
            }
            // the trackers still out are no longer needed
            coroutineContext.cancelChildren()
            if (answered) {
                startedHeard = true
                report(AnnounceReport.Peers(peers.toList()))
                val floorMs = maxOf(timing.minMs, (minIntervalS ?: 0) * 1_000L)
                return@coroutineScope maxOf((intervalS ?: 0) * 1_000L, floorMs)
            }
            report(if (order.isNotEmpty() && reasons.size == order.size) AnnounceReport.Failed(reasons.first()) else AnnounceReport.NoNews)
            timing.minMs
        }

    /** The answer of [url], or null when it could not be had; whatever goes wrong with it but a cancel is "no answer". */
    private suspend fun ask(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply? =
        try {
            client.announce(url, req)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /**
     * Tells the trackers that answered us that the torrent is done (`completed`, when [completed]) and that this client is leaving
     * (`stopped`), within [AnnounceTiming.farewellMs] (real time: it is network time) and whatever they do. Failures are not reported.
     */
    suspend fun farewell(completed: Boolean) {
        val targets = synchronized(heard) { heard.toList() }
        if (targets.isEmpty()) return
        val events = if (completed) listOf(AnnounceEvent.Completed, AnnounceEvent.Stopped) else listOf(AnnounceEvent.Stopped)
        withTimeoutOrNull(timing.farewellMs) {
            coroutineScope {
                targets
                    .map { url -> async { for (e in events) ask(url, request(e)) } }
                    .awaitAll()
            }
        }
    }
}
