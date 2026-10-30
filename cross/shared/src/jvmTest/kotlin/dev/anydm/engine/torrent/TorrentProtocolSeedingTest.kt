package dev.anydm.engine.torrent

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.media.CollectingIo
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewTorrent
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TorrentProtocolSeedingTest {
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val backend = FakeTorrentBackend()
    private val fs = FakeFileSystem()
    private val folder = Path("/dl")
    private val magnet = "magnet:?xt=urn:btih:" + "0123456789abcdef".repeat(3).take(40)
    private var settings = Settings()
    private var rows: List<File> = emptyList()

    private val resumeStore = FailingTorrentStore(store.torrents)

    private val protocol =
        TorrentProtocol(
            http = HttpProtocol(OkHttpTransport()),
            backend = backend,
            torrents = resumeStore,
            cleaner = NameCleaner(OsProfile.LINUX),
            fileSystem = fs,
            magnetTimeout = 50.milliseconds,
            pollEvery = 5.milliseconds,
            settings = { settings },
            resumeEvery = 1000.seconds,
            seedPollEvery = 5.milliseconds,
            secondsPerMinute = 1,
            files = { rows },
        )

    private val pack = backend.torrent("Pack", "Pack/a.bin" to 100L, "Pack/b.bin" to 300L)

    @AfterTest
    fun close() {
        runBlocking { protocol.close() }
        store.close()
    }

    private suspend fun prepared(torrent: NewTorrent = NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4))): Transfer {
        val download =
            store.downloads.add(
                NewDownload(
                    DownloadKind.TORRENT,
                    mirrors = listOf(magnet),
                    saveFolder = folder.toString(),
                    fileName = "Pack",
                    torrent = torrent,
                ),
            )
        val mirror = store.downloads.mirrors(download.id).single()
        val probe = (protocol.probe(ProbeRequest(download, mirror)) as StepResult.Ok).value
        val plan = ((protocol.plan(probe, PlanContext(download)) as StepResult.Ok).value as TransferPlan).files
        val transferFiles =
            plan.map {
                val target = Path(folder, *it.path.split('/').toTypedArray())
                TransferFile(File(FileId(it.index + 1L), download.id, it.index, it.path, it.size, it.selected), emptyList(), target, target)
            }
        return Transfer(download, mirror, probe, transferFiles)
    }

    private fun ok(result: StepResult<FinalizeResult>) = result.shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>().value

    /** A torrent that is downloaded and placed; with a rule on, it is seeding. */
    private suspend fun finalized(
        torrent: NewTorrent = NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4)),
    ): Pair<Transfer, FinalizeResult> {
        val transfer = prepared(torrent)
        protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)
        return transfer to ok(protocol.finalize(transfer))
    }

    private fun stats(
        up: Long = 0,
        wanted: Long = 300,
        seconds: Long = 0,
        down: Long = 0,
    ) = TorrentStats(payloadDown = down, payloadUp = up, allTimeUp = up, wanted = wanted, seedingSeconds = seconds, seeding = true)

    private suspend fun nextEnd(): SeedEnd = withTimeout(5.seconds) { protocol.seedEnds.first() }

    private suspend fun noEnd(): Boolean = withTimeoutOrNull(100.milliseconds) { protocol.seedEnds.first() } == null

    private fun ratio(value: Double) = NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4), seedRatio = SeedRule.At(value))

    @Test
    fun finalizeSeedsWhenTheGlobalRatioIsSet() =
        runBlocking<Unit> {
            settings = Settings(seedRatio = 1.0)

            val (_, result) = finalized()

            result.seeding shouldBe true
            backend.added.single().removed shouldBe 0
        }

    @Test
    fun finalizeSeedsWhenOnlyTheTorrentsOwnRuleIsSet() =
        runBlocking<Unit> {
            val (_, result) = finalized(NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4), seedMinutes = SeedRule.At(5)))

            result.seeding shouldBe true
            backend.added.single().removed shouldBe 0
        }

    @Test
    fun anOffRuleOnTheTorrentBeatsTheGlobalOne() =
        runBlocking<Unit> {
            settings = Settings(seedRatio = 1.0)

            val (_, result) = finalized(NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4), seedRatio = SeedRule.Off))

            result.seeding shouldBe false
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun noRuleMeansCompletedAsBefore() =
        runBlocking<Unit> {
            val (_, result) = finalized()

            result.seeding shouldBe false
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun aRatioEndsTheSeed() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(1.0))
            val torrent = backend.added.single()
            torrent.stats = stats(up = 299)
            noEnd() shouldBe true

            torrent.stats = stats(up = 300)

            nextEnd() shouldBe SeedEnd.Ended(transfer.download.id, SeedEndReason.RULE)
            torrent.removed shouldBe 1
        }

    @Test
    fun aTimeLimitEndsTheSeed() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4), seedMinutes = SeedRule.At(2)))
            val torrent = backend.added.single()
            torrent.stats = stats(seconds = 1)
            noEnd() shouldBe true

            torrent.stats = stats(seconds = 2)

            nextEnd() shouldBe SeedEnd.Ended(transfer.download.id, SeedEndReason.RULE)
        }

    @Test
    fun theFirstLimitWins() =
        runBlocking<Unit> {
            settings = Settings(seedRatio = 100.0, seedMinutes = 1)
            val (transfer, _) = finalized()
            backend.added.single().stats = stats(up = 10, seconds = 1)

            nextEnd() shouldBe SeedEnd.Ended(transfer.download.id, SeedEndReason.RULE)
            noEnd() shouldBe true
        }

    @Test
    fun aRatioIsNeverComputedForZeroWanted() =
        runBlocking<Unit> {
            finalized(ratio(1.0))
            backend.added.single().stats = stats(up = 1000, wanted = 0)

            noEnd() shouldBe true
            backend.added.single().removed shouldBe 0
        }

    @Test
    fun turningTheRuleOffEndsTheSeedAtOnce() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            noEnd() shouldBe true

            store.torrents.setSeedRules(transfer.download.id, SeedRule.Off, SeedRule.Off)

            nextEnd() shouldBe SeedEnd.Ended(transfer.download.id, SeedEndReason.RULE)
        }

    @Test
    fun stopSeedingEndsItWithReasonStopped() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))

            protocol.stopSeeding(transfer.download.id) shouldBe true

            nextEnd() shouldBe SeedEnd.Ended(transfer.download.id, SeedEndReason.STOPPED)
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun stopSeedingOnANonSeedIsFalse() =
        runBlocking<Unit> {
            val (transfer, _) = finalized()

            protocol.stopSeeding(transfer.download.id) shouldBe false
            protocol.stopSeeding(
                dev.anydm.engine.core
                    .DownloadId(999),
            ) shouldBe false
        }

    @Test
    fun anEndedSeedIsReportedOnceAndRemovedFromTheSession() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            protocol.stopSeeding(transfer.download.id) shouldBe true
            nextEnd()

            protocol.stopSeeding(transfer.download.id) shouldBe false
            noEnd() shouldBe true
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun aFileErrorFailsTheSeed() =
        runBlocking<Unit> {
            // the transfer reads the first answer; the seed loop finds the error
            backend.script =
                listOf(
                    TorrentProgress(300, 300, true),
                    TorrentProgress(0, 300, false, TorrentError(TorrentErrorKind.OTHER, "permission denied")),
                )
            val (transfer, _) = finalized(ratio(5.0))

            val end = nextEnd().shouldBeInstanceOf<SeedEnd.Failed>()

            end.id shouldBe transfer.download.id
            end.message shouldContain "permission denied"
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun aTorrentGoneFromTheSessionFailsTheSeed() =
        runBlocking<Unit> {
            backend.script =
                listOf(
                    TorrentProgress(300, 300, true),
                    TorrentProgress(0, 0, false, TorrentError(TorrentErrorKind.LOST, "the torrent left the session")),
                )
            val (transfer, _) = finalized(ratio(5.0))

            nextEnd().shouldBeInstanceOf<SeedEnd.Failed>().id shouldBe transfer.download.id
        }

    /** The file rows a restarted job has, and a rule that keeps its seed (with none, the loop would end it at once). */
    private fun stored(
        transfer: Transfer,
        vararg names: String,
    ) {
        settings = Settings(seedRatio = 5.0)
        rows = names.mapIndexed { i, name -> File(FileId(i + 1L), transfer.download.id, i, name, pack.files[i].size, i == 1) }
    }

    @Test
    fun resumeSeedingAddsTheTorrentAtTheJobFolderFromItsBlob() =
        runBlocking<Unit> {
            val transfer = prepared()
            store.torrents.setResume(transfer.download.id, "blob".encodeToByteArray())
            stored(transfer, "Pack/a.bin", "Pack/b.bin")
            fs.put(Path(folder, "Pack/b.bin"), ByteArray(300))

            protocol.resumeSeeding(transfer.download) shouldBe StepResult.Ok(Unit)

            val request = backend.added.single().request
            request.saveTo shouldBe folder
            request.resume!!.decodeToString() shouldBe "blob"
            request.fileNames shouldBe listOf("Pack/a.bin", "Pack/b.bin")
            request.priorities shouldBe listOf(0, 4)
            protocol.stopSeeding(transfer.download.id) shouldBe true
        }

    @Test
    fun aSeedWhoseProxyCantBeUsedFailsAsProxyAndAnyOtherAddFailureAsTransient() =
        runBlocking<Unit> {
            val transfer = prepared()
            stored(transfer, "Pack/a.bin", "Pack/b.bin")
            fs.put(Path(folder, "Pack/b.bin"), ByteArray(300))

            backend.addFailure = StepResult.Failed(ErrorClass.PROXY, "no password saved")
            protocol.resumeSeeding(transfer.download) shouldBe StepResult.Failed(ErrorClass.PROXY, "no password saved")

            backend.addFailure = StepResult.Failed(null, "no library")
            protocol.resumeSeeding(transfer.download) shouldBe StepResult.Failed(ErrorClass.TRANSIENT, "no library")
        }

    @Test
    fun resumeSeedingWithoutABlobAddsItUnderTheFinalNames() =
        runBlocking<Unit> {
            val transfer = prepared()
            stored(transfer, "Pack (1)/a.bin", "Pack (1)/b.bin")
            fs.put(Path(folder, "Pack (1)/b.bin"), ByteArray(300))

            protocol.resumeSeeding(transfer.download) shouldBe StepResult.Ok(Unit)

            val request = backend.added.single().request
            request.resume shouldBe null
            request.fileNames shouldBe listOf("Pack (1)/a.bin", "Pack (1)/b.bin")
        }

    @Test
    fun resumeSeedingFailsWhenTheFilesAreGone() =
        runBlocking<Unit> {
            val transfer = prepared()
            stored(transfer, "Pack/a.bin", "Pack/b.bin")

            val failed = protocol.resumeSeeding(transfer.download).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "Pack/b.bin"
            backend.added.size shouldBe 0
        }

    @Test
    fun aFailingResumeSaveStillEndsTheSeed() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            resumeStore.failingResume = true

            protocol.stopSeeding(transfer.download.id) shouldBe true

            nextEnd() shouldBe SeedEnd.Ended(transfer.download.id, SeedEndReason.STOPPED)
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun aFailingResumeSaveDoesNotKeepAPausedTorrentInTheSession() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            resumeStore.failingResume = true
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            withTimeout(5.seconds) { while (backend.added.isEmpty()) delay(5) }

            running.cancelAndJoin()

            backend.added.single().removed shouldBe 1
        }

    @Test
    fun aNewTransferReplacesTheSeedOfTheSameJob() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))

            protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)

            backend.added.first().removed shouldBe 1
            // the seed went quietly: no end, and nothing left to stop
            noEnd() shouldBe true
            protocol.stopSeeding(transfer.download.id) shouldBe false
        }

    private fun busy(
        peers: Int = 2,
        done: List<Long> = listOf(0L, 300L),
    ) = TorrentLiveStats(peers, 1, 2.5, 1000, 400, done)

    @Test
    fun aSeedIsInLiveToo() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            backend.added.single().liveStats = busy(peers = 3)
            backend.added.single().stats = stats(up = 150)

            withTimeout(5.seconds) { while (protocol.live()[transfer.download.id]?.summary?.peers != 3) delay(5) }

            val live = protocol.live().getValue(transfer.download.id)
            live.summary.uploaded shouldBe 150L
            live.summary.uploadRate shouldBe 400L
            live.fileDone shouldBe listOf(0L, 300L)
        }

    @Test
    fun liveIsGoneWhenTheSeedEnds() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            protocol.live().containsKey(transfer.download.id) shouldBe true

            protocol.stopSeeding(transfer.download.id) shouldBe true

            protocol.live() shouldBe emptyMap()
        }

    @Test
    fun liveIsGoneAfterClose() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            protocol.live().containsKey(transfer.download.id) shouldBe true

            protocol.close()

            protocol.live() shouldBe emptyMap()
        }

    @Test
    fun aNoRuleTorrentLeavesNoLive() =
        runBlocking<Unit> {
            val (transfer, _) = finalized()

            protocol.live().containsKey(transfer.download.id) shouldBe false
        }

    @Test
    fun detailOfARunningTorrentAndOfASeed() =
        runBlocking<Unit> {
            val shown = TorrentDetail(emptyList(), emptyList(), PieceMap(8, byteArrayOf(0x0F)))
            val (transfer, _) = finalized(ratio(5.0))
            backend.added.single().detailData = shown

            protocol.detail(transfer.download.id) shouldBe shown
        }

    @Test
    fun detailOfARunningDownload() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val shown = TorrentDetail(emptyList(), listOf(TorrentTracker("http://t/a", 0, true, true, true)), PieceMap.Empty)
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            withTimeout(5.seconds) { while (backend.added.isEmpty()) delay(5) }
            backend.added.single().detailData = shown

            protocol.detail(transfer.download.id) shouldBe shown

            running.cancelAndJoin()
            protocol.detail(transfer.download.id) shouldBe null
        }

    @Test
    fun detailOfAnUnknownJobIsNull() =
        runBlocking<Unit> {
            protocol.detail(DownloadId(999)) shouldBe null
        }

    @Test
    fun aNewTransferReplacesTheLiveOfASeed() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            backend.added.single().liveStats = busy(peers = 2)
            withTimeout(5.seconds) { while (protocol.live()[transfer.download.id]?.summary?.peers != 2) delay(5) }

            protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)

            // the second torrent has no peers: the first one's numbers are not left behind
            protocol
                .live()
                .getValue(transfer.download.id)
                .summary.peers shouldBe 0
        }

    @Test
    fun resumeSeedingKeepsTheSeedWhenTheSessionWontStart() =
        runBlocking<Unit> {
            val transfer = prepared()
            stored(transfer, "Pack/a.bin", "Pack/b.bin")
            fs.put(Path(folder, "Pack/b.bin"), ByteArray(300))
            backend.addFailure = StepResult.Failed(null, "torrents aren't supported here: no library")

            val failed = protocol.resumeSeeding(transfer.download).shouldBeInstanceOf<StepResult.Failed>()

            // TRANSIENT: the queue leaves the job SEEDING for the next start
            failed.errorClass shouldBe ErrorClass.TRANSIENT
            failed.message shouldContain "no library"
        }

    @Test
    fun seedTrafficReturnsDeltasSinceTheLastCall() =
        runBlocking<Unit> {
            val transfer = prepared()
            stored(transfer, "Pack/a.bin", "Pack/b.bin")
            fs.put(Path(folder, "Pack/b.bin"), ByteArray(300))
            protocol.resumeSeeding(transfer.download)
            val torrent = backend.added.single()
            torrent.stats = stats(up = 500, down = 40)

            val first = protocol.seedTraffic().single()
            torrent.stats = stats(up = 800, down = 40)
            val second = protocol.seedTraffic().single()

            first.id shouldBe transfer.download.id
            first.up shouldBe 500L
            first.down shouldBe 40L
            second.up shouldBe 300L
            second.down shouldBe 0L
            protocol.seedTraffic() shouldBe emptyList()
        }

    @Test
    fun closeSavesTheSeedsResume() =
        runBlocking<Unit> {
            val (transfer, _) = finalized(ratio(5.0))
            backend.added.single().resume = "seed-blob".encodeToByteArray()

            protocol.close()

            backend.closed shouldBe 1
            store.torrents
                .get(transfer.download.id)!!
                .resume!!
                .decodeToString() shouldBe "seed-blob"
        }
}
