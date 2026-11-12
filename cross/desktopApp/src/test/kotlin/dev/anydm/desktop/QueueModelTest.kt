package dev.anydm.desktop

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
}
