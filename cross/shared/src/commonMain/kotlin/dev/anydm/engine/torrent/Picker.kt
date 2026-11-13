package dev.anydm.engine.torrent

import kotlin.random.Random

/** Names one connected peer to the [Picker]; the torrent run hands out the numbers. */
typealias PeerId = Int

/** What [Picker.blockArrived] says about a block that came in. */
data class Arrival(
    /** Peers that were also asked for this block and now need a `cancel` (endgame only). */
    val cancel: List<PeerId>,
    /** True when this block was the last one the piece was missing: hash it now. */
    val pieceComplete: Boolean,
    /** True when the block was already here (or had been cancelled): nothing to store, but the peer did nothing wrong. */
    val duplicate: Boolean,
)

/** How many pieces are picked at random before rarest first takes over, counting those we already have. */
private const val FIRST_RANDOM = 4

/** In endgame a block may be asked of this many peers at once. */
private const val ENDGAME_REQUESTERS = 3

/** A bag this small is searched whole for a piece the peer has; a bigger one is first probed at random. */
private const val SCAN_WHOLE_BAG = 64

/** How many random probes into a big bag before it is searched whole. */
private const val PROBES = 24

private const val NEEDED: Byte = 0
private const val REQUESTED: Byte = 1
private const val RECEIVED: Byte = 2

/**
 * Chooses which blocks to ask which peer for (BEP 3 requests of [BLOCK] bytes). Pure: no clock, no IO; the torrent run calls it
 * and does the sending. [random] decides ties and the first four pieces, so a seeded one makes every choice repeatable.
 *
 * Order for a peer: blocks of pieces already started (so pieces finish and can be verified), then, among the pieces the peer has
 * and we lack and nobody has started, a random one for the first four and after that the rarest among connected peers (random
 * among equals). When every missing block is requested ([endgame]), blocks still in the air may be asked of more peers, up to
 * three, and [blockArrived] says whom to cancel.
 *
 * Candidates for a new piece (missing, not started) are indexed by how many peers have them, and each peer knows how many of them it
 * holds, so a refill does not scan every piece: a peer with none is answered at once, otherwise the rarest non-empty group is probed
 * at random (a full search of one group only when the peer holds few of it).
 *
 * Peers are registered by [peerHas] (their `bitfield`) or [peerJoined] (no bitfield; `have` messages follow); every call about a peer
 * that is not registered, or has gone, is ignored.
 *
 * Not thread safe: owned by the torrent run's single coroutine.
 */
