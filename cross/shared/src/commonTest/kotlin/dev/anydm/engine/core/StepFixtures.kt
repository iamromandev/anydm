package dev.anydm.engine.core

import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Limiter
import dev.anydm.engine.protocol.Meter
import dev.anydm.engine.store.InMemoryPlanStore
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.time.Duration

/** A save folder for the fakes; nothing is created there. */
internal val stepRoot: Path = Path(SystemTemporaryDirectory, "anydm-step-runner")

internal fun aPendingDownload(
    kind: DownloadKind = DownloadKind.DIRECT,
    saveFolder: String? = stepRoot.toString(),
    fileName: String? = null,
    size: Long? = null,
    id: Long = 1,
) = Download(DownloadId(id), kind, saveFolder = saveFolder, fileName = fileName, size = size)

internal fun aMirror(downloadId: DownloadId = DownloadId(1)) =
    Mirror(MirrorId(downloadId.value), downloadId, "https://example.com/file.zip", 0)

internal fun Download.movedTo(status: DownloadStatus): Download = transitionTo(status).shouldBeInstanceOf<TransitionResult.Moved>().download

internal class RecordingListener(
    private val journal: MutableList<String>? = null,
) : RunListener {
    val moves = mutableListOf<Download>()
    val reported = mutableListOf<Pair<Long, Long?>>()

    override suspend fun moved(download: Download) {
        moves += download
        journal?.add("moved ${download.status}")
    }

    override fun progress(
        downloaded: Long,
        total: Long?,
    ) {
        reported += downloaded to total
    }
}

internal class RecordingFinish(
    private val journal: MutableList<String>,
) : FinishHook {
    val calls = mutableListOf<Pair<Download, List<Path>>>()

    override suspend fun finished(
        download: Download,
        outputs: List<Path>,
    ) {
        calls += download to outputs
        journal += "finish"
    }
}

internal class RecordingLimiter : Limiter {
    var bytes = 0L

    override suspend fun acquire(bytes: Int) {
        this.bytes += bytes
    }
}

internal class RecordingMeter : Meter {
    var bytes = 0L

    override fun record(bytes: Long) {
        this.bytes += bytes
    }
}

internal data class JobResult(
    val download: Download,
    val failure: StepFailure?,
)

/** A StepRunner over the in-memory fakes, sharing one journal; FTP has no protocol. */
internal class StepHarness(
    freeSpaceReserve: Long = 0,
    checkpointEvery: Duration = StepRunner.DEFAULT_CHECKPOINT_EVERY,
) {
    val journal = mutableListOf<String>()
    val protocol = FakeProtocol(journal = journal)
    val fs = FakeFileSystem(journal = journal)
    val store = InMemoryPlanStore()
    val listener = RecordingListener(journal)
    val finish = RecordingFinish(journal)
    var duplicate: DownloadId? = null
    val runner =
        StepRunner(
            fileSystem = fs,
            store = store,
            protocols = mapOf(DownloadKind.DIRECT to protocol, DownloadKind.SITE to protocol, DownloadKind.TORRENT to protocol),
            defaultFolder = stepRoot,
            duplicates =
                object : DuplicateCheck {
                    override suspend fun duplicateOf(
                        download: Download,
                        mirror: Mirror,
                    ): DownloadId? {
                        journal += "duplicateCheck"
                        return duplicate
                    }
                },
            finish = finish,
            freeSpaceReserve = freeSpaceReserve,
            checkpointEvery = checkpointEvery,
        )

    suspend fun prepare(download: Download = aPendingDownload()): PrepareOutcome {
        store.register(download.id)
        return runner.prepare(download, aMirror(download.id), listener)
    }

    /** The whole job as the queue (#552) will drive it. */
    suspend fun runAll(
        download: Download = aPendingDownload(),
        limiter: Limiter = Limiter.None,
        meter: Meter = Meter.None,
    ): JobResult =
        when (val prepared = prepare(download)) {
            is PrepareOutcome.Failed -> {
                JobResult(prepared.download, prepared.failure)
            }

            is PrepareOutcome.Ready -> {
                when (val ran = runner.run(prepared, listener, limiter, meter)) {
                    is RunOutcome.Finished -> JobResult(ran.download, null)
                    is RunOutcome.Failed -> JobResult(ran.download, ran.failure)
                }
            }
        }
}
