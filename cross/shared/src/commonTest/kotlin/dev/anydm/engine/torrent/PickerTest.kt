package dev.anydm.engine.torrent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class PickerTest {
    /** A torrent of [pieces] pieces of [blocksPerPiece] blocks each. */
    private fun layoutOf(
        pieces: Int,
        blocksPerPiece: Int = 1,
    ) = Layout(listOf(pieces.toLong() * blocksPerPiece * BLOCK), blocksPerPiece.toLong() * BLOCK)

    private fun set(
        n: Int,
        vararg pieces: Int,
    ) = PieceSet.of(n, pieces.asIterable())

    private fun range(
        n: Int,
        pieces: IntRange,
    ) = PieceSet.of(n, pieces)

    private fun picker(
        layout: Layout,
        seed: Int = 1,
        have: PieceSet = PieceSet.empty(layout.pieceCount),
    ) = Picker(layout, have, Random(seed))

    private fun Picker.pieceOf(
        peer: PeerId,
        max: Int = 1,
    ): List<Int> = nextBlocks(peer, max).map { it.piece }

    // --- which blocks

    @Test
    fun onlyPiecesThePeerHasAreChosen() {
        val p = picker(layoutOf(10))
        p.peerHas(1, set(10, 3, 7))
        val got = p.pieceOf(1, 10).sorted()
        assertEquals(listOf(3, 7), got)
    }

    @Test
    fun nothingIsChosenForAPeerWeKnowNothingAbout() {
        val p = picker(layoutOf(10))
        assertEquals(emptyList(), p.nextBlocks(9, 5))
        p.peerHas(1, set(10))
        assertEquals(emptyList(), p.nextBlocks(1, 5), "a peer with nothing")
        p.peerHas(2, range(10, 0..9))
        assertEquals(emptyList(), p.nextBlocks(2, 0))
        assertEquals(emptyList(), p.nextBlocks(2, -3))
    }

    @Test
    fun aBlockIsNotChosenTwiceForTheSamePeerOutsideEndgame() {
        val p = picker(layoutOf(8))
        p.peerHas(1, range(8, 0..7))
        val first = p.nextBlocks(1, 3)
        val second = p.nextBlocks(1, 3)
        assertEquals(3, first.size)
        assertEquals(3, second.size)
        assertEquals(6, (first + second).toSet().size)
    }

    @Test
    fun piecesWeAlreadyHaveAreNeverChosen() {
        val p = picker(layoutOf(6), have = set(6, 0, 1, 2))
        p.peerHas(1, range(6, 0..5))
        assertEquals(listOf(3, 4, 5), p.pieceOf(1, 10).sorted())
    }

    @Test
    fun aPartlyRequestedPieceIsContinuedBeforeAnyNewOne() {
        val p = picker(layoutOf(10, blocksPerPiece = 4))
        p.peerHas(1, range(10, 0..9))
        val first = p.nextBlocks(1, 2)
        val piece = first.first().piece
        assertEquals(listOf(Block(piece, 0, BLOCK), Block(piece, BLOCK, BLOCK)), first)
        p.peerHas(2, range(10, 0..9))
        // peer 2 shares the piece peer 1 started, even though every piece is equally rare
        val second = p.nextBlocks(2, 2)
        assertEquals(listOf(Block(piece, 2 * BLOCK, BLOCK), Block(piece, 3 * BLOCK, BLOCK)), second)
    }

    @Test
    fun aRequestSpillsOverIntoTheNextPieceWhenOneIsNotEnough() {
        val p = picker(layoutOf(10, blocksPerPiece = 2))
        p.peerHas(1, range(10, 0..9))
        val blocks = p.nextBlocks(1, 5)
        assertEquals(5, blocks.size)
        assertEquals(3, blocks.map { it.piece }.toSet().size)
        assertEquals(5, blocks.toSet().size)
    }

    @Test
    fun theShortLastBlockKeepsItsLength() {
        val l = Layout(listOf(BLOCK + 100L), 2L * BLOCK)
        val p = picker(l)
        p.peerHas(1, set(1, 0))
        assertEquals(listOf(Block(0, 0, BLOCK), Block(0, BLOCK, 100)), p.nextBlocks(1, 5))
    }

    // --- rarest first and the first four

    @Test
    fun afterTheFirstFourPiecesTheRarestComeFirst() {
        // Peer 1 has all ten pieces, peer 2 has 0..7, peer 3 has 0..5: pieces 8 and 9 are rarest (1), then 6 and 7 (2), then 0..5 (3).
        val rarity =
            IntArray(10) {
                if (it < 6) {
                    3
                } else if (it < 8) {
                    2
                } else {
                    1
                }
            }
        for (seed in 1..60) {
            val p = picker(layoutOf(10), seed)
            p.peerHas(1, range(10, 0..9))
            p.peerHas(2, range(10, 0..7))
            p.peerHas(3, range(10, 0..5))
            val picked = ArrayList<Int>()
            repeat(10) { picked += p.pieceOf(1).single() }
            assertEquals((0..9).toList(), picked.sorted(), "seed $seed picks every piece once")
            for (i in 4 until 10) {
                val left = (0..9).filter { it !in picked.take(i) }
                assertEquals(left.minOf { rarity[it] }, rarity[picked[i]], "seed $seed pick $i of $picked")
            }
        }
    }

    @Test
    fun theFirstFourPiecesAreNotJustTheRarest() {
        val firsts = HashSet<Int>()
        var rarestFirstEveryTime = true
        for (seed in 1..200) {
            val p = picker(layoutOf(20), seed)
            p.peerHas(1, range(20, 0..19))
            p.peerHas(2, range(20, 0..18))
            val first = p.pieceOf(1).single()
            firsts += first
            if (first != 19) rarestFirstEveryTime = false
        }
        assertFalse(rarestFirstEveryTime, "piece 19 is the rarest but must not always be first")
        assertTrue(firsts.size >= 10, "the first piece should vary a lot, saw $firsts")
    }

    @Test
    fun theFirstFourStepIsSkippedWhenWeAlreadyHaveFourPieces() {
        for (seed in 1..40) {
            val p = picker(layoutOf(12), seed, have = set(12, 0, 1, 2, 3))
            p.peerHas(1, range(12, 4..11))
            p.peerHas(2, range(12, 4..10))
            assertEquals(11, p.pieceOf(1).single(), "seed $seed")
        }
    }

    @Test
    fun theFirstFourStepOnlyCountsMissingFromFour() {
        // With two pieces in hand, two more are random; the third is rarest.
        val thirds = HashSet<Int>()
        for (seed in 1..60) {
            val p = picker(layoutOf(12), seed, have = set(12, 0, 1))
            p.peerHas(1, range(12, 2..11))
            p.peerHas(2, range(12, 2..10))
            val picks = List(10) { p.pieceOf(1).single() }
            thirds += picks[0]
            // Piece 11 is the rarest (one holder). Unless one of the two random picks took it, it comes third.
            if (11 !in picks.take(2)) assertEquals(11, picks[2], "seed $seed third pick is the rarest: $picks")
        }
        assertTrue(thirds.size > 3)
    }

    @Test
    fun equallyRarePiecesAreChosenInRandomOrder() {
        val firsts = HashSet<Int>()
        for (seed in 1..100) {
            val p = picker(layoutOf(12), seed, have = range(12, 0..3))
            p.peerHas(1, range(12, 4..11))
            val pick = p.pieceOf(1).single()
            assertTrue(pick in 4..11)
            firsts += pick
        }
        assertTrue(firsts.size >= 6, "saw only $firsts")
    }

    @Test
    fun theSameSeedGivesTheSamePicks() {
        fun run(seed: Int): List<Block> {
            val p = picker(layoutOf(30, 2), seed)
            p.peerHas(1, range(30, 0..29))
            p.peerHas(2, range(30, 10..29))
            return p.nextBlocks(1, 20) + p.nextBlocks(2, 20)
        }
        assertEquals(run(7), run(7))
    }

    @Test
    fun availabilityFollowsHaveMessages() {
        // Piece 5 is held by peer 1 only, so it is the rarest ...
        val before = picker(layoutOf(6), have = range(6, 0..3))
        before.peerHas(1, set(6, 4, 5))
        before.peerHas(2, set(6, 4))
        assertEquals(5, before.pieceOf(1).single())
        // ... until two more peers announce it: now piece 4 (two holders) is rarer than piece 5 (three).
        val after = picker(layoutOf(6), have = range(6, 0..3))
        after.peerHas(1, set(6, 4, 5))
        after.peerHas(2, set(6, 4))
        after.peerJoined(3)
        after.peerHave(3, 5)
        after.peerJoined(4)
        after.peerHave(4, 5)
        assertEquals(4, after.pieceOf(1).single())
    }

    @Test
    fun aRepeatedHaveIsNotCountedTwice() {
        val picks = HashSet<Int>()
        for (seed in 1..30) {
            val p = picker(layoutOf(6), seed, have = range(6, 0..3))
            p.peerHas(1, set(6, 4, 5))
            p.peerHas(2, set(6, 4))
            p.peerJoined(3)
            repeat(3) { p.peerHave(3, 5) }
            picks += p.pieceOf(1).single()
        }
        assertEquals(setOf(4, 5), picks, "both pieces have two holders")
    }

    @Test
    fun aGonePeerNoLongerCountsAsHavingPieces() {
        val picks = HashSet<Int>()
        for (seed in 1..30) {
            val p = picker(layoutOf(6), seed, have = range(6, 0..3))
            p.peerHas(1, set(6, 4, 5))
            p.peerHas(2, set(6, 4))
            p.peerHas(3, set(6, 4))
            p.peerGone(2)
            p.peerGone(3)
            picks += p.pieceOf(1).single()
        }
        assertEquals(setOf(4, 5), picks, "piece 4 is not more common once its other holders are gone")
    }

    @Test
    fun aSecondBitfieldReplacesTheFirst() {
        val picks = HashSet<Int>()
        for (seed in 1..30) {
            val p = picker(layoutOf(6), seed, have = range(6, 0..3))
            p.peerHas(1, set(6, 4, 5))
            p.peerHas(2, set(6, 4, 5))
            p.peerHas(2, set(6, 4))
            picks += p.pieceOf(1).single()
        }
        assertEquals(setOf(5), picks, "peer 2 no longer has piece 5")
    }

    @Test
    fun aBitfieldForAnotherTorrentIsIgnored() {
        val p = picker(layoutOf(6))
        p.peerHas(1, PieceSet.empty(5).with(0))
        assertEquals(emptyList(), p.nextBlocks(1, 3))
        assertFalse(p.wants(1))
    }

    @Test
    fun haveForAPieceOutsideTheTorrentIsIgnored() {
        val p = picker(layoutOf(6))
        p.peerJoined(1)
        p.peerHave(1, 6)
        p.peerHave(1, -1)
        p.peerHave(1, Int.MAX_VALUE)
        assertEquals(emptyList(), p.nextBlocks(1, 3))
    }

    @Test
    fun wantsIsTrueOnlyWhenThePeerHasAPieceWeLack() {
        val p = picker(layoutOf(6), have = range(6, 0..2))
        p.peerHas(1, range(6, 0..2))
        assertFalse(p.wants(1))
        p.peerHave(1, 4)
        assertTrue(p.wants(1))
        assertFalse(p.wants(9))
    }

    // --- outstanding, arrivals, gone

    @Test
    fun outstandingCountsRequestsNotYetAnswered() {
        val p = picker(layoutOf(8))
        p.peerHas(1, range(8, 0..7))
        assertEquals(0, p.outstanding(1))
        val blocks = p.nextBlocks(1, 3)
        assertEquals(3, p.outstanding(1))
        p.blockArrived(1, blocks[0])
        assertEquals(2, p.outstanding(1))
        p.peerGone(1)
        assertEquals(0, p.outstanding(1))
    }

    @Test
    fun aRequestedBlockArrivingIsAcceptedAndCompletesItsPiece() {
        val p = picker(layoutOf(4, blocksPerPiece = 2))
        p.peerHas(1, range(4, 0..3))
        val blocks = p.nextBlocks(1, 2)
        val first = assertNotNull(p.blockArrived(1, blocks[0]))
        assertFalse(first.pieceComplete)
        assertFalse(first.duplicate)
        assertEquals(emptyList(), first.cancel)
        val second = assertNotNull(p.blockArrived(1, blocks[1]))
        assertTrue(second.pieceComplete)
    }

    @Test
    fun aBlockNobodyAskedForIsRefused() {
        val p = picker(layoutOf(4))
        p.peerHas(1, range(4, 0..3))
        p.peerHas(2, range(4, 0..3))
        val mine = p.nextBlocks(1, 1).single()
        assertNull(p.blockArrived(2, mine), "peer 2 was never asked")
        assertNull(p.blockArrived(1, Block(mine.piece, mine.offset + 1, mine.length)), "wrong offset")
        assertNull(p.blockArrived(1, Block((mine.piece + 1) % 4, 0, BLOCK)), "another piece's block")
        assertNull(p.blockArrived(7, mine), "unknown peer")
        assertNotNull(p.blockArrived(1, mine))
        assertNull(p.blockArrived(1, mine), "the same block twice")
    }

    @Test
    fun aGonePeersBlocksGoBackToTheOthers() {
        val p = picker(layoutOf(4))
        p.peerHas(1, range(4, 0..3))
        p.peerHas(2, range(4, 0..3))
        val taken = p.nextBlocks(1, 2)
        val others = p.nextBlocks(2, 2)
        assertEquals(4, (taken + others).toSet().size, "nothing is asked twice before endgame")
        p.peerGone(1)
        p.peerHas(3, range(4, 0..3))
        assertEquals(taken.toSet(), p.nextBlocks(3, 2).toSet())
    }

    @Test
    fun aPieceStartedByAGonePeerIsNotCountedAsStartedForTheFirstFour() {
        // Peer 1 starts three pieces and leaves; they must be as good as new for the random phase and for rarest-first.
        val p = picker(layoutOf(10), seed = 3)
        p.peerHas(1, range(10, 0..9))
        p.nextBlocks(1, 3)
        p.peerGone(1)
        assertTrue(p.inFlight.isEmpty())
    }

    // --- done and failed

    @Test
    fun aDonePieceIsRecordedAndNeverChosenAgain() {
        val p = picker(layoutOf(3))
        p.peerHas(1, range(3, 0..2))
        val blocks = p.nextBlocks(1, 3)
        blocks.forEach { p.blockArrived(1, it) }
        p.pieceDone(blocks[0].piece)
        assertTrue(p.have.has(blocks[0].piece))
        assertEquals(1, p.have.count)
        p.peerHas(2, range(3, 0..2))
        assertFalse(p.nextBlocks(2, 3).any { it.piece == blocks[0].piece })
    }

    @Test
    fun aFailedPieceIsFetchedAgainBlockByBlock() {
        val p = picker(layoutOf(1, blocksPerPiece = 3))
        p.peerHas(1, set(1, 0))
        val blocks = p.nextBlocks(1, 3)
        blocks.forEach { p.blockArrived(1, it) }
        p.pieceFailed(0)
        assertFalse(p.have.has(0))
        assertFalse(p.endgame, "the blocks are needed again, not requested")
        assertEquals(blocks, p.nextBlocks(1, 3))
        assertTrue(p.endgame)
    }

    @Test
    fun inFlightListsPiecesWithARequestedOrReceivedBlockUntilTheyAreDone() {
        val p = picker(layoutOf(6, blocksPerPiece = 2))
        p.peerHas(1, range(6, 0..5))
        val blocks = p.nextBlocks(1, 3)
        assertEquals(blocks.map { it.piece }.toSet(), p.inFlight)
        blocks.forEach { p.blockArrived(1, it) }
        val done =
            blocks
                .groupBy { it.piece }
                .filter { it.value.size == 2 }
                .keys
                .single()
        p.pieceDone(done)
        assertFalse(done in p.inFlight)
        assertEquals(blocks.map { it.piece }.toSet() - done, p.inFlight)
    }

    // --- endgame

    @Test
    fun endgameStartsOnlyWhenEveryMissingBlockIsRequested() {
        val p = picker(layoutOf(2))
        p.peerHas(1, range(2, 0..1))
        assertFalse(p.endgame)
        p.nextBlocks(1, 1)
        assertFalse(p.endgame)
        p.nextBlocks(1, 1)
        assertTrue(p.endgame)
    }

    @Test
    fun endgameDoesNotStartWhileSomePieceIsHeldByNobody() {
        val p = picker(layoutOf(3))
        p.peerHas(1, set(3, 0, 1))
        p.nextBlocks(1, 5)
        assertFalse(p.endgame, "piece 2 has no source yet")
    }

    @Test
    fun aFinishedTorrentIsNotInEndgame() {
        val p = picker(layoutOf(1))
        p.peerHas(1, set(1, 0))
        val b = p.nextBlocks(1, 1).single()
        p.blockArrived(1, b)
        p.pieceDone(0)
        assertFalse(p.endgame)
    }

    @Test
    fun inEndgameAnotherPeerIsAskedForTheLastBlockAndTheSlowerOneIsToldToCancel() {
        val p = picker(layoutOf(1))
        p.peerHas(1, set(1, 0))
        p.peerHas(2, set(1, 0))
        val slow = p.nextBlocks(1, 1).single()
        assertTrue(p.endgame)
        val dup = p.nextBlocks(2, 1).single()
        assertEquals(slow, dup)
        val arrival = assertNotNull(p.blockArrived(2, dup))
        assertEquals(listOf(1), arrival.cancel)
        assertTrue(arrival.pieceComplete)
        assertFalse(arrival.duplicate)
        assertEquals(0, p.outstanding(1), "the cancelled request no longer counts")
    }

    @Test
    fun aCancelledPeersLateBlockIsADuplicateNotAViolation() {
        val p = picker(layoutOf(1))
        p.peerHas(1, set(1, 0))
        p.peerHas(2, set(1, 0))
        val b = p.nextBlocks(1, 1).single()
        p.nextBlocks(2, 1)
        p.blockArrived(2, b)
        val late = assertNotNull(p.blockArrived(1, b))
        assertTrue(late.duplicate)
        assertFalse(late.pieceComplete)
        assertEquals(emptyList(), late.cancel)
        assertNull(p.blockArrived(1, b), "only one late block is excused")
    }

    @Test
    fun aPeerThatOwesTwoLateRepliesForOneBlockIsExcusedBothTimes() {
        val p = picker(layoutOf(1, blocksPerPiece = 4))
        p.peerHas(1, set(1, 0))
        p.peerHas(2, set(1, 0))
        val first = p.nextBlocks(1, 4)
        assertEquals(4, p.nextBlocks(2, 4).size, "endgame: peer 2 asks for the same four")
        // peer 1 wins every block: peer 2 is cancelled four times and owes four late replies
        for (b in first) assertNotNull(p.blockArrived(1, b))
        p.pieceFailed(0)
        assertEquals(4, p.nextBlocks(1, 4).size)
        assertEquals(4, p.nextBlocks(2, 4).size)
        // peer 1 wins block 0 again: peer 2 is cancelled for it a second time
        assertEquals(listOf(2), assertNotNull(p.blockArrived(1, first[0])).cancel)
        assertTrue(assertNotNull(p.blockArrived(2, first[0]), "the first late reply").duplicate)
        assertTrue(assertNotNull(p.blockArrived(2, first[0]), "the second late reply").duplicate)
        assertNull(p.blockArrived(2, first[0]), "that was all it owed")
    }

    @Test
    fun endgameDuplicatesAreLimitedAndNeverRepeatForOnePeer() {
        val p = picker(layoutOf(1))
        for (peer in 1..5) p.peerHas(peer, set(1, 0))
        assertEquals(1, p.nextBlocks(1, 1).size)
        assertEquals(1, p.nextBlocks(2, 1).size)
        assertEquals(1, p.nextBlocks(3, 1).size)
        assertEquals(emptyList(), p.nextBlocks(4, 1), "three requesters are enough")
        assertEquals(emptyList(), p.nextBlocks(1, 1), "peer 1 already has it")
    }

    @Test
    fun endgameOnlyRepeatsBlocksStillInTheAir() {
        val p = picker(layoutOf(1, blocksPerPiece = 2))
        p.peerHas(1, set(1, 0))
        p.peerHas(2, set(1, 0))
        val blocks = p.nextBlocks(1, 2)
        p.blockArrived(1, blocks[0])
        assertEquals(listOf(blocks[1]), p.nextBlocks(2, 5))
    }

    @Test
    fun endgameDuplicatesFillARequestAfterTheLastNewBlocks() {
        val p = picker(layoutOf(2))
        p.peerHas(1, range(2, 0..1))
        p.peerHas(2, range(2, 0..1))
        val first = p.nextBlocks(1, 1)
        assertEquals(1, first.size)
        val second = p.nextBlocks(2, 4)
        assertEquals(2, second.size, "the other block is new, then endgame repeats the first")
        assertEquals(setOf(0, 1), second.map { it.piece }.toSet())
    }

    @Test
    fun aPeerGoneInEndgameLeavesTheBlockWithItsOtherRequesters() {
        val p = picker(layoutOf(1))
        p.peerHas(1, set(1, 0))
        p.peerHas(2, set(1, 0))
        val b = p.nextBlocks(1, 1).single()
        p.nextBlocks(2, 1)
        p.peerGone(1)
        assertTrue(p.endgame, "peer 2 still has it requested")
        assertNotNull(p.blockArrived(2, b))
    }

    @Test
    fun endgameReturnsToNormalWhenTheOnlyRequesterLeaves() {
        val p = picker(layoutOf(1))
        p.peerHas(1, set(1, 0))
        p.nextBlocks(1, 1)
        assertTrue(p.endgame)
        p.peerGone(1)
        assertFalse(p.endgame)
    }

    // --- registration

    @Test
    fun aLateHaveFromAGonePeerDoesNotBringItBack() {
        val picks = HashSet<Int>()
        for (seed in 1..30) {
            val p = picker(layoutOf(6), seed, have = range(6, 0..3))
            p.peerHas(1, set(6, 4, 5))
            p.peerHas(2, set(6, 4))
            p.peerHas(3, set(6, 4))
            p.peerGone(3)
            p.peerHave(3, 5)
            assertFalse(p.wants(3))
            assertEquals(emptyList(), p.nextBlocks(3, 4))
            picks += p.pieceOf(1).single()
        }
        assertEquals(setOf(5), picks, "peer 3 is gone: piece 5 stays the rarest, with one holder")
    }

    @Test
    fun haveFromAPeerThatNeverJoinedIsIgnored() {
        val p = picker(layoutOf(6))
        p.peerHave(9, 2)
        assertFalse(p.wants(9))
        assertEquals(emptyList(), p.nextBlocks(9, 3))
    }

    @Test
    fun peerJoinedRegistersAPeerWithNothingAndLetsHaveMessagesCount() {
        val p = picker(layoutOf(6))
        p.peerJoined(1)
        assertFalse(p.wants(1))
        p.peerHave(1, 4)
        assertTrue(p.wants(1))
        assertEquals(listOf(4), p.pieceOf(1, 5))
    }

    @Test
    fun peerJoinedForAKnownPeerChangesNothing() {
        val p = picker(layoutOf(6))
        p.peerHas(1, set(6, 2))
        val got = p.nextBlocks(1, 1)
        p.peerJoined(1)
        assertTrue(p.wants(1))
        assertEquals(1, p.outstanding(1))
        assertNotNull(p.blockArrived(1, got.single()))
    }

    @Test
    fun theHaveSetMustBeForTheSameTorrent() {
        assertFailsWith<IllegalArgumentException> { Picker(layoutOf(4), PieceSet.empty(3), Random(1)) }
        assertFailsWith<IllegalArgumentException> { Picker(layoutOf(4), PieceSet.empty(5), Random(1)) }
        Picker(layoutOf(4), PieceSet.empty(4), Random(1))
    }

    // --- release: a live peer's requests go back

    @Test
    fun aChokingPeersRequestsGoToAnotherPeerWithoutEndgame() {
        val p = picker(layoutOf(32))
        for (peer in 1..3) p.peerHas(peer, range(32, 0..31))
        val stalled = p.nextBlocks(1, MAX_OUTSTANDING)
        val busy = p.nextBlocks(2, 8)
        assertEquals(MAX_OUTSTANDING, stalled.size)
        assertFalse(p.endgame)
        p.release(1)
        assertEquals(0, p.outstanding(1))
        assertFalse(p.endgame, "the released blocks are needed again, not requested")
        val got = p.nextBlocks(3, 24)
        assertEquals(24, got.size, "everything but what peer 2 holds")
        assertTrue(got.toSet().containsAll(stalled), "peer 3 gets the blocks peer 1 let go")
        assertTrue(got.none { it in busy })
    }

    @Test
    fun releaseKeepsThePeersAvailabilityAndItsInterestingness() {
        val picks = HashSet<Int>()
        for (seed in 1..30) {
            val p = picker(layoutOf(6), seed, have = range(6, 0..3))
            p.peerHas(1, set(6, 4, 5))
            p.peerHas(2, set(6, 4))
            p.peerHas(3, set(6, 4))
            p.release(2)
            p.release(3)
            assertTrue(p.wants(2))
            picks += p.pieceOf(1).single()
        }
        assertEquals(setOf(5), picks, "peers 2 and 3 still count as holders of piece 4")
    }

    @Test
    fun releaseForAnUnknownPeerOrOneWithNothingOutstandingIsHarmless() {
        val p = picker(layoutOf(4))
        p.release(7)
        p.peerHas(1, range(4, 0..3))
        p.release(1)
        assertEquals(4, p.nextBlocks(1, 9).size)
    }

    @Test
    fun releaseKeepsBlocksThatAlreadyArrivedAndFreesTheRest() {
        val p = picker(layoutOf(1, blocksPerPiece = 3))
        p.peerHas(1, set(1, 0))
        p.peerHas(2, set(1, 0))
        val blocks = p.nextBlocks(1, 3)
        p.blockArrived(1, blocks[0])
        p.release(1)
        assertFalse(p.endgame)
        assertEquals(listOf(blocks[1], blocks[2]), p.nextBlocks(2, 5))
        val last = assertNotNull(p.blockArrived(2, blocks[1]))
        assertFalse(last.pieceComplete)
        assertTrue(assertNotNull(p.blockArrived(2, blocks[2])).pieceComplete)
    }

    @Test
    fun inEndgameReleaseLetsAFourthPeerTakeTheLastBlockFromThreeStalledOnes() {
        val p = picker(layoutOf(1))
        for (peer in 1..4) p.peerHas(peer, set(1, 0))
        val b = p.nextBlocks(1, 1).single()
        assertEquals(listOf(b), p.nextBlocks(2, 1))
        assertEquals(listOf(b), p.nextBlocks(3, 1))
        assertEquals(emptyList(), p.nextBlocks(4, 1), "three requesters are the limit")
        p.release(1)
        assertTrue(p.endgame, "peers 2 and 3 still have it requested")
        assertEquals(listOf(b), p.nextBlocks(4, 1), "a slot is free")
        val arrival = assertNotNull(p.blockArrived(4, b))
        assertTrue(arrival.pieceComplete)
        assertEquals(setOf(2, 3), arrival.cancel.toSet(), "peer 1 let go earlier: nothing to cancel there")
        p.pieceDone(0)
        assertFalse(p.endgame)
    }

    @Test
    fun releasingEveryStalledRequesterInEndgameMakesTheBlockNeededAgain() {
        val p = picker(layoutOf(1))
        p.peerHas(1, set(1, 0))
        p.nextBlocks(1, 1)
        assertTrue(p.endgame)
        p.release(1)
        assertFalse(p.endgame)
        p.peerHas(2, set(1, 0))
        assertEquals(1, p.nextBlocks(2, 1).size)
    }

    @Test
    fun aLateBlockAfterReleaseIsADuplicateOnceNotAViolation() {
        val p = picker(layoutOf(2))
        p.peerHas(1, range(2, 0..1))
        val b = p.nextBlocks(1, 1).single()
        p.release(1)
        val late = assertNotNull(p.blockArrived(1, b))
        assertTrue(late.duplicate)
        assertFalse(late.pieceComplete)
        assertEquals(emptyList(), late.cancel)
        assertNull(p.blockArrived(1, b), "only one late block is excused")
    }

    @Test
    fun aBlockRequestedAgainAfterReleaseIsAcceptedAndItsOldReplyStillExcused() {
        val p = picker(layoutOf(1))
        p.peerHas(1, set(1, 0))
        val b = p.nextBlocks(1, 1).single()
        p.release(1)
        assertEquals(listOf(b), p.nextBlocks(1, 1))
        val fresh = assertNotNull(p.blockArrived(1, b))
        assertFalse(fresh.duplicate)
        assertTrue(fresh.pieceComplete)
        assertTrue(assertNotNull(p.blockArrived(1, b)).duplicate, "the reply to the first request is excused")
        assertNull(p.blockArrived(1, b))
    }

    @Test
    fun releaseOfAPieceNobodyHadReceivedGivesBackItsPlaceInTheFirstFourRandomPieces() {
        // Four pick-and-release rounds must not use up the random phase: afterwards the pick is still random, not always the rarest.
        val finals = HashSet<Int>()
        for (seed in 1..40) {
            val p = picker(layoutOf(12), seed)
            p.peerHas(2, range(12, 0..10))
            repeat(4) {
                p.peerHas(1, range(12, 0..11))
                p.nextBlocks(1, 1)
                p.release(1)
            }
            finals += p.pieceOf(1).single()
        }
        assertTrue(finals.size > 3, "piece 11 is the rarest but the first picks are random: saw $finals")
    }

    // --- abandoned pieces

    @Test
    fun aPieceAbandonedBeforeAllItsBlocksArrivedExcusesTheRepliesStillInTheAir() {
        val p = picker(layoutOf(1, blocksPerPiece = 3))
        p.peerHas(1, set(1, 0))
        val blocks = p.nextBlocks(1, 3)
        p.blockArrived(1, blocks[0])
        p.pieceFailed(0)
        assertEquals(0, p.outstanding(1))
        for (late in listOf(blocks[1], blocks[2])) {
            val a = assertNotNull(p.blockArrived(1, late), "$late")
            assertTrue(a.duplicate)
            assertFalse(a.pieceComplete)
        }
        assertNull(p.blockArrived(1, blocks[1]), "each late reply is excused once")
    }

    // --- scale

    @Test
    fun aHundredThousandPiecesAreChosenQuickly() {
        val n = 100_000
        val p = picker(layoutOf(n))
        p.peerHas(1, range(n, 0 until n))
        p.peerHas(2, PieceSet.of(n, (0 until n step 2)))
        val took = measureTime { repeat(300) { p.nextBlocks(1, 16) } }
        println("300 x nextBlocks over $n pieces: $took")
        assertTrue(took.inWholeMilliseconds < 300, "took $took")
    }

    @Test
    fun halfAMillionPiecesAreChosenQuicklyForEachKindOfPeer() {
        val n = 500_000
        val p = picker(layoutOf(n))
        p.peerHas(1, range(n, 0 until n))
        p.peerHas(2, PieceSet.of(n, (0 until n step 2)))
        p.peerHas(3, PieceSet.of(n, (0 until n step 7)))
        val took =
            measureTime {
                repeat(40) {
                    p.nextBlocks(1, 16)
                    p.nextBlocks(2, 16)
                    p.nextBlocks(3, 16)
                }
            }
        println("120 x nextBlocks over $n pieces: $took")
        assertTrue(took.inWholeMilliseconds < 1_000, "took $took")
    }

    @Test
    fun aPeerWithNothingWeLackIsAnsweredWithoutScanningEveryPiece() {
        val n = 500_000
        val p = picker(layoutOf(n))
        p.peerHas(1, range(n, 0 until n))
        p.peerHas(2, range(n, 0 until 10))
        p.nextBlocks(1, 10 * 1)
        // peer 1's blocks cover whatever 1 picked; ask peer 2 until its pieces are all started, then time empty answers.
        while (p.nextBlocks(2, 16).isNotEmpty()) Unit
        val took = measureTime { repeat(2_000) { p.nextBlocks(2, 16) } }
        println("2000 x empty nextBlocks over $n pieces: $took")
        assertTrue(took.inWholeMilliseconds < 100, "took $took")
    }

    @Test
    fun randomOperationsKeepTheBooksStraight() {
        val rnd = Random(77)
        repeat(30) { seed ->
            val l = layoutOf(12, blocksPerPiece = 3)
            val p = picker(l, seed)
            val requested = HashMap<PeerId, MutableSet<Block>>()
            for (peer in 1..4) p.peerHas(peer, range(12, 0..11))
            repeat(400) {
                val peer = rnd.nextInt(1, 5)
                when (rnd.nextInt(5)) {
                    0, 1 -> {
                        val got = p.nextBlocks(peer, rnd.nextInt(1, 6))
                        assertEquals(got.size, got.toSet().size)
                        assertTrue(got.none { it.piece in 0..11 && p.have.has(it.piece) }, "never a piece we have")
                        requested.getOrPut(peer) { HashSet() } += got
                    }

                    2 -> {
                        requested[peer]?.firstOrNull()?.let { b ->
                            val a = p.blockArrived(peer, b)
                            if (a != null) {
                                requested[peer]?.remove(b)
                                a.cancel.forEach { requested[it]?.remove(b) }
                                if (a.pieceComplete) {
                                    if (rnd.nextInt(4) == 0) {
                                        p.pieceFailed(b.piece)
                                    } else {
                                        p.pieceDone(b.piece)
                                    }
                                }
                            } else {
                                requested[peer]?.remove(b)
                            }
                        }
                    }

                    3 -> {
                        if (rnd.nextInt(6) == 0) {
                            p.peerGone(peer)
                            requested.remove(peer)
                            p.peerHas(peer, range(12, 0..11))
                        }
                    }

                    else -> {
                        // A peer that has every piece is given a block whenever some missing piece is not started yet.
                        if ((0..11).any { !p.have.has(it) && it !in p.inFlight }) {
                            val got = p.nextBlocks(peer, 1)
                            assertEquals(1, got.size, "an unstarted piece is there for peer $peer")
                            requested.getOrPut(peer) { HashSet() } += got
                        }
                    }
                }
                assertTrue(p.outstanding(peer) >= 0)
            }
        }
    }

    @Test
    fun theBlocksInTheAirInAllAreAlwaysWhatThePeersHaveOutstandingAdded() {
        val rnd = Random(5)
        repeat(40) { seed ->
            val l = layoutOf(10, blocksPerPiece = 3)
            val p = picker(l, seed)
            val asked = HashMap<PeerId, MutableList<Block>>()
            val peers = 1..4
            for (peer in peers) p.peerHas(peer, range(10, 0..9))

            fun check(after: String) = assertEquals(peers.sumOf { p.outstanding(it) }, p.outstandingTotal, "after $after (seed $seed)")
            check("start")
            repeat(300) {
                val peer = rnd.nextInt(1, 5)
                val what =
                    when (rnd.nextInt(9)) {
                        0, 1, 2 -> {
                            asked.getOrPut(peer) { ArrayList() } += p.nextBlocks(peer, rnd.nextInt(1, 8))
                            "nextBlocks"
                        }

                        3, 4 -> {
                            asked[peer]?.removeFirstOrNull()?.let { b ->
                                val a = p.blockArrived(peer, b)
                                a?.cancel?.forEach { asked[it]?.remove(b) }
                                if (a?.pieceComplete == true) {
                                    if (rnd.nextBoolean()) {
                                        p.pieceDone(b.piece)
                                    } else {
                                        p.pieceFailed(b.piece)
                                    }
                                }
                            }
                            "blockArrived"
                        }

                        5 -> {
                            p.release(peer)
                            asked.remove(peer)
                            "release"
                        }

                        6 -> {
                            if (rnd.nextInt(3) == 0) {
                                p.peerGone(peer)
                                asked.remove(peer)
                                p.peerHas(peer, range(10, 0..9))
                            }
                            "peerGone"
                        }

                        7 -> {
                            p.pieceFailed(rnd.nextInt(10))
                            "pieceFailed"
                        }

                        else -> {
                            p.peerHas(peer, PieceSet.of(10, (0..9).filter { rnd.nextBoolean() })) // a second bitfield replaces the first
                            "peerHas"
                        }
                    }
                check(what)
            }
        }
    }

    @Test
    fun peerHasPieceSaysWhatAPeerAnnouncedAndNothingForAPeerWeDoNotKnow() {
        val p = picker(layoutOf(10))
        p.peerHas(1, set(10, 3, 7))
        assertTrue(p.peerHasPiece(1, 3))
        assertFalse(p.peerHasPiece(1, 4))
        assertFalse(p.peerHasPiece(2, 3))
        p.peerHave(1, 4)
        assertTrue(p.peerHasPiece(1, 4))
    }
}
