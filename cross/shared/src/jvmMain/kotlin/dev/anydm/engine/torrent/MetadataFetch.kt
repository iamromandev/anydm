package dev.anydm.engine.torrent

import dev.anydm.engine.http.Fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** A request not answered for this long lets the peer go: a metadata block is 16 KiB, so a peer that holds one this long is not to be waited for. */
internal const val METADATA_REQUEST_TIMEOUT_MS = 15_000L

/** The most bytes of blocks all the assemblers of a fetch hold together: two of the biggest metadata there can be. */
internal const val MAX_HELD_METADATA_BYTES = 2L * MAX_METADATA_BYTES

/** A block asked of one peer for this long is asked of an idle peer too (the first answer is used). */
private const val DUPLICATE_AFTER_MS = 5_000L

/** While one peer alone gives all the data (after a failed check) its turn lasts this long, plus [SOLO_BLOCK_MS] for each block. */
private const val SOLO_GRACE_MS = 10_000L
private const val SOLO_BLOCK_MS = 5_000L

/** A peer that could not help, or went silent, is not connected to again for this long. */
private const val RETRY_AFTER_MS = 60_000L

/** How many metadata blocks are asked of one peer at a time. */
internal const val MAX_OUTSTANDING_METADATA = 4

/**
 * What a [MetadataFetch] announces as bytes left: a client with no metadata does not know the size, and a tracker that takes a client
 * with nothing left for a seed may leave the seeds out of its answer.
 */
private const val LEFT_UNKNOWN = 1L

/** The biggest message accepted from a peer while fetching: a block and its header, or a `bitfield` of a very large torrent. */
private const val MAX_FETCH_MESSAGE = 128 * 1024

/** The extension id we give `ut_metadata` in our extension handshake. */
private const val OUR_UT_METADATA = 1

/**
 * Fetches the metadata (the `info` dictionary) of a magnet link from peers (BEP 9 over the extension protocol of BEP 10), for the
 * torrent job [jobId]: the peers come from announcing to the magnet's trackers (see [Announcer]) and from [jobPeers]. [run] returns the
 * bytes once their SHA-1 is the info hash, and throws [Fail] when no peer can give them. All timers read the clock of [env].
 *
 * Like [TorrentRun], one coroutine owns the state (the assemblers, the peer table); the peer connections talk to it through [inbox].
 *  - Peers: at most `limits.perTorrent` connections, each holding one of the app-wide permits (from the moment a connect is tried),
 *    never two to one address, never to ourselves, never again to an address that was bad in this run. Bytes read count against the
 *    torrent's share of the speed cap ([TorrentEnv.bucket]).
 *  - A peer is bad, dropped and not tried again in this run, when it lies or breaks the protocol: a `metadata_size` that is not 1 to
 *    [MAX_METADATA_BYTES] (checked before anything is allocated), a malformed message, a reject, a block nobody asked it for, a block
 *    of the wrong length, a `total_size` that is not its own offer, or data that fails the hash. Only these count towards
 *    [METADATA_INVALID].
 *  - A peer that cannot help (no extension support, no `ut_metadata`, no metadata size or a zero one, ourselves) is not bad: it is let
 *    go, and may be offered again (by a tracker) after [RETRY_AFTER_MS]. One that fails by the network, or does not answer for
 *    [METADATA_REQUEST_TIMEOUT_MS], is let go the same way.
 *  - Sizes: each peer claims a size and the claims are not trusted over each other: there is one assembler for each size claimed (at
 *    most `limits.perTorrent`, and the blocks they hold are bounded together by `maxHeld`), the peers of a size share its blocks, and
 *    the first assembler whose data matches the hash is the answer. What a size holds stays when its last peer goes, for the next one.
 *  - Blocks: up to [MAX_OUTSTANDING_METADATA] are asked of a peer at a time, each block of one peer first; a peer with nothing left to ask
 *    is given the blocks that another has held for [DUPLICATE_AFTER_MS], and the first answer is used.
 *  - Integrity: an assembler's data is accepted only when its SHA-1 matches. When it does not and it came from several peers, nobody can
 *    say whose block was wrong, so the data is dropped and one peer at a time gives all of it (the lowest numbered first, for a time
 *    bounded by the size): a peer whose complete data fails is bad, and one whose data holds is the answer. A peer that lost its
 *    connection in that time starts that data over. A peer that was the only one to give a block of bad data is bad at once.
 *  - Ending: with no peer that could help connected, connecting or waiting for `limits.noPeersMs` the fetch fails with [METADATA_INVALID]
 *    when a peer was bad, else with [NO_PEERS]; and as in [TorrentRun], when every tracker refused and there is no peer the reason is
 *    [trackerError]. When it fails or is cancelled the trackers hear `stopped` in the background, never making the end wait.
 */
