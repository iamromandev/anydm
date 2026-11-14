package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.ConnectionPermits
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.Fail
import dev.anydm.engine.http.SpeedMeter
import dev.anydm.engine.http.TokenBucket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** What a [TorrentRun] takes from the engine. */
internal class TorrentEnv(
    val transport: PeerTransport,
    val clock: Clock,
    val emit: suspend (EngineEvent) -> Unit,
    /** The torrent's share of the speed cap, spent by all its peers. */
    val bucket: TokenBucket,
    /** Peer connections across the app: one permit each, from the moment a connect is tried. */
    val permits: ConnectionPermits,
    val random: Random,
    val limits: TorrentLimits,
    /** Where peers come from besides the job's own list. */
    val trackers: TrackerClient,
    /** Where the last announces of a run go on after it is over: the run does not wait for them. */
    val background: CoroutineScope,
    /** Where blocking disk writes run. */
    val io: CoroutineDispatcher = Dispatchers.IO,
    /** The last announces of the latest run of each job, by id: a run that follows (a resume) waits for them before it announces. */
    val farewells: ConcurrentHashMap<String, Job> = ConcurrentHashMap(),
)

internal sealed interface RunMsg {
    class FromPeer(
        val peer: PeerId,
        val event: PeerEvent,
    ) : RunMsg

    /** A piece was hashed and, when it was good, written to disk ([failure] is the disk's complaint). */
    class Checked(
        val piece: Int,
        val ok: Boolean,
        val failure: Fail?,
    ) : RunMsg

    class NewPeers(
        val addrs: List<PeerAddr>,
    ) : RunMsg

    /** The announcer heard from the trackers: the peers of a good answer, or ([failure] set) that every tracker refused. */
    class Tracker(
        val addrs: List<PeerAddr>,
        val failure: String?,
    ) : RunMsg

    /** The announcer's latest round told nothing: whatever the trackers refused with before is out of date. */
    data object TrackerSilent : RunMsg

    data object Tick : RunMsg
}

/**
 * The run of one torrent: a single coroutine owns the picker, the piece buffers and the peer table, and everything else (the peer
 * sessions, the hashing of pieces) talks to it through [inbox], so none of that state needs a lock.
 *
 * The rules, in one place:
 *  - Peers: at most `limits.perTorrent` connections (connecting ones included), each holding one of the app-wide permits; never two
 *    to one address, never to ourselves (our peer id in the handshake), never again to an address banned in this run.
 *  - Requests: up to [MAX_OUTSTANDING] blocks per unchoked peer, only for blocks the picker gave out; a block that was not
 *    asked of that peer, or lies outside the torrent, drops the peer. What is requested and received is bounded by `cap`: that is all
 *    the unverified piece data the run holds, the blocks of a piece being hashed or written included, as a complete piece is hashed
 *    and written block by block, in place, never copied into an array of its own. A piece is complete when its last block is in and
 *    is hashed off this coroutine.
 *  - Integrity: a piece counts as done only after its SHA-1 matched and its bytes were written (write, then mark, then report).
 *    A piece that fails is thrown away and fetched again, and a peer that sent data for [MAX_BAD_PIECES] of them is dropped and banned.
 *  - A peer that was connected and is lost to a network error is tried again after [RECONNECT_WAIT_MS], up to [MAX_RECONNECTS] times
 *    in a row (a block delivered in between starts the count again); one dropped for a violation or banned is not.
 *  - A choke gives that peer's requests back at once; a peer with requests out that delivers no block for [REQUEST_TIMEOUT_MS] has
 *    them given back and is asked for nothing for [SNUB_MS].
 *  - Trackers: an [Announcer] runs beside the peers when the torrent has trackers: it offers what they answer, and when every tracker
 *    refused (see [AnnounceReport.Failed]) and there is no peer connected, connecting or waiting, the run fails with [trackerError]
 *    (at once, or as soon as the last peer is gone: peers that are there make the download go on). When the run ends the announcer
 *    says `completed` (if this run finished the torrent) and `stopped` to the trackers in the background, never making the end wait.
 *    A run that follows (a resume) waits for those last announces, for [TorrentLimits.farewellMs] at most, before it announces
 *    `started`: the tracker must not hear them the other way round.
 *  - No peers: with no peer connected, none connecting and none waiting for a permit or a free place, for `limits.noPeersMs` the run
 *    fails with [NO_PEERS]. At most [MAX_PENDING] addresses wait: when more are offered the oldest are let go.
 *
 * [run] returns when every piece is done, and throws [Fail] for a failure of the row. All timers read [TorrentEnv.clock].
 */
