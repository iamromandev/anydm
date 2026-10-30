package dev.anydm.engine.queue

import app.cash.sqldelight.db.SqlDriver
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.Store
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/** Random command sequences against the queue (#552, #553), each on a new database from [newDriver]. */
abstract class QueuePropertyContract {
    protected abstract fun newDriver(): SqlDriver

    private sealed interface Op {
        data object Add : Op

        data class Pause(
            val job: Int,
        ) : Op

        data class Resume(
            val job: Int,
        ) : Op

        data class Cancel(
            val job: Int,
        ) : Op

        data class Remove(
            val job: Int,
        ) : Op

        data class Retry(
            val job: Int,
        ) : Op

        data class Reprioritize(
            val job: Int,
            val priority: Int,
        ) : Op

        data class SetLimit(
            val limit: Int,
        ) : Op

        data object PauseAll : Op

        data object ResumeAll : Op

        data class Wait(
            val millis: Long,
        ) : Op
    }

    private val kinds = listOf(DownloadKind.DIRECT, DownloadKind.DIRECT, DownloadKind.SITE, DownloadKind.TORRENT)

    // A job index picks among the jobs added so far; -1 is an id that never existed.
    private val arbJob = Arb.int(-1..9)
    private val arbOp: Arb<Op> =
        Arb.choice(
            Arb.constant(Op.Add),
            Arb.constant(Op.Add),
            arbJob.map(Op::Pause),
            arbJob.map(Op::Resume),
            arbJob.map(Op::Cancel),
            arbJob.map(Op::Remove),
            arbJob.map(Op::Retry),
            arbJob.map { Op.Reprioritize(it, it % 3) },
            Arb.int(1..4).map(Op::SetLimit),
            Arb.constant(Op.PauseAll),
            Arb.constant(Op.ResumeAll),
            Arb.long(0L..3_000L).map(Op::Wait),
        )

    @Test
    fun randomCommandsNeverExceedTheLimitOrLoseAJob() =
        runTest {
            checkAll(200, Arb.list(arbOp, 1..40)) { ops ->
                val store = Store(newDriver(), StandardTestDispatcher(testScheduler))
                val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
                try {
                    play(ops, store, scope)
                } finally {
                    scope.cancel()
                    runCurrent()
                    store.close()
                }
            }
        }

    private suspend fun TestScope.play(
        ops: List<Op>,
        store: Store,
        scope: CoroutineScope,
    ) {
        // Jobs cycle through three kinds: one works; one fails mid-transfer for good, so retry has FAILED jobs to act on;
        // one drops mid-transfer and retries, so timers, progress and waiting retries meet every command.
        val working = FakeProtocol()
        val failing = FakeProtocol(failure = FakeFailure.AtTransfer(8 * 1024, StepResult.Failed(null, "dropped")))
        val flaky = FakeProtocol(failure = FakeFailure.AtTransfer(8 * 1024, StepResult.Failed(ErrorClass.TRANSIENT, "reset")))
        val h =
            QueueHarness(
                this,
                store,
                working,
                mapOf(DownloadKind.DIRECT to working, DownloadKind.SITE to failing, DownloadKind.TORRENT to flaky),
                scope = scope,
            )
        h.queue.start()
        val added = mutableListOf<DownloadId>()
        val removed = mutableSetOf<DownloadId>()

        fun pick(index: Int) = if (index < 0 || added.isEmpty()) DownloadId(Long.MAX_VALUE) else added[index % added.size]

        for ((step, op) in ops.withIndex()) {
            when (op) {
                Op.Add -> added += h.addJob(kind = kinds[added.size % kinds.size])
                is Op.Pause -> h.queue.pause(pick(op.job))
                is Op.Resume -> h.queue.resume(pick(op.job))
                is Op.Cancel -> h.queue.cancel(pick(op.job))
                is Op.Remove -> if (h.queue.remove(pick(op.job), RemoveFiles.KEEP) is CommandResult.Done) removed += pick(op.job)
                is Op.Retry -> h.queue.retry(pick(op.job))
                is Op.Reprioritize -> h.queue.reprioritize(pick(op.job), op.priority)
                is Op.SetLimit -> h.queue.setLimit(op.limit)
                Op.PauseAll -> h.queue.pauseAll()
                Op.ResumeAll -> h.queue.resumeAll()
                is Op.Wait -> advanceTimeBy(op.millis.milliseconds)
            }
            runCurrent()
            withClue("after step $step: $op of $ops") { checkInvariants(h, added.toSet() - removed) }
        }
    }

    private suspend fun checkInvariants(
        h: QueueHarness,
        expected: Set<DownloadId>,
    ) {
        val state = h.queue.inspect()
        val jobs =
            h.store.downloads
                .list()
                .associateBy { it.id }

        jobs.keys shouldBe expected
        val holding = (state.running - state.stopping).count { jobs[it]?.status != MUXING }
        withClue("running $holding of limit ${state.limit}") { (holding <= state.limit) shouldBe true }
        // Each slot has exactly one open attempt, and no other attempt is open.
        withClue("open attempts") {
            h.store.downloads
                .openAttempts()
                .map { it.downloadId }
                .sortedBy { it.value } shouldBe state.running.sortedBy { it.value }
        }
        val now = h.clock.now()
        for (job in jobs.values) {
            if (job.status == DOWNLOADING ||
                job.status == MUXING
            ) {
                withClue("running without a slot: $job") { (job.id in state.running) shouldBe true }
            }
            if (job.id in state.running) {
                withClue("a slot before its retry time: $job") { (job.nextAttemptAt.let { it == null || it <= now }) shouldBe true }
                withClue("a failed job in a slot: $job") { (job.status == FAILED) shouldBe false }
            }
            withClue(job) { (job.status == PAUSED) shouldBe job.pauseReasons.isNotEmpty() }
            if (state.globalPause && job.id !in state.stopping) {
                withClue("global pause: $job") { (job.status in setOf(PENDING, QUEUED, DOWNLOADING, MUXING)) shouldBe false }
            }
        }
    }
}