internal class MetadataFetch(
    private val env: TorrentEnv,
    private val jobId: String,
    private val link: MagnetLink,
    private val peerId: ByteArray,
    private val jobPeers: List<PeerAddr>,
    /** The most bytes of blocks that all the assemblers together hold; when it is passed the one with the most starts over. */
    private val maxHeld: Long = MAX_HELD_METADATA_BYTES,
    /** Makes the assembler once a peer's size is judged: a test counts what was built. */
    private val newAssembler: (Long, ByteArray) -> MetadataAssembler = ::MetadataAssembler,
) {
    private val limits = env.limits
    private val tickMs = limits.tickMs()
    private val inbox = Channel<Msg>(INBOX)
    private lateinit var scope: CoroutineScope

    private sealed interface Msg {
        class Reached(
            val peer: PeerId,
        ) : Msg

        class Offer(
            val peer: PeerId,
            val utId: Int,
            val size: Long,
        ) : Msg

        class Data(
            val peer: PeerId,
            val msg: MetaMsg.Data,
        ) : Msg

        /**
         * The connection is over by itself; [violation]: the peer broke the protocol (and is bad); [unable]: it cannot help (not bad);
         * else the network failed.
         */
        class Closed(
            val peer: PeerId,
            val violation: Boolean,
            val unable: Boolean,
        ) : Msg

        class Tracker(
            val addrs: List<PeerAddr>,
            val failure: String?,
        ) : Msg

        data object TrackerSilent : Msg

        data object Tick : Msg
    }

    private class Peer(
        val id: PeerId,
        val addr: PeerAddr,
        val outbox: Channel<ByteArray>,
    ) {
        lateinit var job: Job
        var ready = false

        /** Set by the peer's extension handshake: the id it wants `ut_metadata` messages under, and the size it claims. */
        var offered = false
        var utId = 0
        var size = -1L

        /** The assembler of the size it claims, once it has made an offer. */
        var assembly: Assembly? = null

        /** The blocks asked of it and not yet answered. */
        val outstanding = HashSet<Int>()

        /** When it last gave a block, or was first asked one after being idle. */
        var progressAt = 0L
    }

    /** Who was asked a block first, and when. */
    private class Claim(
        val peer: PeerId,
        val at: Long,
    )

    /** The blocks of one claimed size, and what is known about who gave them. */
    private class Assembly(
        val size: Long,
        var assembler: MetadataAssembler,
    ) {
        val claimed = HashMap<Int, Claim>()

        /** The peers (by address) whose blocks are in the assembler now. */
        val contributors = HashSet<PeerAddr>()

        /** After a failed check with several contributors: only [solo] is asked for blocks, so a failure can be put on one peer. */
        var isolated = false
        var solo: Peer? = null
        var soloSince = 0L

        /** Peers whose turn alone was over before they were done: they wait until the others have had theirs. */
        val passed = HashSet<PeerId>()
    }

    private val peers = HashMap<PeerId, Peer>()
    private val pending = ArrayDeque<PeerAddr>()
    private val inUse = HashSet<PeerAddr>()
    private val banned = HashSet<PeerAddr>()

    /** Addresses that are not offered again before the time given (a peer that could not help, or went silent). */
    private val retryAt = HashMap<PeerAddr, Long>()

    /** Addresses that could not help once: connecting to them again is no sign of hope. */
    private val everUnable = HashSet<PeerAddr>()
    private var nextId = 1
    private var lastPeerAt = 0L
    private var lastReport = 0L
    private var trackerFailure: String? = null

    /** How many peers were bad: dropped for a violation or for data that failed the check. */
    private var bad = 0

    /** One assembler for each size a peer claimed, the oldest first. */
    private val assemblies = LinkedHashMap<Long, Assembly>()

    suspend fun run(onPeers: suspend (Int) -> Unit = {}): ByteArray {
        val announcer = announcer()
        var found: ByteArray? = null
        try {
            found =
                coroutineScope {
                    scope = this
                    val now = env.clock.nowMs()
                    lastPeerAt = now
                    lastReport = now
                    val ticker =
                        launch {
                            while (true) {
                                delay(tickMs)
                                inbox.send(Msg.Tick)
                            }
                        }
                    announcer?.let {
                        launch {
                            // `started` must not overtake the `stopped` of the run before this one (a resume)
                            env.farewells[jobId]?.let { old -> withTimeoutOrNull(limits.farewellMs) { old.join() } }
                            it.run()
                        }
                    }
                    try {
                        offer(jobPeers)
                        connectMore()
                        var result: ByteArray? = null
                        while (result == null) {
                            when (val msg = inbox.receive()) {
                                is Msg.Reached -> reached(msg.peer)
                                is Msg.Offer -> offered(msg)
                                is Msg.Data -> result = data(msg)
                                is Msg.Closed -> closed(msg)
                                is Msg.Tracker -> onTracker(msg)
                                Msg.TrackerSilent -> trackerFailure = null
                                Msg.Tick -> tick(onPeers)
                            }
                        }
                        result
                    } finally {
                        ticker.cancel()
                        // the peers are children of this scope: end them, so the scope can complete
                        scope.coroutineContext.cancelChildren()
                    }
                }
            return found
        } finally {
            // the metadata is in: the run of the torrent that follows announces on its own. Any other end tells the trackers we are gone.
            if (found == null) {
                announcer?.let {
                    val farewell = env.background.launch { it.farewell(completed = false) }
                    env.farewells[jobId] = farewell
                    farewell.invokeOnCompletion { env.farewells.remove(jobId, farewell) }
                }
            }
        }
    }

    /** The trackers that heard `started` while the metadata was fetched: the download that follows does not tell them again. */
    fun startedTrackers(): Set<String> = announcerMade?.startedTrackers() ?: emptySet()

    private var announcerMade: Announcer? = null

    private fun announcer(): Announcer? {
        if (link.trackers.isEmpty()) return null
        val random = Random(peerId.contentHashCode().toLong())
        val timing = AnnounceTiming(limits.minAnnounceMs, tickMs, limits.farewellMs)
        return Announcer(
            env.trackers,
            env.clock,
            link.trackers.map { listOf(it) },
            link.infoHash,
            peerId,
            LEFT_UNKNOWN,
            { 0L },
            random,
            timing,
        ) { report ->
            when (report) {
                is AnnounceReport.Peers -> inbox.send(Msg.Tracker(report.addrs, null))
                is AnnounceReport.Failed -> inbox.send(Msg.Tracker(emptyList(), report.reason))
                AnnounceReport.NoNews -> inbox.send(Msg.TrackerSilent)
            }
        }.also { announcerMade = it }
    }

    // ---- peers

    private fun onTracker(msg: Msg.Tracker) {
        trackerFailure = msg.failure
        if (msg.failure == null) {
            offer(msg.addrs)
            connectMore()
        } else {
            checkTrackerFailure()
        }
    }

    /** Every tracker refused and nobody is left to ask: the fetch fails with the reason. */
    private fun checkTrackerFailure() {
        val reason = trackerFailure ?: return
        if (peers.isEmpty() && pending.isEmpty()) throw Fail(trackerError(reason))
    }

    private fun offer(addrs: List<PeerAddr>) {
        val now = env.clock.nowMs()
        for (a in addrs) {
            if (a in inUse || a in banned || (retryAt[a] ?: 0L) > now) continue
            inUse.add(a)
            pending.addLast(a)
            // the oldest let go are forgotten, so they can be offered again later
            if (pending.size > MAX_PENDING) inUse.remove(pending.removeFirst())
            // an address that could not help before is no news: it does not keep the fetch waiting
            if (a !in everUnable) lastPeerAt = now
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
        val peer = Peer(id, addr, Channel(Channel.UNLIMITED))
        // ATOMIC: the permit is released when the job completes, whether or not its body ever ran
        val job = scope.launch(start = CoroutineStart.ATOMIC) { runPeer(peer) }
        job.invokeOnCompletion { env.permits.release() }
        peer.job = job
        peers[id] = peer
    }

    /**
     * Forgets [peer], ends its connection and frees its place. A [ban]ned peer's address is never connected to again; any other is
     * free to be offered again, after [retryAfterMs] when that is more than 0. The blocks it held go back to the others.
     */
    private fun drop(
        peer: Peer,
        ban: Boolean = false,
        retryAfterMs: Long = 0,
    ) {
        if (peers.remove(peer.id) == null) return
        peer.job.cancel()
        peer.outbox.close()
        val now = env.clock.nowMs()
        if (ban) {
            banned.add(peer.addr)
            bad++
        } else {
            inUse.remove(peer.addr)
            if (retryAfterMs > 0) retryAt[peer.addr] = now + retryAfterMs
        }
        // a peer that could not help is no sign that there are peers: only one that made an offer gives the others more time
        if (peer.offered) lastPeerAt = now
        peer.assembly?.let { released(it, peer) }
        askAll()
        connectMore()
    }

    /** [peer] is gone from [asm]: the blocks it was asked go back, and what it gave alone cannot be mixed with another's. */
    private fun released(
        asm: Assembly,
        peer: Peer,
    ) {
        asm.claimed.values.removeAll { it.peer == peer.id }
        asm.passed.remove(peer.id)
        if (asm.solo === peer) {
            asm.solo = null
            if (asm.isolated) reset(asm)
        }
        if (peers.values.none { it.assembly === asm }) {
            // Nobody of this size is left: the blocks stay for the next peer that claims it. Taking turns was for telling several peers
            // apart, and the next ones are new.
            asm.isolated = false
            asm.solo = null
            asm.passed.clear()
        }
    }

    private fun banAddr(addr: PeerAddr) {
        val peer = peers.values.firstOrNull { it.addr == addr }
        if (peer != null) {
            drop(peer, ban = true)
        } else if (banned.add(addr)) {
            bad++
        }
    }

    private fun reached(id: PeerId) {
        val peer = peers[id] ?: return
        peer.ready = true
    }

    private fun closed(msg: Msg.Closed) {
        val peer = peers[msg.peer] ?: return
        if (msg.unable) {
            everUnable.add(peer.addr)
            drop(peer, retryAfterMs = RETRY_AFTER_MS)
        } else {
            drop(peer, ban = msg.violation)
        }
    }

    // ---- blocks

    private fun offered(msg: Msg.Offer) {
        val peer = peers[msg.peer] ?: return
        peer.offered = true
        peer.utId = msg.utId
        peer.size = msg.size
        // the peer's size was judged by its own connection: 1 to MAX_METADATA_BYTES, so nothing too big is ever built
        peer.assembly = assemblyOf(msg.size)
        lastPeerAt = env.clock.nowMs()
        ask(peer)
    }

    private fun assemblyOf(size: Long): Assembly =
        assemblies.getOrPut(size) {
            // at most perTorrent are kept: the oldest of those nobody is connected for go first
            while (assemblies.size >= limits.perTorrent) {
                val orphan = assemblies.values.firstOrNull { a -> peers.values.none { it.assembly === a } } ?: break
                assemblies.remove(orphan.size)
            }
            Assembly(size, newAssembler(size, link.infoHash))
        }

    /** Drops what [asm] holds. */
    private fun reset(asm: Assembly) {
        asm.assembler = newAssembler(asm.size, link.infoHash)
        asm.contributors.clear()
        asm.claimed.clear()
    }

    private fun askAll() {
        for (p in peers.values.toList()) ask(p)
    }

    /** Asks [peer] for blocks of its size that nobody was asked (or that another has held too long), up to [MAX_OUTSTANDING_METADATA]. */
    private fun ask(peer: Peer) {
        val asm = peer.assembly ?: return
        if (!peer.offered || peer.outstanding.size >= MAX_OUTSTANDING_METADATA) return
        if (asm.isolated) {
            if (asm.solo == null) pickSolo(asm)
            if (asm.solo !== peer) return
        }
        val now = env.clock.nowMs()
        val missing = asm.assembler.missing()
        val idle = peer.outstanding.isEmpty()
        while (peer.outstanding.size < MAX_OUTSTANDING_METADATA) {
            val next = nextBlock(asm, peer, missing, now, idle) ?: break
            if (peer.outstanding.isEmpty()) peer.progressAt = now
            asm.claimed.putIfAbsent(next, Claim(peer.id, now))
            peer.outstanding.add(next)
            peer.outbox.trySend(encodeMessage(PeerMsg.Extended(peer.utId, encodeMetaRequest(next))))
        }
    }

    private fun nextBlock(
        asm: Assembly,
        peer: Peer,
        missing: List<Int>,
        now: Long,
        idle: Boolean,
    ): Int? {
        missing.firstOrNull { it !in asm.claimed && it !in peer.outstanding }?.let { return it }
        // nothing is free: a peer that had nothing to do takes what another has held for long, and the first answer is used
        if (!idle) return null
        return missing.firstOrNull { it !in peer.outstanding && now - (asm.claimed[it]?.at ?: now) >= DUPLICATE_AFTER_MS }
    }

    /** Gives the turn to the lowest numbered peer of [asm] that has not had its turn yet (all of them again when all have). */
    private fun pickSolo(asm: Assembly) {
        if (!asm.isolated || asm.solo != null) return
        val candidates = peers.values.filter { it.assembly === asm && it.offered }.sortedBy { it.id }
        var next = candidates.firstOrNull { it.id !in asm.passed }
        if (next == null) {
            asm.passed.clear()
            next = candidates.firstOrNull() ?: return
        }
        val now = env.clock.nowMs()
        asm.solo = next
        asm.soloSince = now
        // blocks it was asked for before are on their way: they are its to give
        for (b in next.outstanding) asm.claimed[b] = Claim(next.id, now)
    }

    /** A peer that has had the turn alone for too long (for what it has to give) loses it to another, when there is one. */
    private fun passOver(
        asm: Assembly,
        now: Long,
    ) {
        val solo = asm.solo ?: return
        if (!asm.isolated || now < asm.soloSince + SOLO_GRACE_MS + asm.assembler.blockCount * SOLO_BLOCK_MS) return
        if (peers.values.none { it.assembly === asm && it.offered && it !== solo && it.id !in asm.passed }) return
        asm.passed.add(solo.id)
        asm.solo = null
        reset(asm)
        pickSolo(asm)
    }

    private fun data(msg: Msg.Data): ByteArray? {
        val peer = peers[msg.peer] ?: return null
        val block = msg.msg
        val asm = peer.assembly
        if (asm == null || !peer.outstanding.remove(block.piece)) {
            drop(peer, ban = true) // a block nobody asked it for
            return null
        }
        if (block.total != peer.size) {
            drop(peer, ban = true)
            return null
        }
        peer.progressAt = env.clock.nowMs()
        if (asm.claimed[block.piece]?.peer == peer.id) asm.claimed.remove(block.piece)
        if (asm.isolated && peer !== asm.solo) {
            // not the data being built: what it sent is let go, and it is not at fault
            ask(peer)
            return null
        }
        // a second copy of a block (one that was asked of two peers) is no contribution
        val fresh = !asm.assembler.holds(block.piece)
        if (!asm.assembler.accept(block.piece, block.block)) {
            drop(peer, ban = true)
            return null
        }
        if (fresh) {
            asm.contributors.add(peer.addr)
            if (overBudget(asm)) return null
        }
        if (asm.assembler.complete()) {
            asm.assembler.verified()?.let { return it }
            failedCheck(asm)
            return null
        }
        ask(peer)
        return null
    }

    /**
     * When all the assemblers hold more than `maxHeld` together, the one with the most starts over ([current], the one that just
     * took a block, when it is as big as the biggest). True when that was [current].
     */
    private fun overBudget(current: Assembly): Boolean {
        if (assemblies.values.sumOf { it.assembler.heldBytes } <= maxHeld) return false
        val other = assemblies.values.filter { it !== current }.maxByOrNull { it.assembler.heldBytes }
        val victim = if (other == null || current.assembler.heldBytes >= other.assembler.heldBytes) current else other
        reset(victim)
        askAll()
        return victim === current
    }

    /** The data of [asm] did not match the info hash (the assembler has dropped it). */
    private fun failedCheck(asm: Assembly) {
        val culprits = asm.contributors.toList()
        asm.contributors.clear()
        asm.claimed.clear()
        when {
            culprits.size == 1 -> {
                banAddr(culprits.single())
                askAll()
            }

            culprits.isEmpty() -> {
                askAll()
            }

            else -> {
                asm.isolated = true
                asm.solo = null
                asm.passed.clear()
                pickSolo(asm)
                askAll()
            }
        }
    }

    // ---- the clock

    private suspend fun tick(onPeers: suspend (Int) -> Unit) {
        val now = env.clock.nowMs()
        for (p in peers.values.toList()) {
            if (p.outstanding.isNotEmpty() && now - p.progressAt >= METADATA_REQUEST_TIMEOUT_MS) drop(p, retryAfterMs = RETRY_AFTER_MS)
        }
        for (a in assemblies.values.toList()) passOver(a, now)
        askAll() // a peer with nothing to do takes the blocks another has held too long
        connectMore()
        // peers that are connected, being connected to or waiting for a permit are not "no peers": the clock starts when none is left
        // (those that could not help once do not count: they come back every so often and say the same)
        if (hopeful()) lastPeerAt = now
        checkTrackerFailure()
        if (now - lastPeerAt >= limits.noPeersMs) throw Fail(if (bad > 0) METADATA_INVALID else NO_PEERS)
        // a tick may come late: due a tick early, so a report is never more than the interval after the one before
        if (now - lastReport >= limits.reportMs - tickMs) {
            lastReport = now
            onPeers(peers.values.count { it.ready })
        }
    }

    /** Whether there is a peer that may give metadata: one that made an offer, or is new, connected to or waiting for a place. */
    private fun hopeful(): Boolean = peers.values.any { it.offered || it.addr !in everUnable } || pending.any { it !in everUnable }

    // ---- one connection

    /** Reports to the run what [peer]'s connection does, until it ends; tells [Msg.Closed] unless it was cancelled. */
    private suspend fun runPeer(peer: Peer) {
        var conn: PeerConn? = null
        var violation = false
        var unable = false
        try {
            conn = env.transport.connect(peer.addr, limits.connectTimeoutMs)
            // the handshake's time starts when the connection is made: a slow connect does not eat into it
            serve(peer, conn, env.clock.nowMs())
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // closing the connection is how a cancelled peer stops its blocked read: that failure is the cancellation
            currentCoroutineContext().ensureActive()
        } catch (_: WireError) {
            currentCoroutineContext().ensureActive()
            violation = true
        } catch (_: Unable) {
            currentCoroutineContext().ensureActive()
            unable = true
        } finally {
            conn?.close()
            peer.outbox.close()
        }
        inbox.send(Msg.Closed(peer.id, violation, unable))
    }

    /** Runs until the connection ends or the peer is bad: always by an exception. */
    private suspend fun serve(
        peer: Peer,
        conn: PeerConn,
        started: Long,
    ): Unit =
        coroutineScope {
            // blocking reads and writes don't see coroutine cancellation: closing the connection is what unblocks them
            launch {
                try {
                    awaitCancellation()
                } finally {
                    conn.close()
                }
            }
            val offered = AtomicBoolean()
            val lastRead = AtomicLong(started)
            launch {
                while (true) {
                    delay(tickMs)
                    val now = env.clock.nowMs()
                    if (!offered.get()) {
                        if (now - started >= limits.handshakeMs) throw IOException("Handshake timed out")
                    } else if (now - lastRead.get() >= PEER_SILENCE_MS) {
                        throw IOException("Peer went silent")
                    }
                }
            }
            conn.write(encodeHandshake(link.infoHash, peerId, extensions = true))
            launch { for (bytes in peer.outbox) conn.write(bytes) }
            val hello = parseHandshake(readExactly(conn, HANDSHAKE_SIZE)) ?: throw WireError("Bad handshake")
            if (!hello.infoHash.contentEquals(link.infoHash)) throw WireError("Wrong info hash")
            if (hello.peerId.contentEquals(peerId)) throw Unable("Ourselves")
            if (!hello.supportsExtensions) throw Unable("No extension support")
            inbox.send(Msg.Reached(peer.id))
            peer.outbox.trySend(encodeMessage(PeerMsg.Extended(0, encodeExtHandshake(OUR_UT_METADATA))))
            val decoder = WireDecoder(maxMessage = MAX_FETCH_MESSAGE)
            val buf = ByteArray(READ_CHUNK)
            while (true) {
                val n = conn.read(buf, 0, minOf(buf.size, env.bucket.maxChunk()))
                if (n < 0) throw IOException("Closed by peer")
                val now = env.clock.nowMs()
                lastRead.set(now)
                delay(env.bucket.reserve(n.toLong(), now))
                for (msg in decoder.feed(buf, 0, n)) {
                    if (msg is PeerMsg.Extended && extended(peer, msg, offered)) offered.set(true)
                }
            }
        }

    /** Handles one extension message; true when it was the peer's extension handshake (which gave a good offer). */
    private suspend fun extended(
        peer: Peer,
        msg: PeerMsg.Extended,
        offered: AtomicBoolean,
    ): Boolean {
        when (msg.extId) {
            0 -> {
                if (offered.get()) return false
                val hello = decodeExtHandshake(msg.payload) ?: throw WireError("Bad extension handshake")
                val utId = hello.utMetadata ?: throw Unable("No ut_metadata")
                // a peer that has no metadata (yet) says no size, or 0: it cannot help, which is no fault of its own
                val size = hello.metadataSize?.takeIf { it != 0L } ?: throw Unable("No metadata size")
                // before anything is allocated for it
                if (size < 0 || size > MAX_METADATA_BYTES) throw WireError("Metadata size refused")
                inbox.send(Msg.Offer(peer.id, utId, size))
                return true
            }

            OUR_UT_METADATA -> {
                when (val m = decodeMetaMsg(msg.payload) ?: throw WireError("Bad metadata message")) {
                    is MetaMsg.Data -> inbox.send(Msg.Data(peer.id, m))
                    is MetaMsg.Reject -> throw WireError("Metadata request rejected")
                    is MetaMsg.Request -> Unit // we serve none
                }
            }

            else -> {
                Unit
            }
        }
        return false
    }

    private suspend fun readExactly(
        conn: PeerConn,
        n: Int,
    ): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = conn.read(out, got, n - got)
            if (r < 0) throw IOException("Closed by peer")
            got += r
        }
        return out
    }

    /** The peer cannot give metadata (and is not at fault): its connection ends, and it is not bad. */
    private class Unable(
        message: String,
    ) : Exception(message)

    private companion object {
        /** Messages waiting for the run: the readers wait when it is full, so a peer cannot flood it. */
        const val INBOX = 1024

        /** The most addresses kept waiting for a connection; one that offers more makes the oldest be forgotten. */
        const val MAX_PENDING = 1000

        const val READ_CHUNK = 32 * 1024
    }
}
