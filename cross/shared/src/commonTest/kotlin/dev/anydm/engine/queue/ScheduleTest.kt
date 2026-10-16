package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

class ScheduleTest {
    private fun job(
        id: Long,
        priority: Int = 0,
        queuedAt: Long? = 0,
        status: DownloadStatus = QUEUED,
    ) = Download(
        id = DownloadId(id),
        kind = DownloadKind.DIRECT,
        status = status,
        priority = priority,
        queuedAt = queuedAt?.let(Instant::fromEpochMilliseconds),
    )

    private fun running(
        download: Download,
        stopping: Boolean = false,
    ) = SlotView(download.copy(status = if (download.status == QUEUED) DOWNLOADING else download.status), stopping)

    private fun ids(vararg ids: Long) = ids.map(::DownloadId)

    @Test
    fun startsTheHighestPriorityFirst() {
        val decision = schedule(listOf(job(1, priority = 0), job(2, priority = 5), job(3, priority = 3)), emptyList(), limit = 2)

        decision shouldBe Decision(start = ids(2, 3), preempt = emptyList())
    }

    @Test
    fun breaksPriorityTiesByQueuedAtThenId() {
        val waiting = listOf(job(1, queuedAt = 20), job(2, queuedAt = 10), job(4, queuedAt = 10), job(3, queuedAt = null))

        schedule(waiting, emptyList(), limit = 4).start shouldBe ids(3, 2, 4, 1)
    }

    @Test
    fun startsNothingWhenEverySlotIsTaken() {
        schedule(listOf(job(1)), listOf(running(job(2)), running(job(3))), limit = 2) shouldBe Decision(emptyList(), emptyList())
    }

    @Test
    fun aStoppingSlotStillCountsAgainstTheLimit() {
        schedule(listOf(job(1)), listOf(running(job(2), stopping = true)), limit = 1).start shouldBe emptyList()
    }

    @Test
    fun preemptsTheLowestPriorityWhenOverTheLimit() {
        val slots = listOf(running(job(1, priority = 5)), running(job(2, priority = 1)), running(job(3, priority = 3)))

        schedule(listOf(job(9, priority = 100)), slots, limit = 2) shouldBe Decision(start = emptyList(), preempt = ids(2))
    }

    @Test
    fun preemptsTheNewestOnATie() {
        val slots = listOf(running(job(1, queuedAt = 10)), running(job(2, queuedAt = 30)), running(job(3, queuedAt = 20)))

        schedule(emptyList(), slots, limit = 1).preempt shouldBe ids(2, 3)
    }

    @Test
    fun neverPreemptsMuxingOrStoppingSlots() {
        val slots = listOf(running(job(1, status = MUXING)), running(job(2, status = MUXING)), running(job(3, status = MUXING)))
        schedule(emptyList(), slots, limit = 1) shouldBe Decision(emptyList(), emptyList())

        // One slot is already leaving, so only one more has to go to get from three to one.
        val leaving = listOf(running(job(1), stopping = true), running(job(2, priority = 1)), running(job(3)))
        schedule(emptyList(), leaving, limit = 1).preempt shouldBe ids(3)
    }

    @Test
    fun aDecisionNeverExceedsTheLimit() =
        runTest {
            val arbJob =
                Arb.long(1L..30L).let { idArb ->
                    arbitrary {
                        job(
                            id = idArb.bind(),
                            priority = Arb.int(-2..2).bind(),
                            queuedAt = Arb.long(0L..5L).orNull().bind(),
                            status = Arb.element(PENDING, QUEUED, DOWNLOADING, MUXING).bind(),
                        )
                    }
                }
            checkAll(500, Arb.list(arbJob, 0..10), Arb.list(Arb.boolean(), 0..10), Arb.int(1..5)) { jobs, stoppingFlags, limit ->
                val unique = jobs.distinctBy { it.id }
                val runningCount = minOf(stoppingFlags.size, unique.size)
                val slots = unique.take(runningCount).mapIndexed { i, it -> SlotView(it, stoppingFlags[i]) }
                val waiting = unique.drop(runningCount).map { it.copy(status = QUEUED) }

                val decision = schedule(waiting, slots, limit)

                (decision.start.isEmpty() || decision.preempt.isEmpty()) shouldBe true
                if (decision.start.isNotEmpty()) (slots.size + decision.start.size <= limit) shouldBe true
                val left = slots.filter { !it.stopping && it.download.id !in decision.preempt }
                (left.size <= limit || left.all { it.download.status == MUXING }) shouldBe true
                decision.preempt.all { id -> slots.any { it.download.id == id && !it.stopping && it.download.status != MUXING } } shouldBe
                    true
                decision.start shouldBe waiting.sortedWith(waitingOrder).take(decision.start.size).map { it.id }
            }
        }
}
