package dev.anydm.engine.torrent

import dev.anydm.engine.http.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One announce a fake tracker client was asked for. */
internal class Call(
    val url: String,
    val event: AnnounceEvent?,
    val downloaded: Long,
    val left: Long,
)

/** Answers by URL; a URL with no answer is unreachable. Records every call. */
internal class ScriptedTrackers(
    var answer: (String, AnnounceRequest) -> TrackerReply? = { _, _ -> null },
) : TrackerClient {
    /** Takes a minute to answer, for the calls made from now on. */
    @Volatile var hang = false

    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun announce(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply {
        calls.add(Call(url, req.event, req.downloaded, req.left))
        if (hang) delay(60_000)
        return answer(url, req) ?: throw IOException("unreachable")
    }

    fun urls() = calls.map { it.url }
}

/**
 * Trackers that answer when the fake clock says so: [answers] has, per URL, the clock time it answers at and what; a URL with null
 * never answers (until it is cancelled), a URL that is not there is unreachable. Records every call.
 */
internal class ClockedTrackers(
    private val clock: FakeClock,
    private val answers: Map<String, Pair<Long, TrackerReply>?>,
) : TrackerClient {
    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun announce(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply {
        calls.add(Call(url, req.event, req.downloaded, req.left))
        val (at, reply) = answers[url] ?: awaitCancellation()
        while (clock.nowMs() < at) delay(2)
        return reply
    }

    fun urls() = calls.map { it.url }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncerTest {
    private val clock = FakeClock()

    // one thread: the trackers of a round are asked in the order of their tier, so a test can see that order
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val reports = CopyOnWriteArrayList<AnnounceReport>()
    private val peers = listOf(PeerAddr("93.184.216.34", 6881))
    private val none = emptyList<PeerAddr>()
    private val trackers = ScriptedTrackers()
    private var done = 0L

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun ok(
        found: List<PeerAddr> = peers,
        interval: Int = 1_800,
        min: Int? = null,
    ): TrackerReply = AnnounceResult(found, interval, min)

    private fun announcer(
        tiers: List<List<String>>,
        random: Random = Random(1),
        farewellMs: Long = 2_000,
        client: TrackerClient = trackers,
    ) = Announcer(
        client,
        clock,
        tiers,
        ByteArray(20) { it.toByte() },
        "-AD0001-abcdefghijkl".encodeToByteArray(),
        total = 1_000,
        downloaded = { done },
        random = random,
        timing = AnnounceTiming(tickMs = 5, farewellMs = farewellMs),
        report = { reports.add(it) },
    )

    private fun start(a: Announcer): Job = scope.launch { a.run() }

    private fun await(
        what: String,
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; calls=${trackers.urls()} reports=${reports.size}" }
            Thread.sleep(2)
        }
    }

    private fun settle() = Thread.sleep(60)

    /** Waits for the [n]th report and for the round that made it to end: only then does the announcer read the clock for its next wait. */
    private fun awaitRound(n: Int) {
        await("report $n") { reports.size == n }
        settle()
    }

    @Test
    fun theFirstAnnounceIsStartedAndItsPeersAreReported() {
        trackers.answer = { _, _ -> ok() }
        done = 250
        start(announcer(listOf(listOf("a"))))
        await("a report") { reports.size == 1 }
        val call = trackers.calls.single()
        assertEquals(AnnounceEvent.Started, call.event)
        assertEquals(250L, call.downloaded)
        assertEquals(750L, call.left)
        assertEquals(peers, assertIs<AnnounceReport.Peers>(reports.single()).addrs)
    }

    @Test
    fun theNextAnnounceComesAtTheTrackersIntervalWithNoEvent() {
        trackers.answer = { _, _ -> ok(interval = 900) }
        start(announcer(listOf(listOf("a"))))
        awaitRound(1)
        clock.advance(899_000)
        settle()
        assertEquals(1, trackers.calls.size, "not before the interval")
        clock.advance(1_000)
        await("the second") { trackers.calls.size == 2 }
        assertEquals(null, trackers.calls[1].event)
    }

    @Test
    fun anIntervalUnderAMinuteIsRaisedToAMinute() {
        trackers.answer = { _, _ -> ok(interval = 5) }
        start(announcer(listOf(listOf("a"))))
        awaitRound(1)
        clock.advance(59_000)
        settle()
        assertEquals(1, trackers.calls.size)
        clock.advance(1_000)
        await("the second") { trackers.calls.size == 2 }
    }

    @Test
    fun aRoundLongerThanTheIntervalIsNotFollowedByAnAnnounceAtOnce() {
        // the answer comes 100 s into the round: the wait (a minute at least) starts when the round is over, not when it began
        val client = ClockedTrackers(clock, mapOf("a" to (100_000L to ok(interval = 5))))
        start(announcer(listOf(listOf("a")), client = client))
        await("the first") { client.calls.size == 1 }
        clock.advance(100_000)
        await("its report") { reports.size == 1 }
        settle()
        assertEquals(1, client.calls.size, "not at once")
        clock.advance(59_000)
        settle()
        assertEquals(1, client.calls.size, "not before a minute after the round")
        clock.advance(1_000)
        await("the second") { client.calls.size == 2 }
    }

    @Test
    fun aMinIntervalAboveTheIntervalIsKept() {
        trackers.answer = { _, _ -> ok(interval = 100, min = 300) }
        start(announcer(listOf(listOf("a"))))
        awaitRound(1)
        clock.advance(299_000)
        settle()
        assertEquals(1, trackers.calls.size)
        clock.advance(1_000)
        await("the second") { trackers.calls.size == 2 }
    }

    // a tracker that says nothing for minutes must not hold up the ones that are up: "No peers" would fire before they were asked
    @Test
    fun aTrackerThatNeverAnswersDoesNotHoldUpTheNextTrackerOfItsTier() {
        val client = ClockedTrackers(clock, mapOf("slow" to null, "fast" to (100_000L to ok())))
        // the shuffle puts the silent one first: a sequential round would wait for it
        val seed = (0..50).first { listOf("slow", "fast").shuffled(Random(it)).first() == "slow" }
        start(announcer(listOf(listOf("slow", "fast")), random = Random(seed), client = client))
        await("the first asked") { client.calls.size == 1 }
        settle()
        assertEquals(listOf("slow"), client.urls(), "the next one is not asked while the first may still answer")
        clock.advance(10_000)
        await("the second asked") { client.calls.size == 2 }
        assertTrue(reports.isEmpty())
        clock.advance(90_000)
        await("the peers of the tracker that is up") { reports.size == 1 }
        assertEquals(peers, assertIs<AnnounceReport.Peers>(reports.single()).addrs)
    }

    @Test
    fun aTierThatDoesNotAnswerDoesNotHoldUpTheNextTier() {
        val client = ClockedTrackers(clock, mapOf("slow" to null, "fast" to (100_000L to ok())))
        start(announcer(listOf(listOf("slow"), listOf("fast")), client = client))
        await("the first tier asked") { client.calls.size == 1 }
        clock.advance(100_000)
        await("the peers of the second tier") { reports.size == 1 }
        assertEquals(listOf("slow", "fast"), client.urls())
        assertEquals(peers, assertIs<AnnounceReport.Peers>(reports.single()).addrs)
    }

    @Test
    fun aTrackerThatFailsAtOnceIsFollowedByTheNextWithoutWaiting() {
        trackers.answer = { url, _ -> if (url == "dead") null else ok() }
        start(announcer(listOf(listOf("dead"), listOf("up"))))
        await("a report") { reports.size == 1 }
        assertEquals(listOf("dead", "up"), trackers.urls())
    }

    @Test
    fun noMoreThanEightSilentTrackersAreOutAtOnce() {
        val urls = (0 until 20).map { "t$it" }
        val client = ClockedTrackers(clock, urls.associateWith { null })
        start(announcer(listOf(urls), client = client))
        repeat(15) {
            await("the ${it + 1}th asked") { client.calls.size >= minOf(it + 1, 8) }
            settle()
            clock.advance(10_000)
        }
        settle()
        assertEquals(8, client.calls.size, "no more while those eight are out")
    }

    @Test
    fun theTrackerThatAnsweredMovesToTheFrontOfItsTierAndIsAskedFirstNextTime() {
        val urls = (0 until 12).map { "t$it" }
        val order = urls.shuffled(Random(1)) // what the announcer makes of the tier
        val live = order.last()
        trackers.answer = { url, _ -> if (url == live) ok(interval = 100) else null }
        start(announcer(listOf(urls), random = Random(1)))
        awaitRound(1)
        assertEquals(order, trackers.urls(), "the dead ones did not keep the live one from being asked")
        trackers.calls.clear()
        clock.advance(100_000)
        await("the second round") { reports.size == 2 }
        assertEquals(listOf(live), trackers.urls(), "the second round starts with the one that answered, and stops there")
    }

    @Test
    fun aTierIsShuffledOnceAndKeepsItsOrderWhileNothingAnswers() {
        val urls = (0 until 8).map { "t$it" }
        start(announcer(listOf(urls), random = Random(5)))
        await("round one") { trackers.calls.size == 8 }
        settle()
        val first = trackers.urls()
        assertEquals(urls.toSet(), first.toSet())
        assertTrue(first != urls, "shuffled: $first")
        trackers.calls.clear()
        clock.advance(60_000)
        await("round two") { trackers.calls.size == 8 }
        assertEquals(first, trackers.urls(), "the same order again")
    }

    @Test
    fun theTiersAreTriedInOrderAndALaterOneOnlyWhenTheEarlierOnesGaveNothing() {
        trackers.answer = { url, _ -> if (url == "b1") ok() else null }
        start(announcer(listOf(listOf("a1"), listOf("b1"), listOf("c1"))))
        await("a report") { reports.size == 1 }
        assertEquals(listOf("a1", "b1"), trackers.urls(), "c1 is never needed")
    }

    @Test
    fun aTrackerThatAnswersWithNoPeersIsFollowedByTheNextOne() {
        trackers.answer = { url, _ -> if (url == "a") ok(none) else ok(peers) }
        start(announcer(listOf(listOf("a"), listOf("b"))))
        await("a report") { reports.size == 1 }
        assertEquals(listOf("a", "b"), trackers.urls())
        assertEquals(peers, assertIs<AnnounceReport.Peers>(reports.single()).addrs)
    }

    @Test
    fun aRoundWhereEveryTrackerAnsweredWithAFailureReasonReportsTheFirstReason() {
        trackers.answer = { url, _ -> TrackerFailure("no such torrent at $url") }
        start(announcer(listOf(listOf("a"), listOf("b"))))
        await("a report") { reports.size == 1 }
        assertEquals("no such torrent at a", assertIs<AnnounceReport.Failed>(reports.single()).reason)
        assertEquals(listOf("a", "b"), trackers.urls(), "every one was asked")
    }

    @Test
    fun anUnreachableTrackerAmongTheFailuresMeansNoTrackerErrorAndTheRoundTellsNoNews() {
        trackers.answer = { url, _ -> if (url == "a") TrackerFailure("nope") else null }
        start(announcer(listOf(listOf("a", "b"))))
        await("both asked") { trackers.calls.size == 2 }
        await("a report") { reports.isNotEmpty() }
        assertIs<AnnounceReport.NoNews>(reports.single(), "not every tracker answered: $reports")
    }

    @Test
    fun aMalformedAnswerIsNotAFailureReasonAndTheRoundTellsNoNews() {
        trackers.answer = { _, _ -> TrackerFailure("Bad tracker response", malformed = true) }
        start(announcer(listOf(listOf("a"))))
        await("a report") { reports.isNotEmpty() }
        assertIs<AnnounceReport.NoNews>(reports.single(), "$reports")
    }

    @Test
    fun aRoundWhereNoTrackerCouldBeReachedTellsNoNewsEveryTime() {
        start(announcer(listOf(listOf("a"))))
        await("the first") { reports.size == 1 }
        settle()
        clock.advance(60_000)
        await("the second") { reports.size == 2 }
        assertTrue(reports.all { it is AnnounceReport.NoNews }, "$reports")
    }

    @Test
    fun aClientThatThrowsSomethingOddIsAnUnreachableTrackerAndTheLoopGoesOn() {
        trackers.answer = { url, _ -> if (url == "a") throw IllegalStateException("boom") else ok() }
        start(announcer(listOf(listOf("a", "b"))))
        await("a report") { reports.size == 1 }
        assertIs<AnnounceReport.Peers>(reports.single())
    }

    @Test
    fun startedIsSentAgainUntilSomeTrackerAnswers() {
        var up = false
        trackers.answer = { _, _ -> if (up) ok() else null }
        start(announcer(listOf(listOf("a"))))
        await("the first") { trackers.calls.size == 1 }
        settle()
        clock.advance(60_000)
        await("the retry after a minute") { trackers.calls.size == 2 }
        settle()
        assertEquals(AnnounceEvent.Started, trackers.calls[1].event, "the tracker has not heard of us yet")
        up = true
        clock.advance(60_000)
        await("the third") { trackers.calls.size == 3 }
        await("its report") { reports.any { it is AnnounceReport.Peers } }
        settle()
        assertEquals(AnnounceEvent.Started, trackers.calls[2].event)
        clock.advance(1_800_000)
        await("the fourth") { trackers.calls.size == 4 }
        assertEquals(null, trackers.calls[3].event, "started is over once it was heard")
    }

    @Test
    fun aTrackerThatIsDownIsAskedAgainAMinuteLater() {
        start(announcer(listOf(listOf("a"))))
        await("the first") { trackers.calls.size == 1 }
        settle()
        clock.advance(59_000)
        settle()
        assertEquals(1, trackers.calls.size)
        clock.advance(1_000)
        await("the second") { trackers.calls.size == 2 }
    }

    // R5: stopped and completed
    @Test
    fun theFarewellSendsStoppedToTheTrackersThatAnswered() {
        trackers.answer = { url, _ -> if (url == "a") null else ok() }
        val a = announcer(listOf(listOf("a", "b")))
        val job = start(a)
        await("a report") { reports.size == 1 }
        runBlocking { job.cancel().also { job.join() } }
        trackers.calls.clear()
        done = 400
        runBlocking { a.farewell(completed = false) }
        assertEquals(listOf("b"), trackers.urls())
        assertEquals(AnnounceEvent.Stopped, trackers.calls.single().event)
        assertEquals(400L, trackers.calls.single().downloaded)
        assertEquals(600L, trackers.calls.single().left)
    }

    @Test
    fun aCompletedTorrentSendsCompletedThenStopped() {
        trackers.answer = { _, _ -> ok() }
        val a = announcer(listOf(listOf("a")))
        val job = start(a)
        await("a report") { reports.size == 1 }
        runBlocking { job.cancel().also { job.join() } }
        trackers.calls.clear()
        done = 1_000
        runBlocking { a.farewell(completed = true) }
        assertEquals(listOf(AnnounceEvent.Completed, AnnounceEvent.Stopped), trackers.calls.map { it.event })
        assertEquals(0L, trackers.calls.first().left)
    }

    @Test
    fun theFarewellSendsNothingToATrackerThatNeverAnswered() {
        val a = announcer(listOf(listOf("a")))
        runBlocking { a.farewell(completed = true) }
        assertTrue(trackers.calls.isEmpty())
    }

    @Test
    fun theFarewellIsBoundedWhateverTheTrackerDoes() {
        trackers.answer = { _, _ -> ok() }
        val a = announcer(listOf(listOf("a")), farewellMs = 300)
        val job = start(a)
        await("a report") { reports.size == 1 }
        runBlocking { job.cancel().also { job.join() } }
        trackers.hang = true
        val started = System.currentTimeMillis()
        runBlocking { a.farewell(completed = true) }
        assertTrue(System.currentTimeMillis() - started < 3_000, "gave up on time")
    }
}
