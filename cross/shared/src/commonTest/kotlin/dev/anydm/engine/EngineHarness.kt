package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.FixedRandom
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An [Engine] over [store] with the fakes, on the test's virtual time: by default one [FakeProtocol] for every kind,
 * serving one 64 KiB file at 16 KiB/s (4 s a job). Call `engine.stop()` at the end of each test.
 */
internal class EngineHarness(
    test: TestScope,
    val store: Store,
    val protocol: FakeProtocol = FakeProtocol(),
    protocols: Map<DownloadKind, Protocol> = DownloadKind.entries.associateWith { protocol },
    val fs: FakeFileSystem = FakeFileSystem(),
    val clock: Clock = test.virtualClock(),
    stopTimeout: Duration = 10.seconds,
    checkpointEvery: Duration = StepRunner.DEFAULT_CHECKPOINT_EVERY,
    log: Log = Log.None,
) {
    val engine =
        Engine(
            store = store,
            fileSystem = fs,
            protocols = protocols,
            defaultFolder = stepRoot,
            clock = clock,
            dispatcher = StandardTestDispatcher(test.testScheduler),
            stopTimeout = stopTimeout,
            checkpointEvery = checkpointEvery,
            retryPolicy = RetryPolicy(random = FixedRandom(0.0)),
            log = log,
        )

    private var added = 0

    /** Adds a job with one mirror and its own file name. */
    suspend fun addJob(priority: Int = 0): DownloadId =
        engine
            .add(
                NewDownload(
                    DownloadKind.DIRECT,
                    mirrors = listOf("https://example.com/file.zip"),
                    saveFolder = stepRoot.toString(),
                    fileName = "job-${++added}.zip",
                    priority = priority,
                ),
            ).shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id

    fun snapshot(id: DownloadId): DownloadSnapshot? = engine.downloads.value.find { it.download.id == id }

    fun status(id: DownloadId): DownloadStatus? = snapshot(id)?.download?.status
}
