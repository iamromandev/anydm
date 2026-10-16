package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.io.files.Path

/**
 * A queue over [store] with the fakes: by default one [FakeProtocol] for every kind, serving one 64 KiB file at 16 KiB/s
 * of virtual time, so a job takes 4 s. [finished] records completed jobs in the order the finish hook saw them.
 */
internal class QueueHarness(
    test: TestScope,
    val store: Store,
    val protocol: FakeProtocol = FakeProtocol(),
    protocols: Map<DownloadKind, Protocol> = DownloadKind.entries.associateWith { protocol },
    finish: FinishHook? = null,
    scope: CoroutineScope = test.backgroundScope,
) {
    val fs = FakeFileSystem()
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
        )
    val queue = Queue(scope, store, runner, protocols, fs, test.virtualClock())

    private var added = 0

    /** Adds a job with one mirror and its own file name (the same name in one folder would collide when finishing). */
    suspend fun addJob(
        priority: Int = 0,
        kind: DownloadKind = DownloadKind.DIRECT,
    ): DownloadId {
        val added =
            queue.add(
                NewDownload(
                    kind,
                    mirrors = listOf("https://example.com/file.zip"),
                    saveFolder = stepRoot.toString(),
                    fileName = "job-${++added}.zip",
                    priority = priority,
                ),
            )
        return added.shouldBeInstanceOf<CommandResult.Done>().download!!.id
    }

    suspend fun job(id: DownloadId): Download? = store.downloads.get(id)

    suspend fun status(id: DownloadId): DownloadStatus? = job(id)?.status

    suspend fun statusesShouldBe(vararg expected: Pair<DownloadId, DownloadStatus>) {
        expected.map { (id, _) -> id to status(id) } shouldBe expected.toList()
    }
}