internal class TorrentRun(
    private val env: TorrentEnv,
    private val job: TorrentJob,
    private val meta: TorrentMeta,
    private val store: TorrentStore,
    have: PieceSet,
    /** Trackers that heard `started` for this job already (while its metadata was fetched): its first announce to them has no event. */
    private val startedTrackers: Set<String> = emptySet(),
) {
    private val layout = Layout(meta)
    private val picker = Picker(layout, have, env.random)
    private val limits = env.limits

    /** What the unverified piece data held (received, not yet written, the pieces being hashed and written included) may add up to. */
    private val cap: Long = maxOf(limits.maxBuffered, meta.pieceLength)

    /** How often the clock is looked at: four times in a report interval, so a late tick cannot push a report far past its time. */
    private val tickMs: Long = limits.tickMs()

    /** The pieces that are verified and on disk: what a stopped or failed run says is done. */
    @Volatile var verified: PieceSet = have
        private set

    /** The most unverified piece bytes (received) this run held at once. */
    @Volatile var peakBuffered: Long = 0
        private set

    /** How many times the picker was asked for blocks: what a flood of messages from peers costs the run. */
    @Volatile var pickerCalls: Long = 0
        private set

    private val inbox = Channel<RunMsg>(INBOX)

    private class Peer(
        val id: PeerId,
        val addr: PeerAddr,
        val session: PeerSession,
        val job: Job,
    ) {
        var ready = false
        var choked = true
        var interested = false

        /** When this peer last delivered a block, or was first asked for one while it had none outstanding. */
        var progressAt = 0L
        var snubbedUntil = 0L
    }

    /** The blocks of one piece that have arrived, and who sent them. */
    private class PieceBuf(
        blocks: Int,
    ) {
        val blocks = arrayOfNulls<ByteArray>(blocks)
        var bytes = 0L
        val from = HashSet<PeerAddr>()
    }

    private val peers = HashMap<PeerId, Peer>()
    private val bufs = HashMap<Int, PieceBuf>()
    private val checking = HashMap<Int, PieceBuf>()
    private val pending = ArrayDeque<PeerAddr>()

    /** A peer lost to a network error, to be tried again at [at]; its address stays in [inUse] meanwhile. */
    private class Retry(
        val addr: PeerAddr,
        val at: Long,
    )

    private val retries = ArrayDeque<Retry>()

    /** How many times in a row each address was tried again without delivering a block in between. */
    private val retried = HashMap<PeerAddr, Int>()
    private val inUse = HashSet<PeerAddr>()
    private val banned = HashSet<PeerAddr>()
    private val badPieces = HashMap<PeerAddr, Int>()
    private val meter = SpeedMeter()
    private var nextId = 1
    private var readyCount = 0
    private var buffered = 0L
    private var lastReport = 0L
    private var lastPeerAt = 0L

    /** The reason every tracker gave in its latest round, until a round brings an answer or tells nothing. */
    private var trackerFailure: String? = null
    private lateinit var scope: kotlinx.coroutines.CoroutineScope

    /** Offers peers to connect to (from a tracker, say). Addresses that are in use or banned are skipped. */
    suspend fun offerPeers(addrs: List<PeerAddr>) {
        inbox.send(RunMsg.NewPeers(addrs))
    }

    suspend fun run() {
        if (verified.isComplete) return
        val announcer = announcer()
        var finished = false
        try {
            coroutineScope {
                scope = this
                val now = env.clock.nowMs()
                lastReport = now
                lastPeerAt = now
                val ticker =
                    launch {
                        while (true) {
                            delay(tickMs)
                            inbox.send(RunMsg.Tick)
                        }
                    }
                announcer?.let {
                    launch {
                        // `started` must not overtake the `stopped` of the run before this one (a resume): the tracker would end up with us gone
                        env.farewells[job.id]?.let { old -> withTimeoutOrNull(limits.farewellMs) { old.join() } }
                        it.run()
                    }
                }
                try {
                    offer(job.peers)
                    connectMore()
                    while (!verified.isComplete) {
                        when (val msg = inbox.receive()) {
                            is RunMsg.FromPeer -> onPeer(msg.peer, msg.event)
                            is RunMsg.Checked -> onChecked(msg)
                            is RunMsg.NewPeers -> offer(msg.addrs).also { connectMore() }
                            is RunMsg.Tracker -> onTracker(msg)
                            RunMsg.TrackerSilent -> trackerFailure = null
                            RunMsg.Tick -> onTick()
                        }
                    }
                    finished = true
                } finally {
                    ticker.cancel()
                    // the peers are children of this scope: end them, so the scope can complete
                    scope.coroutineContext.cancelChildren()
                }
            }
        } finally {
            // whatever the end, pause or failure or the last piece: the trackers hear of it, after this run has ended and in no hurry of ours
            announcer?.let {
                val farewell = env.background.launch { it.farewell(completed = finished) }
                env.farewells[job.id] = farewell
                farewell.invokeOnCompletion { env.farewells.remove(job.id, farewell) }
            }
        }
    }

    private fun announcer(): Announcer? {
        if (meta.trackers.isEmpty()) return null
        // its own random: this one is not for other coroutines, and the peer id is as good a seed as any
        val random = Random(job.peerId.contentHashCode().toLong())
        val timing = AnnounceTiming(limits.minAnnounceMs, tickMs, limits.farewellMs)
        return Announcer(
            env.trackers,
            env.clock,
            meta.trackers,
            meta.infoHash,
            job.peerId,
            meta.totalSize,
            { verified.doneBytes(meta) },
            random,
            timing,
            startedTrackers,
        ) { report ->
            when (report) {
                is AnnounceReport.Peers -> inbox.send(RunMsg.Tracker(report.addrs, null))
                is AnnounceReport.Failed -> inbox.send(RunMsg.Tracker(emptyList(), report.reason))
                AnnounceReport.NoNews -> inbox.send(RunMsg.TrackerSilent)
            }
        }
    }

    private fun onTracker(msg: RunMsg.Tracker) {
        trackerFailure = msg.failure
        if (msg.failure == null) {
            offer(msg.addrs)
            connectMore()
        } else {
            checkTrackerFailure()
        }
    }

    /** Every tracker refused and nobody is left to download from: the row fails with the reason. */
    private fun checkTrackerFailure() {
        val reason = trackerFailure ?: return
        if (peers.isEmpty() && pending.isEmpty()) throw Fail(trackerError(reason))
    }

    // ---- peers

    private fun offer(addrs: List<PeerAddr>) {
        for (a in addrs) {
            if (a in inUse || a in banned) continue
            inUse.add(a)
            pending.addLast(a)
            // the oldest let go are forgotten, so they can be offered again later
            if (pending.size > MAX_PENDING) inUse.remove(pending.removeFirst())
            lastPeerAt = env.clock.nowMs()
        }
    }

    private fun connectMore() {
        while (pending.isNotEmpty() && peers.size < limits.perTorrent) {
            if (!env.permits.tryAcquireExtra()) return
            startPeer(pending.removeFirst())
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startPeer(addr: PeerAddr) {
        val id = nextId++
        val session =
            PeerSession(
                addr,
                env.transport,
                meta.infoHash,
                job.peerId,
                layout,
                env.clock,
                env.bucket,
                SessionLimits(limits.connectTimeoutMs, limits.handshakeMs, tickMs),
            ) { inbox.send(RunMsg.FromPeer(id, it)) }
        // ATOMIC: the permit is released when the job completes, whether or not its body ever ran
        val sessionJob = scope.launch(start = CoroutineStart.ATOMIC) { session.run() }
        sessionJob.invokeOnCompletion { env.permits.release() }
        peers[id] = Peer(id, addr, session, sessionJob)
    }

    /**
     * Forgets [peer], ends its session and frees its place. A banned peer's address is never connected to again. A peer that was
     * connected and is lost to a network error ([retry]) is tried again after [RECONNECT_WAIT_MS], up to [MAX_RECONNECTS] times in a row.
     */
    private fun drop(
        peer: Peer,
        ban: Boolean = false,
        retry: Boolean = false,
    ) {
        if (peers.remove(peer.id) == null) return
        picker.peerGone(peer.id)
        peer.job.cancel()
        if (peer.ready) {
            readyCount--
            if (readyCount == 0) lastPeerAt = env.clock.nowMs()
        }
        val tries = retried[peer.addr] ?: 0
        if (ban) {
            banned.add(peer.addr)
        } else if (retry && tries < MAX_RECONNECTS) {
            retried[peer.addr] = tries + 1
            retries.addLast(Retry(peer.addr, env.clock.nowMs() + RECONNECT_WAIT_MS))
        } else {
            retried.remove(peer.addr)
            inUse.remove(peer.addr)
        }
        connectMore()
    }

    private fun onPeer(
        id: PeerId,
        event: PeerEvent,
    ) {
        val peer = peers[id] ?: return
        when (event) {
            is PeerEvent.Ready -> ready(peer, event.remoteId)
            is PeerEvent.Closed -> drop(peer, retry = peer.ready && event.retry)
            is PeerEvent.Msg -> if (peer.ready) message(peer, event.msg)
        }
    }

    private fun ready(
        peer: Peer,
        remote: ByteArray,
    ) {
        // ourselves, by another address; any other id is no identity (clients send blank or shared ones): the address says who it is
        if (remote.contentEquals(job.peerId)) return drop(peer, ban = true)
        peer.ready = true
        readyCount++
        lastPeerAt = env.clock.nowMs()
        picker.peerJoined(peer.id)
    }

    private fun message(
        peer: Peer,
        msg: PeerMsg,
    ) {
        when (msg) {
            PeerMsg.Choke -> {
                peer.choked = true
                // the blocks it held go to the others at once; a peer that held none frees nothing, and a flood of chokes costs nothing
                val released = picker.release(peer.id)
                if (released.isNotEmpty()) fillForPieces(released)
            }

            PeerMsg.Unchoke -> {
                peer.choked = false
                fill(peer)
            }

            is PeerMsg.Have -> {
                picker.peerHave(peer.id, msg.piece)
                // a piece we have is no reason for interest or for a request
                if (!picker.have.has(msg.piece)) {
                    interestIn(peer)
                    fill(peer)
                }
            }

            is PeerMsg.Bitfield -> {
                val set = PieceSet.fromPeerBitfield(meta.pieceCount, msg.bytes) ?: return drop(peer)
                picker.peerHas(peer.id, set)
                interestIn(peer)
                fill(peer)
            }

            is PeerMsg.Piece -> {
                block(peer, msg)
            }

            else -> {
                // the session passes on nothing else
            }
        }
    }

    private fun interestIn(peer: Peer) {
        if (peer.interested || !picker.wants(peer.id)) return
        peer.interested = true
        peer.session.send(PeerMsg.Interested)
    }

    // ---- blocks and pieces

    /** The bytes requested and not yet answered, over all peers. */
    private fun inAir(): Long = picker.outstandingTotal.toLong() * BLOCK

    private fun fillAll() {
        for (p in peers.values.toList()) fill(p)
    }

    /** Asks the peers that have one of [pieces], and room for more requests, to take over blocks that were given back. */
    private fun fillForPieces(pieces: Set<Int>) {
        for (p in peers.values.toList()) {
            if (picker.outstanding(p.id) < MAX_OUTSTANDING && pieces.any { picker.peerHasPiece(p.id, it) }) fill(p)
        }
    }

    private fun fill(peer: Peer) {
        if (!peer.ready || peer.choked) return
        val now = env.clock.nowMs()
        if (now < peer.snubbedUntil) return
        val out = picker.outstanding(peer.id)
        val room = MAX_OUTSTANDING - out
        val budget = (cap - buffered - inAir()) / BLOCK
        val n = minOf(room.toLong(), budget).toInt()
        if (n <= 0) return
        pickerCalls++
        val blocks = picker.nextBlocks(peer.id, n)
        if (blocks.isEmpty()) return
        if (out == 0) peer.progressAt = now
        for (b in blocks) peer.session.send(PeerMsg.Request(b.piece, b.offset, b.length))
    }

    private fun block(
        peer: Peer,
        msg: PeerMsg.Piece,
    ) {
        val block = Block(msg.piece, msg.offset, msg.data.size)
        // a block nobody asked this peer for (or one outside the torrent) is a violation
        val arrival = picker.blockArrived(peer.id, block) ?: return drop(peer)
        val now = env.clock.nowMs()
        peer.progressAt = now
        if (arrival.duplicate) {
            fill(peer)
            return
        }
        val buf = bufs.getOrPut(block.piece) { PieceBuf(layout.blockCount(block.piece)) }
        buf.blocks[block.offset / BLOCK] = msg.data
        buf.bytes += msg.data.size
        buf.from.add(peer.addr)
        retried.remove(peer.addr)
        buffered += msg.data.size
        if (buffered > peakBuffered) peakBuffered = buffered
        meter.add(now, msg.data.size.toLong())
        for (other in arrival.cancel) peers[other]?.session?.send(PeerMsg.Cancel(block.piece, block.offset, block.length))
        if (arrival.pieceComplete) verify(block.piece)
        fill(peer)
    }

    /**
     * Hashes the complete piece [piece] away from this coroutine, writes it when it is right, and says so through the inbox. The
     * blocks are hashed one after the other and written where they belong, so no second copy of the piece is made.
     */
    private fun verify(piece: Int) {
        val buf = bufs.remove(piece) ?: return
        checking[piece] = buf
        val blocks = buf.blocks.requireNoNulls().asList()
        val expected = meta.pieceHashes[piece]
        scope.launch(Dispatchers.Default) {
            val digest = MessageDigest.getInstance("SHA-1")
            for (b in blocks) digest.update(b)
            val ok = digest.digest().contentEquals(expected)
            var failure: Fail? = null
            if (ok) {
                try {
                    withContext(env.io) { store.writePiece(piece, blocks) }
                } catch (e: Fail) {
                    failure = e
                }
            }
            inbox.send(RunMsg.Checked(piece, ok, failure))
        }
    }

    private fun onChecked(msg: RunMsg.Checked) {
        val buf = checking.remove(msg.piece) ?: return
        buffered -= buf.bytes
        msg.failure?.let { throw it }
        if (msg.ok) {
            picker.pieceDone(msg.piece)
            verified = picker.have
        } else {
            picker.pieceFailed(msg.piece)
            for (addr in buf.from) {
                val bad = (badPieces[addr] ?: 0) + 1
                badPieces[addr] = bad
                if (bad >= MAX_BAD_PIECES) ban(addr)
            }
        }
        fillAll()
    }

    private fun ban(addr: PeerAddr) {
        banned.add(addr)
        pending.remove(addr)
        retries.removeAll { it.addr == addr }
        peers.values.firstOrNull { it.addr == addr }?.let { drop(it, ban = true) }
    }

    // ---- the clock

    private suspend fun onTick() {
        val now = env.clock.nowMs()
        stalls(now)
        unjam()
        fillAll()
        requeue(now)
        connectMore()
        // Peers that are connected, being connected to, or waiting for a permit or a place are not "no peers": the clock starts when
        // there is nobody left to try.
        if (readyCount > 0 || peers.isNotEmpty() || pending.isNotEmpty() || retries.isNotEmpty()) lastPeerAt = now
        checkTrackerFailure()
        if (now - lastPeerAt >= limits.noPeersMs) throw Fail(NO_PEERS)
        // a tick may come late: due a tick early, so a report is never more than the interval after the one before
        if (now - lastReport >= limits.reportMs - tickMs) {
            lastReport = now
            env.emit(
                EngineEvent.Progress(
                    job.id,
                    verified.doneBytes(meta),
                    meta.totalSize,
                    meter.bytesPerSec(now),
                    connections = readyCount,
                    pieces = verified,
                    inFlight = picker.inFlight,
                    peers = readyCount,
                ),
            )
        }
    }

    /** The peers whose wait is over join the addresses waiting for a connection. */
    private fun requeue(now: Long) {
        while (retries.isNotEmpty() && retries.first().at <= now) {
            pending.addLast(retries.removeFirst().addr)
            if (pending.size > MAX_PENDING) inUse.remove(pending.removeFirst())
        }
    }

    /** A peer with requests out that has delivered nothing for [REQUEST_TIMEOUT_MS] gives them back and is left alone for [SNUB_MS]. */
    private fun stalls(now: Long) {
        for (p in peers.values) {
            if (!p.ready || p.choked || picker.outstanding(p.id) == 0) continue
            if (now - p.progressAt < REQUEST_TIMEOUT_MS) continue
            picker.release(p.id)
            p.snubbedUntil = now + SNUB_MS
        }
    }

    /**
     * Unverified data fills the budget but nothing is on its way and nothing is being checked: the pieces held are all waiting for
     * blocks that could not be requested. Gives a piece up so the others can finish: one that no peer who may ask now has (the least
     * complete of those), so what is freed goes to pieces someone can deliver and is not taken back by the next round; when each
     * held piece could be finished by someone, only the budget is short, and least complete pieces are given up until a whole
     * piece's worth of it is free.
     */
    private fun unjam() {
        if (buffered + BLOCK <= cap || checking.isNotEmpty() || picker.outstandingTotal > 0) return
        val now = env.clock.nowMs()
        val askable = peers.values.filter { it.ready && !it.choked && now >= it.snubbedUntil }
        if (askable.isEmpty()) return // nobody could ask yet: wait for one
        val orphans = bufs.keys.filter { piece -> askable.none { picker.peerHasPiece(it.id, piece) } }
        if (orphans.isNotEmpty()) {
            giveUp(orphans.minWith(compareBy({ bufs.getValue(it).bytes }, { it })))
            return
        }
        var freed = 0L
        for (piece in bufs.keys.sortedWith(compareBy({ bufs.getValue(it).bytes }, { it }))) {
            freed += giveUp(piece)
            if (freed >= meta.pieceLength) break
        }
    }

    /** Throws away what was received of [piece]; it is needed again. Returns the bytes freed. */
    private fun giveUp(piece: Int): Long {
        val buf = bufs.remove(piece) ?: return 0
        buffered -= buf.bytes
        picker.pieceFailed(piece)
        return buf.bytes
    }

    private companion object {
        /** Messages waiting for the run: the readers wait when it is full, so a peer cannot flood it. */
        const val INBOX = 1024

        /** The most addresses kept waiting for a connection; a tracker or a peer that offers more makes the oldest be forgotten. */
        const val MAX_PENDING = 1000
    }
}
