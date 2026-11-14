package dev.anydm.desktop

import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.Reasons
import dev.anydm.engine.http.Span
import dev.anydm.engine.torrent.CANT_RESUME
import dev.anydm.engine.torrent.METADATA_INVALID
import dev.anydm.engine.torrent.NO_PEERS
import dev.anydm.engine.torrent.PieceSet
import dev.anydm.engine.torrent.TorrentFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueModelTest {
    private fun item(
        id: String,
        status: DownloadStatus,
    ) = DownloadItem(id, "f$id.zip", 100, if (status == DownloadStatus.Finished) 100 else 10, 0, status)

    private fun d(id: String) = item(id, DownloadStatus.Downloading)

    private fun q(id: String) = item(id, DownloadStatus.Queued)

    private fun p(id: String) = item(id, DownloadStatus.Paused)

    private fun f(id: String) = item(id, DownloadStatus.Finished)

    /** Running rows `d1..dN`, then queued rows named by [queued], in that queue order. */
    private fun state(
        running: Int,
        vararg queued: String,
    ) = QueueState(List(running) { d("d${it + 1}") } + queued.map { q(it) }, queued.toList())

    private fun QueueState.status(id: String) = list.first { it.id == id }.status

    private fun QueueState.ids(status: DownloadStatus) = list.filter { it.status == status }.map { it.id }

    @Test
    fun runningCountsOnlyDownloadingRows() {
        assertEquals(1, running(QueueState(listOf(d("1"), q("2"), p("3"), f("4")), listOf("2"))))
    }

    @Test
    fun settleStartsTheFirstQueuedRowsUpToTheLimit() {
        val out = settle(state(1, "a", "b", "c"), 3)
        assertEquals(listOf("a", "b"), out.ids(DownloadStatus.Downloading).filter { it in setOf("a", "b") })
        assertEquals(DownloadStatus.Queued, out.status("c"))
        assertEquals(listOf("c"), out.queue)
    }

    @Test
    fun settleDoesNothingWhenFullOrOverTheLimit() {
        val s = state(3, "a")
        assertEquals(s, settle(s, 3))
        assertEquals(s, settle(s, 2))
    }

    @Test
    fun addStartsWhenASlotIsFree() {
        val out = addDownload(state(1), q("n"), 2)
        assertEquals(DownloadStatus.Downloading, out.status("n"))
        assertTrue(out.queue.isEmpty())
    }

    @Test
    fun addQueuesAtTheEndWhenFullAndOverwritesTheStatus() {
        val out = addDownload(state(2, "a"), d("n"), 2)
        assertEquals(DownloadStatus.Queued, out.status("n"))
        assertEquals(listOf("a", "n"), out.queue)
        assertEquals("n", out.list.last().id)
    }

    @Test
    fun limitOneWithThreeNewDownloadsRunsOneAndQueuesTwo() {
        val out = listOf("1", "2", "3").fold(QueueState(emptyList(), emptyList())) { s, id -> addDownload(s, d(id), 1) }
        assertEquals(listOf("1"), out.ids(DownloadStatus.Downloading))
        assertEquals(listOf("2", "3"), out.queue)
    }

    @Test
    fun pausingARunningRowStartsTheFirstQueuedRow() {
        val out = pauseAll(state(2, "a", "b"), setOf("d1"), 2)
        assertEquals(DownloadStatus.Paused, out.status("d1"))
        assertEquals(DownloadStatus.Downloading, out.status("a"))
        assertEquals(listOf("b"), out.queue)
    }

    @Test
    fun pausingAQueuedRowPausesItAndClosesTheGap() {
        val out = pauseAll(state(2, "a", "b", "c"), setOf("b"), 2)
        assertEquals(DownloadStatus.Paused, out.status("b"))
        assertEquals(listOf("a", "c"), out.queue)
    }

    @Test
    fun bulkPauseMarksEveryTargetBeforeSettling() {
        val s = QueueState(listOf(d("d1"), d("d2"), q("a"), q("b")), listOf("a", "b"))
        val out = pauseAll(s, setOf("d1", "a"), 2)
        assertEquals(DownloadStatus.Paused, out.status("a"))
        assertEquals(DownloadStatus.Paused, out.status("d1"))
        assertEquals(DownloadStatus.Downloading, out.status("b"))
        assertEquals(emptyList(), out.queue)
    }

    @Test
    fun pauseIgnoresFinishedAndPausedRows() {
        val s = QueueState(listOf(f("1"), p("2")), emptyList())
        assertEquals(s, pauseAll(s, setOf("1", "2"), 2))
    }

    @Test
    fun resumeQueuesAtTheEndWhenFull() {
        val out = resumeAll(QueueState(state(2, "a").list + p("x"), listOf("a")), setOf("x"), 2)
        assertEquals(DownloadStatus.Queued, out.status("x"))
        assertEquals(listOf("a", "x"), out.queue)
    }

    @Test
    fun resumeStartsWhenASlotIsFree() {
        val out = resumeAll(QueueState(listOf(d("1"), p("x")), emptyList()), setOf("x"), 2)
        assertEquals(DownloadStatus.Downloading, out.status("x"))
        assertTrue(out.queue.isEmpty())
    }

    @Test
    fun resumeDoesNotJumpTheQueue() {
        val s = QueueState(listOf(d("1"), p("x"), q("a")), listOf("a"))
        val out = resumeAll(s, setOf("x"), 1)
        assertEquals(listOf("a", "x"), out.queue)
    }

    @Test
    fun bulkResumeGoesInListOrderStartingFirstThenQueueing() {
        val s = QueueState(listOf(d("1"), p("y"), p("x"), p("z")), emptyList())
        val out = resumeAll(s, setOf("x", "y", "z"), 2)
        assertEquals(DownloadStatus.Downloading, out.status("y"))
        assertEquals(listOf("x", "z"), out.queue)
        assertEquals(DownloadStatus.Queued, out.status("x"))
    }

    @Test
    fun removingARunningRowStartsTheFirstQueuedRow() {
        val out = removeAll(state(2, "a", "b"), setOf("d1"), 2)
        assertEquals(listOf("d2", "a", "b"), out.list.map { it.id })
        assertEquals(DownloadStatus.Downloading, out.status("a"))
        assertEquals(listOf("b"), out.queue)
    }

    @Test
    fun removingAQueuedRowClosesTheGap() {
        val out = removeAll(state(2, "a", "b"), setOf("a"), 2)
        assertEquals(listOf("b"), out.queue)
        assertEquals(listOf("d1", "d2", "b"), out.list.map { it.id })
    }

    @Test
    fun removingEveryRunningRowStartsExactlyLimitRows() {
        val out = removeAll(state(3, "a", "b", "c", "d", "e"), setOf("d1", "d2", "d3"), 3)
        assertEquals(listOf("a", "b", "c"), out.ids(DownloadStatus.Downloading))
        assertEquals(listOf("d", "e"), out.queue)
    }

    @Test
    fun removingFinishedRowsLeavesTheQueueAlone() {
        val s = QueueState(state(2, "a").list + f("z"), listOf("a"))
        val out = removeAll(s, setOf("z"), 2)
        assertEquals(listOf("a"), out.queue)
        assertEquals(DownloadStatus.Queued, out.status("a"))
    }

    @Test
    fun raisingTheLimitStartsQueuedRows() {
        val out = changeLimit(state(2, "a", "b"), 4)
        assertEquals(emptyList(), out.queue)
        assertEquals(4, running(out))
    }

    @Test
    fun loweringTheLimitStopsNothingAndStartsNothing() {
        val s = state(3, "a")
        val out = changeLimit(s, 2)
        assertEquals(s, out)
        assertEquals(QueueState(listOf(d("1")), emptyList()), changeLimit(QueueState(listOf(d("1")), emptyList()), 1))
    }

    @Test
    fun anAddAfterLoweringTheLimitQueues() {
        val out = addDownload(changeLimit(state(3), 2), d("n"), 2)
        assertEquals(DownloadStatus.Queued, out.status("n"))
        assertEquals(3, running(out))
    }

    @Test
    fun startNowStartsQueuedTargetsOverTheLimit() {
        val out = startNow(state(2, "a", "b", "c"), setOf("a", "c"))
        assertEquals(4, running(out))
        assertEquals(listOf("b"), out.queue)
        assertEquals(DownloadStatus.Queued, out.status("b"))
    }

    @Test
    fun startNowIgnoresNonQueuedTargets() {
        val s = QueueState(listOf(d("1"), p("2")), emptyList())
        assertEquals(s, startNow(s, setOf("1", "2")))
    }

    @Test
    fun startNowIsNotSettledBackAndBlocksLaterStartsWhileOverTheLimit() {
        val over = startNow(state(2, "a", "b"), setOf("a"))
        assertEquals(DownloadStatus.Downloading, over.status("a"))
        val paused = pauseAll(over, setOf("d1"), 2)
        assertEquals(DownloadStatus.Queued, paused.status("b"))
        val again = pauseAll(paused, setOf("d2"), 2)
        assertEquals(DownloadStatus.Downloading, again.status("b"))
    }

    @Test
    fun moveToTopKeepsRelativeOrder() {
        val out = moveToTop(state(2, "a", "b", "c", "d", "e"), setOf("e", "c"))
        assertEquals(listOf("c", "e", "a", "b", "d"), out.queue)
    }

    @Test
    fun moveToTopIgnoresRowsThatAreNotQueued() {
        val s = state(2, "a", "b")
        assertEquals(s, moveToTop(s, setOf("d1")))
        assertEquals(listOf("b", "a"), moveToTop(s, setOf("b", "d1")).queue)
    }

    @Test
    fun moveUpSwapsWithTheNearestRowAbove() {
        assertEquals(listOf("b", "a", "c"), moveUp(state(2, "a", "b", "c"), setOf("b")).queue)
    }

    @Test
    fun moveUpOnTheFirstRowChangesNothing() {
        val s = state(2, "a", "b")
        assertEquals(s, moveUp(s, setOf("a")))
        assertEquals(s, moveUp(s, setOf("a", "b")))
    }

    @Test
    fun moveUpSkipsOverNonTargetsPerTarget() {
        assertEquals(listOf("b", "a", "d", "c", "e"), moveUp(state(2, "a", "b", "c", "d", "e"), setOf("b", "d")).queue)
    }

    @Test
    fun moveDownSwapsWithTheNearestRowBelow() {
        assertEquals(listOf("a", "c", "b"), moveDown(state(2, "a", "b", "c"), setOf("b")).queue)
    }

    @Test
    fun moveDownOnTheLastRowChangesNothing() {
        val s = state(2, "a", "b")
        assertEquals(s, moveDown(s, setOf("b")))
    }

    @Test
    fun moveDownKeepsAGroupTogether() {
        assertEquals(listOf("c", "a", "b", "d"), moveDown(state(2, "a", "b", "c", "d"), setOf("a", "b")).queue)
    }

    @Test
    fun movesLeaveStatusesAndTheListUntouched() {
        val s = state(2, "a", "b", "c")
        assertEquals(s.list, moveToTop(s, setOf("c")).list)
        assertEquals(s.list, moveUp(s, setOf("c")).list)
        assertEquals(s.list, moveDown(s, setOf("a")).list)
    }

    @Test
    fun queuePositionIsOneBasedAndNullOutsideTheQueue() {
        val s = state(2, "a", "b")
        assertEquals(1, queuePosition(s, "a"))
        assertEquals(2, queuePosition(s, "b"))
        assertNull(queuePosition(s, "d1"))
        assertNull(queuePosition(s, "nope"))
    }

    @Test
    fun startsAfterIsNullWhenASlotIsFree() {
        assertNull(startsAfter(state(1), 2))
        assertNull(startsAfter(state(0), 1))
    }

    @Test
    fun startsAfterCountsTheRunningOverflowAndTheQueue() {
        assertEquals(1, startsAfter(state(2), 2))
        assertEquals(2, startsAfter(state(2, "a"), 2))
        assertEquals(5, startsAfter(state(3, "a", "b"), 1))
    }

    @Test
    fun invariantsHoldForConsistentStates() {
        assertTrue(invariantsHold(state(2, "a", "b"), 2))
        assertTrue(invariantsHold(state(1), 2))
        assertTrue(invariantsHold(state(3, "a"), 2))
    }

    @Test
    fun invariantsFailWhenAQueuedRowIsMissingFromTheQueue() {
        assertFalse(invariantsHold(QueueState(listOf(d("1"), d("2"), q("a")), emptyList()), 2))
    }

    @Test
    fun invariantsFailWhenAQueuedRowIsInTheQueueTwice() {
        assertFalse(invariantsHold(QueueState(listOf(d("1"), d("2"), q("a")), listOf("a", "a")), 2))
    }

    @Test
    fun invariantsFailWhenTheQueueHoldsANonQueuedRow() {
        assertFalse(invariantsHold(QueueState(listOf(d("1"), d("2"), p("a")), listOf("a")), 2))
        assertFalse(invariantsHold(QueueState(listOf(d("1"), d("2")), listOf("ghost")), 2))
    }

    @Test
    fun invariantsFailWhenASlotIsFreeButTheQueueIsNotEmpty() {
        assertFalse(invariantsHold(QueueState(listOf(d("1"), q("a")), listOf("a")), 2))
    }

    @Test
    fun randomEventsKeepTheInvariants() {
        val rnd = Random(42)
        var limit = 2
        var s = QueueState(listOf(f("f1"), f("f2"), p("p1"), p("p2")), emptyList())
        var next = 0
        var queueOfTwo = 0
        var startedOverLimit = 0
        var loweredBelowRunning = 0
        repeat(500) { step ->
            val before = s
            val beforeLimit = limit
            val ids = s.list.map { it.id }
            val targets = ids.filter { rnd.nextInt(3) == 0 }.toSet()
            val event = rnd.nextInt(10)
            s =
                when (event) {
                    0, 1, 2 -> {
                        addDownload(s, d("n${next++}"), limit)
                    }

                    3 -> {
                        pauseAll(s, targets, limit)
                    }

                    4 -> {
                        resumeAll(s, targets, limit)
                    }

                    5 -> {
                        removeAll(s, targets, limit)
                    }

                    6, 7 -> {
                        startNow(s, targets)
                    }

                    8 -> {
                        listOf(::moveToTop, ::moveUp, ::moveDown)[rnd.nextInt(3)](s, targets)
                    }

                    else -> {
                        limit = rnd.nextInt(1, 6)
                        changeLimit(s, limit)
                    }
                }
            if (s.queue.size >= 2) queueOfTwo++
            if (event in 6..7 && running(s) > limit && running(s) > running(before)) startedOverLimit++
            if (event == 9) {
                if (limit < running(before)) loweredBelowRunning++
                assertTrue(running(s) >= running(before), "step $step: changeLimit stopped a running row")
            }
            // Start now may leave running above the limit but never leaves a free slot with a waiting queue.
            assertTrue(invariantsHold(s, limit), "step $step broke the invariants: $s limit $limit")
            // Pausing a running row may start others, but settle itself never moves a Downloading row to another status.
            val settled = settle(before, beforeLimit)
            before.list.filter { it.status == DownloadStatus.Downloading }.forEach {
                assertEquals(DownloadStatus.Downloading, settled.status(it.id), "step $step: settle stopped ${it.id}")
            }
            before.list.filter { it.status == DownloadStatus.Finished }.forEach { fin ->
                val now = s.list.firstOrNull { it.id == fin.id }
                assertTrue(now == null || now.status == DownloadStatus.Finished, "step $step: finished row ${fin.id} changed")
            }
            assertEquals(
                s.list.size,
                s.list
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }
        println("random counters: queueOfTwo=$queueOfTwo startedOverLimit=$startedOverLimit loweredBelowRunning=$loweredBelowRunning")
        assertTrue(queueOfTwo > 0, "queue never held 2 rows")
        assertTrue(startedOverLimit > 0, "start now never ran above the limit")
        assertTrue(loweredBelowRunning > 0, "limit never dropped below the running count")
    }

    @Test
    fun startsAfterTextIsSingularForOne() {
        assertEquals("Starts after 1 download", startsAfterText(1))
        assertEquals("Starts after 3 downloads", startsAfterText(3))
    }

    private fun fail(id: String) = item(id, DownloadStatus.Failed).copy(error = "HTTP 404")

    @Test
    fun progressedUpdatesBytesAndSpeedWithoutChangingStatusOrQueue() {
        val s = state(1, "a")
        val out = progressed(s, "d1", 50, 200, 7)
        val row = out.list.first { it.id == "d1" }
        assertEquals(listOf(50L, 200L, 7L), listOf(row.doneBytes, row.totalBytes, row.bytesPerSec))
        assertEquals(s.list.map { it.status }, out.list.map { it.status })
        assertEquals(s.queue, out.queue)
        assertTrue(invariantsHold(out, 1))
    }

    @Test
    fun progressedIgnoresRowsThatAreNotDownloadingAnymore() {
        val s = QueueState(listOf(p("1")), emptyList())
        assertEquals(s, progressed(s, "1", 90, 100, 5))
        assertEquals(s, progressed(s, "gone", 90, 100, 5))
    }

    @Test
    fun startedStoresNameSizeValidatorsAndDone() {
        val s = state(1)
        val out =
            started(
                s,
                dev.anydm.engine.http.EngineEvent
                    .Started("d1", "x.zip", 99, "\"e\"", "Mon", 0),
            )
        val row = out.list.single()
        assertEquals(listOf("x.zip", 99L, "\"e\"", "Mon", 0L), listOf(row.name, row.totalBytes, row.etag, row.lastModified, row.doneBytes))
        val unknown =
            started(
                s,
                dev.anydm.engine.http.EngineEvent
                    .Started("d1", "x.zip", null, null, null, 5),
            )
        assertEquals(UNKNOWN_SIZE, unknown.list.single().totalBytes)
    }

    @Test
    fun finishedMarksFinishedFixesTheTotalAndStartsTheNextQueuedRow() {
        val out = finished(state(1, "a", "b"), "d1", 100, 1)
        val row = out.list.first { it.id == "d1" }
        assertEquals(DownloadStatus.Finished, row.status)
        assertEquals(listOf(100L, 100L, 0L), listOf(row.doneBytes, row.totalBytes, row.bytesPerSec))
        assertEquals(DownloadStatus.Downloading, out.status("a"))
        assertEquals(listOf("b"), out.queue)
        assertTrue(invariantsHold(out, 1))
    }

    @Test
    fun finishedCarriesTheRealNameWhenTheFinalNameWasTaken() {
        val out = finished(QueueState(listOf(d("1")), emptyList()), "1", 100, 1, name = "f1 (1).zip")
        assertEquals("f1 (1).zip", out.list.single().name)
        assertEquals(DownloadStatus.Finished, out.status("1"))
    }

    @Test
    fun finishedWithoutANameKeepsTheRowsName() {
        val out = finished(QueueState(listOf(d("1")), emptyList()), "1", 100, 1)
        assertEquals("f1.zip", out.list.single().name)
    }

    @Test
    fun finishedAfterAPauseStillFinishesTheRow() {
        val out = finished(QueueState(listOf(p("1")), emptyList()), "1", 100, 1)
        assertEquals(DownloadStatus.Finished, out.status("1"))
    }

    @Test
    fun failedKeepsTheReasonFreesTheSlotAndStartsTheNextQueuedRow() {
        val out = failed(state(1, "a"), "d1", "HTTP 404", 30, 1)
        val row = out.list.first { it.id == "d1" }
        assertEquals(DownloadStatus.Failed, row.status)
        assertEquals(listOf("HTTP 404", 30L, 0L), listOf(row.error, row.doneBytes, row.bytesPerSec))
        assertEquals(DownloadStatus.Downloading, out.status("a"))
        assertTrue(out.queue.isEmpty())
        assertTrue(invariantsHold(out, 1))
    }

    @Test
    fun failedIgnoresRowsThatWerePausedMeanwhile() {
        val s = QueueState(listOf(p("1")), emptyList())
        assertEquals(s, failed(s, "1", "Network error", 5, 1))
    }

    @Test
    fun resumeAllRetriesFailedRowsAndClearsTheError() {
        val out = resumeAll(QueueState(listOf(fail("1")), emptyList()), setOf("1"), 1)
        assertEquals(DownloadStatus.Downloading, out.status("1"))
        assertNull(out.list.single().error)
        assertTrue(invariantsHold(out, 1))
    }

    @Test
    fun retryQueuesWhenNoSlotIsFree() {
        val out = resumeAll(QueueState(listOf(d("1"), fail("2")), emptyList()), setOf("2"), 1)
        assertEquals(DownloadStatus.Queued, out.status("2"))
        assertEquals(listOf("2"), out.queue)
        assertTrue(invariantsHold(out, 1))
    }

    @Test
    fun pauseAllLeavesFailedRowsAlone() {
        val s = QueueState(listOf(fail("1")), emptyList())
        assertEquals(s, pauseAll(s, setOf("1"), 1))
    }

    private val spans = listOf(Span(0, 49, 20), Span(50, 99, 5))

    @Test
    fun startedStoresTheSpansAndNoSpansForAOneConnectionStart() {
        val segmented = started(state(1), EngineEvent.Started("d1", "x.zip", 100, null, null, 25, spans))
        assertEquals(spans, segmented.list.single().spans)
        val restarted = started(segmented, EngineEvent.Started("d1", "x.zip", 100, null, null, 0))
        assertNull(restarted.list.single().spans)
    }

    @Test
    fun progressedStoresTheSpansAndTheLiveConnections() {
        val row = progressed(state(1), "d1", 25, 100, 7, spans, 3).list.single()
        assertEquals(spans, row.spans)
        assertEquals(3, row.liveConnections)
    }

    @Test
    fun leavingDownloadingZeroesTheLiveConnections() {
        val running = progressed(state(1), "d1", 25, 100, 7, spans, 3)
        assertEquals(0, pauseAll(running, setOf("d1"), 1).list.single().liveConnections)
        assertEquals(0, failed(running, "d1", "Network error", 25, 1, spans).list.single().liveConnections)
        assertEquals(0, finished(running, "d1", 100, 1).list.single().liveConnections)
    }

    @Test
    fun failedKeepsTheSpansItCarriesAndOtherwiseTheRowsOwn() {
        val running = progressed(state(1), "d1", 25, 100, 7, spans, 3)
        val more = listOf(Span(0, 49, 30), Span(50, 99, 5))
        assertEquals(more, failed(running, "d1", "Network error", 35, 1, more).list.single().spans)
        assertEquals(spans, failed(running, "d1", "HTTP 503", 25, 1).list.single().spans)
    }

    @Test
    fun finishedDropsTheSpans() {
        val running = progressed(state(1), "d1", 25, 100, 7, spans, 3)
        assertNull(finished(running, "d1", 100, 1).list.single().spans)
    }

    @Test
    fun stoppedUpdatesDoneAndSpansOfAPausedQueuedOrFailedRowWithoutChangingItsStatus() {
        for (row in listOf(p("1"), q("1"), fail("1"))) {
            val s = QueueState(listOf(row), if (row.status == DownloadStatus.Queued) listOf("1") else emptyList())
            val out = stopped(s, "1", 30, spans)
            val got = out.list.single()
            assertEquals(listOf(30L, spans, row.status), listOf(got.doneBytes, got.spans, got.status), "${row.status}")
            assertEquals(s.queue, out.queue)
        }
    }

    @Test
    fun stoppedLeavesADownloadingRowAloneBecauseItAlreadyRunsFromTheEnginesNewerSpans() {
        val s = state(1)
        assertEquals(s, stopped(s, "d1", 30, spans))
    }

    @Test
    fun stoppedIgnoresAMissingRowAndAFinishedOne() {
        val s = QueueState(listOf(f("1")), emptyList())
        assertEquals(s, stopped(s, "1", 30, spans))
        assertEquals(s, stopped(s, "gone", 30, spans))
    }

    // M1: the identity of the part file travels with the ranges
    @Test
    fun startedProgressedAndStoppedCarryThePartIdWithTheSpans() {
        val started = started(state(1), EngineEvent.Started("d1", "x.zip", 100, null, null, 25, spans, "AA"))
        assertEquals("AA", started.list.single().partId)
        assertEquals("BB", progressed(started, "d1", 30, 100, 7, spans, 2, "BB").list.single().partId)
        val idle = QueueState(listOf(p("d1")), emptyList())
        assertEquals("CC", stopped(idle, "d1", 30, spans, "CC").list.single().partId)
        assertNull(
            started(started, EngineEvent.Started("d1", "x.zip", 100, null, null, 0)).list.single().partId,
            "a one-connection start has none",
        )
    }

    @Test
    fun failedKeepsTheRowsPartIdUnlessItCarriesSpansWithAnother() {
        val running = started(state(1), EngineEvent.Started("d1", "x.zip", 100, null, null, 25, spans, "AA"))
        assertEquals("AA", failed(running, "d1", "HTTP 503", 25, 1).list.single().partId)
        val more = listOf(Span(0, 49, 30), Span(50, 99, 5))
        val out = failed(running, "d1", "Network error", 35, 1, more, "BB").list.single()
        assertEquals(listOf(more, "BB"), listOf(out.spans, out.partId))
    }

    // m5: a failure that says the engine deleted the part file leaves no ranges on the row
    @Test
    fun aClearedFailureDropsTheRangesThePartIdAndTheDoneBytes() {
        val running = started(state(1), EngineEvent.Started("d1", "x.zip", 100, null, null, 25, spans, "AA"))
        val out = failed(running, "d1", Reasons.CHANGED, 0, 1, cleared = true).list.single()
        assertEquals(DownloadStatus.Failed, out.status)
        assertNull(out.spans)
        assertNull(out.partId)
        assertEquals(0L, out.doneBytes)
    }

    // n1
    @Test
    fun startedSeedsOneLiveConnectionBecauseTheFirstResponseIsOpen() {
        assertEquals(
            1,
            started(state(1), EngineEvent.Started("d1", "x.zip", 100, null, null, 0, spans, "AA")).list.single().liveConnections,
        )
        assertEquals(1, started(state(1), EngineEvent.Started("d1", "x.zip", 100, null, null, 0)).list.single().liveConnections)
    }

    // Torrent rows (R12, R14, NFR6)
    private val hex = "ab".repeat(20)

    private fun tor(
        status: DownloadStatus = DownloadStatus.Downloading,
        pieces: PieceSet? = PieceSet.of(8, listOf(0, 1)),
        infoHash: String? = hex,
        done: Long = 20,
    ) = DownloadItem("t", "T", 80, done, 0, status, DownloadKind.Torrent, infoHash = infoHash, pieces = pieces)

    private fun only(row: DownloadItem) = QueueState(listOf(row), emptyList())

    private val files = listOf(TorrentFile("a.bin", 50), TorrentFile("d/b.bin", 30))

    private fun magnetRow(
        status: DownloadStatus = DownloadStatus.Downloading,
        pieces: PieceSet? = null,
    ) = DownloadItem(
        "t",
        "dn name",
        UNKNOWN_SIZE,
        0,
        0,
        status,
        DownloadKind.Torrent,
        url = "magnet:?xt=urn:btih:$hex&tr=udp%3A%2F%2Ft.example%3A1",
        infoHash = hex,
        pieces = pieces,
    )

    @Test
    fun theMetadataOfAMagnetRowGivesItsRealNameSizeAndFilesAndKeepsItsStatus() {
        for (status in listOf(DownloadStatus.Downloading, DownloadStatus.Paused, DownloadStatus.Failed, DownloadStatus.Queued)) {
            val out = metadataArrived(only(magnetRow(status)), "t", "Real", 80, files).list.single()
            assertEquals(
                listOf<Any?>("Real", 80L, listOf(SampleFile("a.bin", 50), SampleFile("d/b.bin", 30)), status, 0L),
                listOf(out.name, out.totalBytes, out.files, out.status, out.doneBytes),
                "$status",
            )
            assertEquals(false, out.awaitingMetadata)
        }
    }

    // review M1: the engine tells the metadata of a copy that was there already, before Started; the row is then never "awaiting"
    @Test
    fun aMagnetRowThatStartsAndFinishesAfterItsMetadataNeverAwaitsAndNamesItsFilesByTheTorrent() {
        val started = EngineEvent.Started("t", "Real", 80, null, null, 0, pieces = PieceSet.of(8, emptyList()))
        val s = started(metadataArrived(only(magnetRow()), "t", "Real", 80, files), started)
        val row = s.list.single()
        assertEquals(false, row.awaitingMetadata)
        assertEquals(listOf("Real", 2), listOf(row.name, row.files?.size))
        val done = finished(s, "t", 80, 3, "Real").list.single()
        assertEquals(false, done.awaitingMetadata)
    }

    @Test
    fun aMagnetRowThatStartsOrFinishesWithNoFilesIsNotAwaitingAnyMore() {
        val started = EngineEvent.Started("t", "Real", 80, null, null, 0, pieces = PieceSet.of(8, emptyList()))
        val running = started(only(magnetRow()), started).list.single()
        assertEquals(null, running.files)
        assertEquals(false, running.awaitingMetadata, "it has pieces: its files are there")
        val done = finished(only(magnetRow()), "t", 80, 3, "Real").list.single()
        assertEquals(false, done.awaitingMetadata, "it is finished: its files are there")
    }

    @Test
    fun theMetadataOfARowThatRanBeforeKeepsTheNameItsFilesHave() {
        val out = metadataArrived(only(magnetRow(pieces = PieceSet.of(8, listOf(0)))), "t", "Real", 80, files).list.single()
        assertEquals(listOf("dn name", 80L), listOf(out.name, out.totalBytes))
        assertEquals(2, out.files?.size)
    }

    @Test
    fun theMetadataOfAnotherOrARemovedRowChangesNothing() {
        val s = only(magnetRow())
        assertEquals(s, metadataArrived(s, "other", "Real", 80, files))
        val empty = QueueState(emptyList(), emptyList())
        assertEquals(empty, metadataArrived(empty, "t", "Real", 80, files))
    }

    @Test
    fun aMagnetRowThatFailedForNoMetadataShowsWhyAndRetriesAsAnyFailedRow() {
        for (reason in listOf(NO_PEERS, METADATA_INVALID)) {
            val failed = failed(only(magnetRow()), "t", reason, 0, 3)
            assertEquals(listOf(DownloadStatus.Failed, reason), failed.list.single().let { listOf(it.status, it.error) })
            val retried = resumeAll(failed, setOf("t"), 3).list.single()
            assertEquals(listOf(DownloadStatus.Downloading, null), listOf(retried.status, retried.error), reason)
            assertEquals(true, retried.awaitingMetadata, "it still waits for the metadata")
        }
    }

    @Test
    fun aTorrentProgressTakesItsPiecesInFlightSetAndPeers() {
        val set = PieceSet.of(8, listOf(0, 1, 2))
        val row = progressed(only(tor()), "t", 30, 80, 5, pieces = set, inFlight = setOf(5, 6), peers = 7).list.single()
        assertEquals(listOf(set, setOf(5, 6), 7, 30L), listOf(row.pieces, row.inFlight, row.peers, row.doneBytes))
    }

    @Test
    fun aTorrentProgressWithoutPiecesIsTheRecheckAndNeitherLowersTheStoredClaimNorTheDoneBytes() {
        val before = tor()
        val row = progressed(only(before), "t", 0, 80, 0, connections = 0).list.single()
        assertEquals(before.pieces, row.pieces)
        assertEquals(20L, row.doneBytes)
    }

    @Test
    fun aFreshTorrentProgressWithoutPiecesStillMovesTheDoneBytes() {
        val row = progressed(only(tor(pieces = null, done = 0)), "t", 12, 80, 0).list.single()
        assertEquals(12L, row.doneBytes)
    }

    @Test
    fun aTorrentStartedTakesThePiecesThatHeldTheRecheck() {
        val checked = PieceSet.of(8, listOf(0))
        val row = started(only(tor()), EngineEvent.Started("t", "T", 80, null, null, 10, pieces = checked)).list.single()
        assertEquals(listOf(checked, 10L), listOf(row.pieces, row.doneBytes))
    }

    @Test
    fun stoppedAndFailedKeepThePiecesAndTakeTheEnginesWhenItSaysThem() {
        val set = PieceSet.of(8, listOf(0, 1, 2))
        val paused = only(tor(DownloadStatus.Paused))
        assertEquals(set, stopped(paused, "t", 30, emptyList(), pieces = set).list.single().pieces)
        assertEquals(paused.list.single().pieces, stopped(paused, "t", 30, emptyList()).list.single().pieces)
        val failedRow = failed(only(tor()), "t", "No peers", 20, 1, pieces = set).list.single()
        assertEquals(set, failedRow.pieces)
        assertEquals(tor().pieces, failed(only(tor()), "t", "No peers", 20, 1).list.single().pieces)
    }

    // A row that never ran has no pieces: only Started (the engine's word that the files have the row's name) gives it some, so no
    // other event can make a name the row only hoped for look settled (it would then be deleted with "also delete the files").
    private val claim = PieceSet.of(8, emptyList())

    @Test
    fun aTorrentRowThatNeverStartedTakesNoPiecesFromFailedStoppedOrProgress() {
        val fresh = tor(DownloadStatus.Downloading, pieces = null, done = 0)
        assertEquals(null, failed(only(fresh), "t", "Disk full", 0, 1, pieces = claim).list.single().pieces)
        assertEquals(
            null,
            stopped(only(fresh.copy(status = DownloadStatus.Paused)), "t", 0, emptyList(), pieces = claim).list.single().pieces,
        )
        assertEquals(null, progressed(only(fresh), "t", 0, 80, 0, pieces = claim).list.single().pieces)
    }

    @Test
    fun aStoppedThatCameAfterAStartedTheRowDidNotSeeDoesNotMoveItsDoneBytesEither() {
        val paused = tor(DownloadStatus.Paused, pieces = null, done = 0)
        val row = stopped(only(paused), "t", 30, emptyList(), pieces = PieceSet.of(8, listOf(0, 1, 2))).list.single()
        assertEquals(listOf(null, 0L), listOf(row.pieces, row.doneBytes))
    }

    @Test
    fun onceStartedATorrentRowTakesThePiecesOfLaterEvents() {
        val fresh = tor(DownloadStatus.Downloading, pieces = null, done = 0)
        val begun = started(only(fresh), EngineEvent.Started("t", "T", 80, null, null, 0, pieces = claim))
        val more = PieceSet.of(8, listOf(0, 1))
        assertEquals(more, progressed(begun, "t", 20, 80, 0, pieces = more).list.single().pieces)
        assertEquals(more, failed(begun, "t", "No peers", 20, 1, pieces = more).list.single().pieces)
        assertEquals(more, stopped(pauseAll(begun, setOf("t"), 1), "t", 20, emptyList(), pieces = more).list.single().pieces)
    }

    @Test
    fun aRowThatStopsRunningShowsNoPeersAndNothingInFlight() {
        val live = progressed(only(tor()), "t", 30, 80, 5, inFlight = setOf(3), peers = 4)
        val row = pauseAll(live, setOf("t"), 1).list.single()
        assertEquals(listOf(0, emptySet<Int>()), listOf(row.peers, row.inFlight))
        val bad = failed(live, "t", "No peers", 30, 1).list.single()
        assertEquals(listOf(0, emptySet<Int>()), listOf(bad.peers, bad.inFlight))
    }

    @Test
    fun aFinishedTorrentKeepsItsInfoHashAndHasEveryPieceSoItsMapStaysFull() {
        val row = finished(only(tor()), "t", 80, 1).list.single()
        assertEquals(listOf(hex, PieceSet.of(8, 0 until 8)), listOf(row.infoHash, row.pieces))
        assertEquals(null, finished(only(tor(pieces = null)), "t", 80, 1).list.single().pieces)
    }

    @Test
    fun resumingATorrentRowWithoutAnInfoHashFailsItWithCantResumeAndStartsNothing() {
        val out = resumeAll(only(tor(DownloadStatus.Paused, pieces = null, infoHash = null).copy(noCopy = true)), setOf("t"), 3)
        val row = out.list.single()
        assertEquals(listOf(DownloadStatus.Failed, CANT_RESUME), listOf(row.status, row.error))
        assertEquals(emptyList(), out.queue)
    }

    @Test
    fun aSimulatedTorrentRowOfThisSessionStillResumesAsItDid() {
        val row = resumeAll(only(tor(DownloadStatus.Paused, pieces = null, infoHash = null)), setOf("t"), 3).list.single()
        assertEquals(DownloadStatus.Downloading, row.status)
    }

    @Test
    fun resumingATorrentRowWithAnInfoHashStartsIt() {
        val row = resumeAll(only(tor(DownloadStatus.Paused)), setOf("t"), 3).list.single()
        assertEquals(DownloadStatus.Downloading, row.status)
    }
}
