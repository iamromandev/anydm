package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.FixedRandom
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.ftp.CredentialWriter
import dev.anydm.engine.ftp.Keychain
import dev.anydm.engine.log.Log
import dev.anydm.engine.media.PlaylistSource
import dev.anydm.engine.net.NetworkIdentity
import dev.anydm.engine.net.OnlineProbe
import dev.anydm.engine.net.ProxyRouter
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.KeepAwake
import dev.anydm.engine.platform.MeteredDetector
import dev.anydm.engine.platform.PowerActions
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.TrafficStore
import dev.anydm.engine.tools.ToolManager
import dev.anydm.engine.torrent.TorrentService
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    network: NetworkIdentity = NetworkIdentity.Unknown,
    traffic: TrafficStore = store.traffic,
    tools: ToolManager? = null,
    playlists: PlaylistSource? = null,
    torrents: TorrentService? = null,
    credentials: CredentialWriter? = null,
    timeZone: () -> TimeZone = { TimeZone.UTC },
    onlineProbe: OnlineProbe = OnlineProbe.Always,
    meteredDetector: MeteredDetector = MeteredDetector.None,
    keepAwake: KeepAwake = KeepAwake.None,
    powerActions: PowerActions = PowerActions.None,
    monotonic: TimeSource = test.testScheduler.timeSource,
    /** Never wakes by default, so tests that move the wall clock with a ShiftedClock are not disturbed. */
    wakeGap: Duration = Duration.INFINITE,
    proxyRouter: ProxyRouter = ProxyRouter.Direct,
    proxyKeychain: Keychain? = null,
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
            identity = network,
            traffic = traffic,
            tools = tools,
            playlists = playlists,
            torrents = torrents,
            credentials = credentials,
            timeZone = timeZone,
            onlineProbe = onlineProbe,
            meteredDetector = meteredDetector,
            keepAwake = keepAwake,
            powerActions = powerActions,
            monotonic = monotonic,
            wakeGap = wakeGap,
            proxyRouter = proxyRouter,
            proxyKeychain = proxyKeychain,
        )

    private var added = 0

    /** Adds a job with its own file name and its own link (the same link twice is a duplicate, FIL-05). */
    suspend fun addJob(priority: Int = 0): DownloadId {
        // A harness made after a restart carries on from the jobs the engine published at start. It reads the engine,
        // not the store, so a closed store still fails inside the engine.
        val stored =
            engine.downloads.value.mapNotNull {
                it.download.fileName
                    ?.removePrefix("job-")
                    ?.removeSuffix(".zip")
                    ?.toIntOrNull()
            }
        added = maxOf(added, stored.maxOrNull() ?: 0) + 1
        val number = added
        return engine
            .add(
                NewDownload(
                    DownloadKind.DIRECT,
                    mirrors = listOf("https://example.com/file-$number.zip"),
                    saveFolder = stepRoot.toString(),
                    fileName = "job-$number.zip",
                    priority = priority,
                ),
            ).shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id
    }

    fun snapshot(id: DownloadId): DownloadSnapshot? = engine.downloads.value.find { it.download.id == id }

    fun status(id: DownloadId): DownloadStatus? = snapshot(id)?.download?.status
}
