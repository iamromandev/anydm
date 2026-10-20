package dev.anydm.engine.torrent

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue
import org.libtorrent4j.TorrentBuilder
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Seeding (TOR-05), resume data (TOR-06) and the meter (TOR-09) through the real engine and libtorrent, on loopback. The
 * test's own seeder session holds the torrent; the engine downloads it, then seeds it to a leecher session that is told
 * where the engine is. The database is a file, so an engine can be stopped and another started over the same jobs.
 */
class TorrentSeedingEndToEndTest : TorrentEngineTestBase() {
    @Test
    fun aTorrentSeedsUntilItsRatioThenCompletes() =
        runBlocking<Unit> {
            val (data, made) = single()
            seed(made)
            // The leecher may learn of the test's seeder from the engine and take part of the file from it, so the ratio is a
            // fraction: whatever split, the engine sends more than 5% of its size.
            newEngine(settings = { it.copy(seedRatio = 0.05) })

            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            leech(made)
            await(id, DownloadStatus.COMPLETED)

            File(leechDir, "data.bin").readBytes().toList() shouldBe data.toList()
            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            noLeftovers()
            // gone from the session: there is no seed left to stop
            protocol!!.stopSeeding(id) shouldBe false
        }

    @Test
    fun aTimeLimitEndsASeed() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 2)
            seed(made)
            newEngine(secondsPerMinute = 1)

            val id = add(made, seedMinutes = SeedRule.At(1))
            await(id, DownloadStatus.SEEDING)
            await(id, DownloadStatus.COMPLETED)

            protocol!!.stopSeeding(id) shouldBe false
        }

    @Test
    fun stopSeedingEndsItNow() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 3)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)

            engine!!.stopSeeding(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.COMPLETED)

            protocol!!.stopSeeding(id) shouldBe false
        }

    @Test
    fun aSeedSurvivesAnEngineRestart() =
        runBlocking<Unit> {
            val (data, made) = single(seed = 4)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            engine!!.stop()

            newEngine()
            await(id, DownloadStatus.SEEDING)
            leech(made)

            File(leechDir, "data.bin").readBytes().toList() shouldBe data.toList()
            engine!!.stopSeeding(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.COMPLETED)
        }

    @Test
    fun aPausedDownloadResumesWithItsSavedState() =
        runBlocking<Unit> {
            val size = 4 * 1024 * 1024
            val (data, made) = single(size = size, seed = 5)
            val serving = seed(made)
            newEngine()
            val id = add(made, template = NewDownload(DownloadKind.TORRENT, speedLimit = 256L * 1024))
            withTimeout(60.seconds) {
                engine!!.downloads.first { snaps -> (snaps.firstOrNull { it.download.id == id }?.downloaded ?: 0) >= 1024 * 1024 }
            }

            engine!!.pause(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.PAUSED)
            (store!!.torrents.get(id)!!.resume != null) shouldBe true
            engine!!.setSpeedLimit(id, null)
            engine!!.resume(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.COMPLETED)

            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            // what the first run fetched (over 1 MiB) was not fetched again; pieces in flight at the pause are
            serving.stats().payloadUp shouldBeLessThan size + 768L * 1024
        }

    @Test
    fun aSeedWhoseFilesAreRemovedFails() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 6)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            engine!!.stop()
            File(dl, "data.bin").delete() shouldBe true

            newEngine()

            await(id, DownloadStatus.FAILED)
            engine!!
                .downloads.value
                .first { it.download.id == id }
                .lastFailure!!
                .message!!
                .contains("data.bin") shouldBe true
        }

    @Test
    fun uploadIsMeteredForASeed() =
        runBlocking<Unit> {
            val size = 1024 * 1024
            val (_, made) = single(size = size, seed = 7)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            leech(made)
            // the seed's counters are sampled once a second
            delay(2500)

            engine!!.stop()

            val reopened = openStore()
            try {
                (reopened.traffic.downloadUpload(id) >= size) shouldBe true
            } finally {
                reopened.close()
            }
        }

    @Test
    fun theGlobalUploadLimitReachesTheSession() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 8)
            seed(made)
            newEngine()
            val id = add(made)
            await(id, DownloadStatus.COMPLETED)

            engine!!.setGlobalUploadLimit(100_000).shouldBeInstanceOf<CommandResult.Done>()
            engineBackend!!.appliedSessionLimits() shouldBe (0 to 100_000)

            engine!!.setGlobalUploadLimit(null).shouldBeInstanceOf<CommandResult.Done>()
            engineBackend!!.appliedSessionLimits() shouldBe (0 to 0)
        }

    @Test
    fun noRuleFinishesAsCompletedImmediately() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 9)
            seed(made)
            newEngine()

            val id = add(made)
            await(id, DownloadStatus.COMPLETED)

            protocol!!.stopSeeding(id) shouldBe false
            noLeftovers()
        }
}
