package dev.anydm.desktop

import dev.anydm.engine.http.DownloadEngine
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.JobSpec
import dev.anydm.engine.http.Reasons
import dev.anydm.engine.torrent.CANT_RESUME
import dev.anydm.engine.torrent.DefaultTorrentEngine
import dev.anydm.engine.torrent.PEER_ID_PREFIX
import dev.anydm.engine.torrent.TorrentJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The real environment, with links that fail before any connection is made: nothing here reaches the network. */
class DownloadEnvTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun job(
        id: String,
        url: String,
    ) = JobSpec(id, url, tmp.root.path, "a$id.bin", 0, null, null)

    @Test
    fun theRealEnvFailsEachBadLinkAloneAndKeepsWorkingEvenOnAScopeWithoutASupervisor() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val env = DownloadEnv.real(scope)
            val events = CopyOnWriteArrayList<EngineEvent>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { env.engine.events.collect { events.add(it) } }
            env.engine.start(job("1", "ftp://x.test/a")) // not http(s)
            env.engine.start(job("2", "http://x test/a")) // not a URI
            env.engine.start(job("3", "https://x.test/a b")) // a space: not a valid URI
            val end = System.currentTimeMillis() + 10_000
            while (events.size < 3 && System.currentTimeMillis() < end) Thread.sleep(10)
            assertEquals(listOf("1", "2", "3"), events.map { it.id }.sorted())
            assertTrue(events.all { it is EngineEvent.Failed && it.reason == Reasons.NETWORK }, "$events")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theRealEnvBuildsItsOwnScopeWhenNoneIsGiven() {
        val env = DownloadEnv.real()
        env.engine.setRate(null)
        env.engine.stop("nothing")
    }

    @Test
    fun theRealEnvKeepsTorrentCopiesInATorrentsFolderOfTheDataDirAndMakesNoFolderUntilTheFirstSave() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val data = tmp.root.toPath().resolve("data")
            val env = DownloadEnv.real(scope, data)
            val hex = "ab".repeat(20)
            assertEquals(data.resolve("torrents").resolve("$hex.torrent"), env.torrentFiles.path(hex))
            assertTrue(
                !java.nio.file.Files
                    .exists(data),
                "nothing is made at start",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theRealEnvRunsATorrentJobAndAMissingCopyFailsItWithCantResumeBeforeAnyNetworkIsTouched() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val env = DownloadEnv.real(scope, tmp.root.toPath().resolve("data"))
            val events = CopyOnWriteArrayList<EngineEvent>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { env.torrents.events.collect { events.add(it) } }
            env.torrents.start(TorrentJob("1", env.torrentFiles.path("cd".repeat(20)).toString(), tmp.root.path, "T", null, env.peerId))
            val end = System.currentTimeMillis() + 10_000
            while (events.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
            val failed = events.single() as EngineEvent.Failed
            assertEquals(CANT_RESUME, failed.reason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aPeerIdIsOurPrefixAndTwelveRandomBytesAndDiffersEachTime() {
        val a = DownloadEnv.newPeerId()
        assertEquals(20, a.size)
        assertEquals(PEER_ID_PREFIX, a.copyOf(PEER_ID_PREFIX.length).decodeToString())
        assertTrue(!a.contentEquals(DownloadEnv.newPeerId()))
    }

    @Test
    fun theRealEnvGivesTheSameSharedRatesToBothEnginesAndToTheEnvsRates() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val env = DownloadEnv.real(scope, tmp.root.toPath().resolve("data"))
            val http = (env.engine as DownloadEngine).shares
            val torrents = (env.torrents as DefaultTorrentEngine).shares
            assertSame(env.rates, http, "the http engine draws on the env's shares")
            assertSame(env.rates, torrents, "the torrent engine draws on the env's shares")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aTorrentSavedThroughTheRealEnvsCopyStoreStartsFromItsPathAndStopsAgain() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val env = DownloadEnv.real(scope, tmp.root.toPath().resolve("data"))
            // a tracker on a closed loopback port: it refuses at once, nothing leaves the machine
            val bytes =
                TestTorrents.bytes(
                    "Loop",
                    listOf("a.bin" to 40_000L),
                    single = true,
                    trackers = listOf(listOf("http://127.0.0.1:1/announce")),
                )
            val hex = TestTorrents.meta(bytes).infoHashHex
            env.torrentFiles.save(hex, bytes)
            val events = CopyOnWriteArrayList<EngineEvent>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { env.torrents.events.collect { events.add(it) } }
            val folder = tmp.newFolder("save").toPath()
            env.torrents.start(TorrentJob("1", env.torrentFiles.path(hex).toString(), folder.toString(), "Loop", null, env.peerId))
            val end = System.currentTimeMillis() + 10_000
            while (events.none { it is EngineEvent.Started || it is EngineEvent.Failed } &&
                System.currentTimeMillis() < end
            ) {
                Thread.sleep(10)
            }
            val first = events.first { it is EngineEvent.Started || it is EngineEvent.Failed }
            assertTrue(first is EngineEvent.Started, "the copy the store saved is the one the engine reads: $first")
            assertEquals(
                null,
                events
                    .filterIsInstance<EngineEvent.Failed>()
                    .firstOrNull()
                    ?.reason
                    ?.takeIf { it == CANT_RESUME },
            )
            env.torrents.stop("1")
            while (events.none { it is EngineEvent.Stopped } && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue(events.any { it is EngineEvent.Stopped }, "stopped: $events")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun twoEnvsWithTheSamePeerIdBytesAndTheSameCollaboratorsAreEqual() {
        val a = DownloadEnv.inert()
        val b = a.copy(peerId = a.peerId.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != a.copy(peerId = DownloadEnv.newPeerId()))
    }
}
