package dev.anydm.engine.queue

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.DirectPlacement
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DuplicateCheck
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.core.FixedRandom
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.Placement
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.net.RuleEffect
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A queue over [store] with the fakes: by default one [FakeProtocol] for every kind, serving one 64 KiB file at 16 KiB/s
 * of virtual time, so a job takes 4 s. [finished] records completed jobs in the order the finish hook saw them. The
 * default [retryPolicy] has no jitter: the first retry waits exactly 2.5 s, the second 5 s, the third 10 s. A harness
 * made after a restart passes the first one's [clock], since a new virtualClock starts again at its origin.
 */
internal class QueueHarness(
    test: TestScope,
    val store: Store,
    val protocol: FakeProtocol = FakeProtocol(),
    protocols: Map<DownloadKind, Protocol> = DownloadKind.entries.associateWith { protocol },
    finish: FinishHook? = null,
    scope: CoroutineScope = test.backgroundScope,
    val clock: Clock = test.virtualClock(),
    val retryPolicy: RetryPolicy = RetryPolicy(random = FixedRandom(0.0)),
    /** A harness made after a restart passes the first one's, so the files survive it. */
    val fs: FakeFileSystem = FakeFileSystem(),
    checkpointEvery: Duration = StepRunner.DEFAULT_CHECKPOINT_EVERY,
    observer: QueueObserver = QueueObserver.None,
    duplicates: DuplicateCheck = DuplicateCheck.None,
    placement: Placement = DirectPlacement(fs),
) {
    val finished = mutableListOf<DownloadId>()
    val runner =
        StepRunner(
            fileSystem = fs,
            store = store.downloads,
            protocols = protocols,
            defaultFolder = stepRoot,
            finish =
                finish ?: object : FinishHook {
                    override suspend fun finished(
                        download: Download,
                        outputs: List<Path>,
                    ) {
                        finished += download.id
                    }
                },
            freeSpaceReserve = 0,
            duplicates = duplicates,
            placement = placement,
            checkpointEvery = checkpointEvery,
        )
    val queue =
        Queue(
            scope,
            store,
            runner,
            protocols,
            fs,
            clock,
            retryPolicy,
            observer,
            timeSource = test.testScheduler.timeSource,
            duplicates = duplicates,
        )

    private var added = 0

    /** Adds a job with one mirror and its own file name (the same name in one folder would collide when finishing). */
    suspend fun addJob(
        priority: Int = 0,
        kind: DownloadKind = DownloadKind.DIRECT,
        mirrors: List<String> = listOf("https://example.com/file.zip"),
        checksum: Checksum? = null,
        speedLimit: Long? = null,
        folder: Path = stepRoot,
    ): DownloadId {
        val added =
            queue.add(
                NewDownload(
                    kind,
                    mirrors = mirrors,
                    saveFolder = folder.toString(),
                    fileName = "job-${++added}.zip",
                    priority = priority,
                    checksum = checksum,
                    speedLimit = speedLimit,
                ),
            )
        return added.shouldBeInstanceOf<CommandResult.Done>().download!!.id
    }

    suspend fun job(id: DownloadId): Download? = store.downloads.get(id)

    suspend fun status(id: DownloadId): DownloadStatus? = job(id)?.status

    suspend fun reasons(id: DownloadId): Set<PauseReason> = job(id)?.pauseReasons.orEmpty()

    suspend fun statusesShouldBe(vararg expected: Pair<DownloadId, DownloadStatus>) {
        expected.map { (id, _) -> id to status(id) } shouldBe expected.toList()
    }
}

/** The helpers every queue test shares, each test on a new database from [newDriver]. */
abstract class QueueTestBase : StoreTestBase() {
    // advanceUntilIdle doesn't run backgroundScope, where the queue lives: move virtual time on instead.
    internal fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    internal suspend fun TestScope.started(
        limit: Int? = null,
        rules: RuleEffect = RuleEffect.None,
        harness: (TestScope) -> QueueHarness = { QueueHarness(it, newStore()) },
    ): QueueHarness =
        harness(this).also { h ->
            if (limit != null) h.store.settings.update { it.copy(concurrencyLimit = limit) }
            h.queue.start(rules)
        }

    /** A scope inside the test's background scope that a test can cancel alone, to stop a queue and start another. */
    internal fun TestScope.childScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
}