class Picker(
    private val layout: Layout,
    have: PieceSet,
    private val random: Random,
) {
    /** The verified pieces, as told by [pieceDone] (and the set given at the start). */
    var have: PieceSet = have
        private set

    private val pieceCount = layout.pieceCount

    init {
        require(have.size == pieceCount) { "the have set is for ${have.size} pieces, the torrent has $pieceCount" }
    }

    /** How many connected peers have each piece. */
    private val availability = IntArray(pieceCount)

    private class PeerState(
        val bits: ByteArray,
    ) {
        /** Blocks asked of this peer and not answered or cancelled, as `piece shl 32 or block index`. */
        val outstanding = LinkedHashSet<Long>()

        /**
         * Blocks cancelled, released or abandoned while in the air, and how many replies the peer still owes for each: it may send
         * each of them, once, without penalty. A block can be owed twice (cancelled, asked again, cancelled again).
         */
        val cancelled = HashMap<Long, Int>()

        fun owe(key: Long) {
            cancelled[key] = (cancelled[key] ?: 0) + 1
        }

        /** True when [key] was owed: one reply less is owed now. */
        fun excuse(key: Long): Boolean {
            val owed = cancelled[key] ?: return false
            if (owed > 1) cancelled[key] = owed - 1 else cancelled.remove(key)
            return true
        }

        /** How many pieces this peer has that are missing and not started: what [nextBlocks] can still start for it. */
        var candidates = 0

        fun has(piece: Int): Boolean = bits[piece shr 3].toInt() and (0x80 shr (piece and 7)) != 0
    }

    /** A piece we have started: one state byte per block, and how many are in each state. */
    private class Active(
        val blocks: ByteArray,
        /** Whether starting this piece used up one of the first random picks. */
        val random: Boolean,
    ) {
        var requested = 0
        var received = 0

        val needed: Int get() = blocks.size - requested - received
    }

    /** Pieces in no particular order with O(1) add, remove and random access; [pos] says where each piece sits, -1 when it is not here. */
    private class Bag(
        private val pos: IntArray,
    ) {
        var items = IntArray(16)
        var size = 0

        fun add(piece: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            pos[piece] = size
            items[size++] = piece
        }

        fun remove(piece: Int) {
            val at = pos[piece]
            val last = items[--size]
            items[at] = last
            pos[last] = at
            pos[piece] = -1
        }
    }

    private val peers = HashMap<PeerId, PeerState>()
    private val active = LinkedHashMap<Int, Active>()
    private val isActive = BooleanArray(pieceCount)
    private val requesters = HashMap<Long, MutableList<PeerId>>()

    private val unstartedPos = IntArray(pieceCount) { -1 }
    private val levelPos = IntArray(pieceCount) { -1 }

    /** The pieces we lack and nobody has started. */
    private val unstarted = Bag(unstartedPos)

    /** The same pieces by availability: `byLevel[n]` holds those that `n` peers have. */
    private val byLevel = ArrayList<Bag>()

    init {
        for (piece in 0 until pieceCount) if (!have.has(piece)) startable(piece)
    }

    private fun level(n: Int): Bag {
        while (byLevel.size <= n) byLevel += Bag(levelPos)
        return byLevel[n]
    }

    /** [piece] is missing and not started: it becomes a candidate for every peer that has it. */
    private fun startable(piece: Int) {
        unstarted.add(piece)
        level(availability[piece]).add(piece)
        for (state in peers.values) if (state.has(piece)) state.candidates++
    }

    /** [piece] is started or done: it is no candidate any more. */
    private fun unstartable(piece: Int) {
        unstarted.remove(piece)
        level(availability[piece]).remove(piece)
        for (state in peers.values) if (state.has(piece)) state.candidates--
    }

    private fun changeAvailability(
        piece: Int,
        delta: Int,
    ) {
        val before = availability[piece]
        availability[piece] = before + delta
        if (unstartedPos[piece] >= 0) {
            level(before).remove(piece)
            level(before + delta).add(piece)
        }
    }

    /** Blocks of missing pieces nobody has asked for. */
    private var unrequested: Long = (0 until pieceCount).sumOf { if (have.has(it)) 0L else layout.blockCount(it).toLong() }

    /** How many of the first pieces are still to be random. */
    private var randomLeft = (FIRST_RANDOM - have.count).coerceAtLeast(0)

    /** True when every block of every missing piece is requested or received, and there is still something to fetch. */
    val endgame: Boolean get() = unrequested == 0L && have.count < pieceCount

    /** The pieces with a block requested or received that are not verified yet. */
    val inFlight: Set<Int> get() = active.keys.toSet()

    /** How many requests are not answered, over all peers: what [outstanding] adds up to, kept as a running count. */
    var outstandingTotal: Int = 0
        private set

    /** Whether [peer] has said it has [piece] (in its `bitfield` or a `have`); false for a peer that is not registered. */
    fun peerHasPiece(
        peer: PeerId,
        piece: Int,
    ): Boolean = piece in 0 until pieceCount && peers[peer]?.has(piece) == true

    /** How many requests [peer] has not answered. */
    fun outstanding(peer: PeerId): Int = peers[peer]?.outstanding?.size ?: 0

    private fun key(
        piece: Int,
        index: Int,
    ): Long = (piece.toLong() shl 32) or index.toLong()

    /**
     * Registers [peer] and records the pieces it announced in its `bitfield`, replacing what it announced before. Ignored if the set
     * is for another torrent.
     */
    fun peerHas(
        peer: PeerId,
        pieces: PieceSet,
    ) {
        if (pieces.size != pieceCount) return
        peers[peer]?.let { count(it.bits, -1) }
        val bits = pieces.toBytes()
        count(bits, +1)
        val state = PeerState(bits)
        state.candidates = candidatesIn(bits)
        peers[peer]?.let { old ->
            state.outstanding += old.outstanding
            state.cancelled += old.cancelled
        }
        peers[peer] = state
    }

    /** Registers [peer] as having no pieces yet, for a peer that sends no `bitfield`; its `have` messages follow. No-op if known. */
    fun peerJoined(peer: PeerId) {
        if (peer !in peers) peers[peer] = PeerState(ByteArray((pieceCount + 7) / 8))
    }

    private fun count(
        bits: ByteArray,
        delta: Int,
    ) {
        for (i in bits.indices) {
            val b = bits[i].toInt() and 0xFF
            if (b == 0) continue
            for (bit in 0 until 8) if (b and (0x80 shr bit) != 0) changeAvailability(i * 8 + bit, delta)
        }
    }

    private fun candidatesIn(bits: ByteArray): Int {
        var n = 0
        for (i in bits.indices) {
            val b = bits[i].toInt() and 0xFF
            if (b == 0) continue
            for (bit in 0 until 8) if (b and (0x80 shr bit) != 0 && unstartedPos[i * 8 + bit] >= 0) n++
        }
        return n
    }

    /** Records that [peer] announced one more piece with `have`. Ignored for a peer that is not registered and for pieces outside the torrent. */
    fun peerHave(
        peer: PeerId,
        piece: Int,
    ) {
        if (piece !in 0 until pieceCount) return
        val state = peers[peer] ?: return
        if (state.has(piece)) return
        state.bits[piece shr 3] = (state.bits[piece shr 3].toInt() or (0x80 shr (piece and 7))).toByte()
        changeAvailability(piece, +1)
        if (unstartedPos[piece] >= 0) state.candidates++
    }

    /** Forgets [peer]: its pieces stop counting and its unanswered requests go back to being needed. */
    fun peerGone(peer: PeerId) {
        val state = peers.remove(peer) ?: return
        count(state.bits, -1)
        outstandingTotal -= state.outstanding.size
        for (key in state.outstanding) unrequest(peer, key)
    }

    /**
     * Takes back every request [peer] has not answered, for a peer that is connected but not delivering. Its blocks are needed
     * again, or in endgame just lose this peer as a requester. The peer stays registered and keeps counting in the availability of
     * its pieces. A block it still sends for a released request is a duplicate (once), not a violation. Returns the pieces the
     * released requests were for (none when there were none), so the caller can ask the peers that have them to take over.
     *
     * What the torrent run must do:
     *  - on `Choke` from a peer: call [release] for it;
     *  - a peer with outstanding requests that delivers no block for [REQUEST_TIMEOUT_MS] (60 s): call [release] and mark it snubbed:
     *    ask it for nothing more for [SNUB_MS] (120 s), then try again.
     *
     * Without this, a peer that chokes us or goes quiet while sending keep-alives would hold up to 16 blocks for good, and in endgame
     * three such peers could hold the last block forever. Only [peerGone] returned blocks before.
     */
    fun release(peer: PeerId): Set<Int> {
        val state = peers[peer] ?: return emptySet()
        outstandingTotal -= state.outstanding.size
        val pieces = HashSet<Int>()
        for (key in state.outstanding) {
            state.owe(key)
            pieces += (key shr 32).toInt()
            unrequest(peer, key)
        }
        state.outstanding.clear()
        return pieces
    }

    /** Takes [peer] off block [key]; when no one else has it requested, it is needed again. */
    private fun unrequest(
        peer: PeerId,
        key: Long,
    ) {
        val list = requesters[key] ?: return
        list.remove(peer)
        if (list.isNotEmpty()) return
        requesters.remove(key)
        val piece = (key shr 32).toInt()
        val a = active[piece] ?: return
        val index = key.toInt()
        if (a.blocks[index] != REQUESTED) return
        a.blocks[index] = NEEDED
        a.requested--
        unrequested++
        if (a.requested == 0 && a.received == 0) deactivate(piece, startable = true)
    }

    /** [piece] is no longer started. When it is still missing ([startable]) it can be picked again and its random pick is given back. */
    private fun deactivate(
        piece: Int,
        startable: Boolean,
    ) {
        val a = active.remove(piece)
        isActive[piece] = false
        if (!startable) return
        if (a != null && a.random) randomLeft++
        startable(piece)
    }

    /** Whether [peer] has a piece we are missing: reason to say `interested`. */
    fun wants(peer: PeerId): Boolean {
        val bits = peers[peer]?.bits ?: return false
        return have.lacksAnyOf(bits)
    }

    /**
     * Up to [max] blocks to request from [peer] now, marked as requested by it. Empty when the peer has nothing we need.
     * The caller keeps [outstanding] within [MAX_OUTSTANDING].
     */
    fun nextBlocks(
        peer: PeerId,
        max: Int,
    ): List<Block> {
        val state = peers[peer]
        if (state == null || max <= 0) return emptyList()
        val out = ArrayList<Block>(minOf(max, MAX_OUTSTANDING))
        for ((piece, a) in active) {
            if (out.size >= max) break
            if (a.needed > 0 && state.has(piece)) take(peer, state, piece, a, max, out)
        }
        while (out.size < max) {
            val piece = pickNewPiece(state) ?: break
            val a = Active(ByteArray(layout.blockCount(piece)), random = randomLeft > 0)
            active[piece] = a
            isActive[piece] = true
            unstartable(piece)
            if (a.random) randomLeft--
            take(peer, state, piece, a, max, out)
        }
        if (out.size < max && endgame) repeatInEndgame(peer, state, max, out)
        return out
    }

    private fun take(
        peer: PeerId,
        state: PeerState,
        piece: Int,
        a: Active,
        max: Int,
        out: MutableList<Block>,
    ) {
        val size = layout.pieceSize(piece)
        for (index in a.blocks.indices) {
            if (out.size >= max) return
            if (a.blocks[index] != NEEDED) continue
            a.blocks[index] = REQUESTED
            a.requested++
            unrequested--
            request(peer, state, key(piece, index))
            out += Block(piece, index * BLOCK, minOf(BLOCK, size - index * BLOCK))
        }
    }

    private fun request(
        peer: PeerId,
        state: PeerState,
        key: Long,
    ) {
        if (state.outstanding.add(key)) outstandingTotal++
        requesters.getOrPut(key) { ArrayList(1) } += peer
    }

    /** A random piece for the first four, else the rarest, among those [state] has, we lack and nobody has started; null if none. */
    private fun pickNewPiece(state: PeerState): Int? {
        if (state.candidates <= 0) return null
        if (randomLeft > 0) return pickFrom(unstarted, state)
        for (bag in byLevel) pickFrom(bag, state)?.let { return it }
        return null
    }

    /** A random piece of [bag] that [state] has, equally likely each; null if none. Probes first, so a bag the peer mostly has is cheap. */
    private fun pickFrom(
        bag: Bag,
        state: PeerState,
    ): Int? {
        val n = bag.size
        if (n == 0) return null
        if (n > SCAN_WHOLE_BAG) {
            repeat(PROBES) {
                val piece = bag.items[random.nextInt(n)]
                if (state.has(piece)) return piece
            }
        }
        var best = -1
        var seen = 0
        for (i in 0 until n) {
            val piece = bag.items[i]
            if (state.has(piece) && random.nextInt(++seen) == 0) best = piece
        }
        return best.takeIf { it >= 0 }
    }

    /** Fills [out] with blocks in the air that [peer] has not been asked for, those with the fewest requesters first. */
    private fun repeatInEndgame(
        peer: PeerId,
        state: PeerState,
        max: Int,
        out: MutableList<Block>,
    ) {
        for (copies in 1 until ENDGAME_REQUESTERS) {
            for ((piece, a) in active) {
                if (!state.has(piece)) continue
                val size = layout.pieceSize(piece)
                for (index in a.blocks.indices) {
                    if (out.size >= max) return
                    if (a.blocks[index] != REQUESTED) continue
                    val key = key(piece, index)
                    val askedOf = requesters[key] ?: continue
                    if (askedOf.size != copies || peer in askedOf) continue
                    request(peer, state, key)
                    out += Block(piece, index * BLOCK, minOf(BLOCK, size - index * BLOCK))
                }
            }
        }
    }

    /**
     * Records that [peer] delivered [block]. Null when [peer] was never asked for exactly this block (a protocol violation:
     * drop it). Otherwise the [Arrival] says whether the piece is now complete and whom to `cancel` (endgame).
     */
    fun blockArrived(
        peer: PeerId,
        block: Block,
    ): Arrival? {
        val state = peers[peer] ?: return null
        if (layout.blockAt(block.piece, block.offset, block.length) == null) return null
        val index = block.offset / BLOCK
        val key = key(block.piece, index)
        if (!state.outstanding.remove(key)) {
            return if (state.excuse(key)) Arrival(emptyList(), pieceComplete = false, duplicate = true) else null
        }
        outstandingTotal--
        val others = requesters[key].orEmpty().filter { it != peer }
        requesters.remove(key)
        val a = active[block.piece]
        if (a == null || a.blocks[index] != REQUESTED) return Arrival(emptyList(), pieceComplete = false, duplicate = true)
        a.blocks[index] = RECEIVED
        a.requested--
        a.received++
        for (other in others) {
            peers[other]?.let {
                if (it.outstanding.remove(key)) outstandingTotal--
                it.owe(key)
            }
        }
        return Arrival(others, pieceComplete = a.received == a.blocks.size, duplicate = false)
    }

    /** Piece [piece] was verified and written: it is ours and is never chosen again. */
    fun pieceDone(piece: Int) {
        if (piece !in 0 until pieceCount || have.has(piece)) return
        val a = active[piece]
        unrequested -= if (a != null) a.needed.toLong() else layout.blockCount(piece).toLong()
        forget(piece)
        if (a != null) deactivate(piece, startable = false) else unstartable(piece)
        have = have.with(piece)
    }

    /** Piece [piece] failed its hash, or is abandoned half received: throw its blocks away; they are needed again. */
    fun pieceFailed(piece: Int) {
        if (piece !in 0 until pieceCount || have.has(piece)) return
        val a = active[piece]
        unrequested += layout.blockCount(piece) - (a?.needed ?: layout.blockCount(piece))
        forget(piece)
        if (a != null) deactivate(piece, startable = true)
    }

    /** Drops [piece]'s requests. A peer may still send a block it was asked for, once, so those replies are excused as duplicates. */
    private fun forget(piece: Int) {
        for (index in 0 until layout.blockCount(piece)) {
            val key = key(piece, index)
            requesters.remove(key)?.forEach { id ->
                peers[id]?.let {
                    if (it.outstanding.remove(key)) {
                        outstandingTotal--
                        it.owe(key)
                    }
                }
            }
        }
    }
}
