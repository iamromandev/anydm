package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.virtualEpoch
import dev.anydm.engine.store.DATABASE_FILE
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The queue across a restart: the same database file opened by a new store and queue. */
class QueueRestartTest {
    private val root = newTempFolder("anydm-queue-test-")
    private val file = Path(root, DATABASE_FILE)
    private val stores = mutableListOf<Store>()

    @AfterTest
    fun cleanUp() {
        stores.forEach { it.close() }
        deleteRecursively(root)
    }

    private fun TestScope.open(): Store = Store(openDatabaseFile(file), StandardTestDispatcher(testScheduler)).also { stores += it }

    // A queue on its own scope, so the test can stop it the way quitting the app would.
    private fun TestScope.harness(store: Store = open()): Pair<QueueHarness, CoroutineScope> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        return QueueHarness(this, store, scope = scope) to scope
    }

    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    private suspend fun Store.put(download: Download) = downloads.update(download)

    @Test
    fun globalPauseSurvivesARestart() =
        runTest {
            val (before, scope) = harness()
            before.queue.start()
            val ids = List(2) { before.addJob() }
            advanceTimeBy(1.seconds)
            before.queue.pauseAll()
            scope.cancel()
            runCurrent()

            val (after, _) = harness()
            after.queue.start()
            settle()

            after.queue.inspect().running shouldBe emptySet()
            after.store.settings
                .load()
                .globalPause shouldBe true
            ids.map { after.job(it)!!.pauseReasons } shouldBe List(2) { setOf(PauseReason.GLOBAL) }
        }

    @Test
    fun startRequeuesJobsLeftRunning() =
        runTest {
            val (setup, scope) = harness()
            setup.queue.start()
            setup.queue.pauseAll()
            val downloading = setup.addJob()
            val muxing = setup.addJob()
            scope.cancel()
            setup.store.put(setup.job(downloading)!!.copy(status = DOWNLOADING, pauseReasons = emptySet()))
            setup.store.put(setup.job(muxing)!!.copy(status = MUXING, pauseReasons = emptySet()))
            setup.store.settings.update { it.copy(globalPause = false) }

            val (after, _) = harness(setup.store)
            after.queue.start()
            settle()

            after.statusesShouldBe(downloading to COMPLETED, muxing to COMPLETED)
        }

    @Test
    fun startHealsAHalfWrittenGlobalPause() =
        runTest {
            val (setup, scope) = harness()
            setup.queue.start()
            setup.queue.pauseAll()
            val waiting = setup.addJob()
            val held = setup.addJob()
            scope.cancel()
            // The setting is on, but this job never got its GLOBAL reason.
            setup.store.put(setup.job(waiting)!!.copy(status = QUEUED, pauseReasons = emptySet()))

            val (after, _) = harness(setup.store)
            after.queue.start()
            settle()

            after.job(waiting)!!.status shouldBe PAUSED
            after.job(waiting)!!.pauseReasons shouldBe setOf(PauseReason.GLOBAL)
            after.job(held)!!.status shouldBe PAUSED
        }

    @Test
    fun startReleasesGlobalWhenTheSettingIsOff() =
        runTest {
            val (setup, scope) = harness()
            setup.queue.start()
            setup.queue.pauseAll()
            val held = setup.addJob()
            scope.cancel()
            setup.store.settings.update { it.copy(globalPause = false) }

            val (after, _) = harness(setup.store)
            after.queue.start()
            settle()

            after.status(held) shouldBe COMPLETED
        }

    @Test
    fun startGivesWaitingJobsWithoutQueuedAtOne() =
        runTest {
            val (setup, scope) = harness()
            setup.queue.start()
            setup.queue.pauseAll()
            val ids: List<DownloadId> = List(2) { setup.addJob() }
            scope.cancel()
            for (id in ids) setup.store.put(setup.job(id)!!.copy(status = PENDING, pauseReasons = emptySet(), queuedAt = null))
            setup.store.settings.update { it.copy(globalPause = false, concurrencyLimit = 1) }
            // Its virtual clock starts at virtualEpoch when created; the queue starts 7 s later.
            val (after, _) = harness(setup.store)
            advanceTimeBy(7.seconds)

            after.queue.start()

            ids.map { after.job(it)!!.queuedAt } shouldBe List(2) { virtualEpoch + 7.seconds }
        }
}
