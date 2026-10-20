package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.files.ClashPolicy
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
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TorrentProtocolTransferTest {
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val backend = FakeTorrentBackend()
    private val fs = FakeFileSystem()
    private var clash = ClashPolicy.RENAME
    private val folder = Path("/dl")
    private val magnet = "magnet:?xt=urn:btih:" + "0123456789abcdef".repeat(3).take(40)

    private val failing = FailingTorrentStore(store.torrents)

    private fun newProtocol(resumeEvery: Duration = 1.hours) =
        TorrentProtocol(
            http = HttpProtocol(OkHttpTransport()),
            backend = backend,
            torrents = failing,
            cleaner = NameCleaner(OsProfile.LINUX),
            fileSystem = fs,
            policy = { clash },
            magnetTimeout = 50.milliseconds,
            pollEvery = 5.milliseconds,
            resumeEvery = resumeEvery,
        )

    private val protocol = newProtocol()

    private val pack = backend.torrent("Pack", "Pack/a.bin" to 100L, "Pack/b.bin" to 300L)
    private val one = backend.torrent("one.bin", "one.bin" to 50L)

    @AfterTest
    fun close() {
        store.close()
    }

    private suspend fun prepared(
        info: TorrentInfo = pack,
        torrent: NewTorrent =
            NewTorrent(
                metadata = info.metadata,
                priorities =
                    if (info ===
                        pack
                    ) {
                        listOf(0, 4)
                    } else {
                        emptyList()
                    },
                sequential = true,
            ),
        name: String = info.name,
    ): Transfer {
        val download =
            store.downloads.add(
                NewDownload(
                    DownloadKind.TORRENT,
                    mirrors = listOf(magnet),
                    saveFolder = folder.toString(),
                    fileName = name,
                    torrent = torrent,
                ),
            )
        val mirror = store.downloads.mirrors(download.id).single()
        val probe = (protocol.probe(ProbeRequest(download, mirror)) as StepResult.Ok).value
        val plan = ((protocol.plan(probe, PlanContext(download)) as StepResult.Ok).value as TransferPlan).files
        val files =
            plan.map {
                val target = Path(folder, *it.path.split('/').toTypedArray())
                TransferFile(File(FileId(it.index + 1L), download.id, it.index, it.path, it.size, it.selected), emptyList(), target, target)
            }
        return Transfer(download, mirror, probe, files)
    }

    private fun staging(transfer: Transfer) = Path(folder, ".${transfer.download.fileName}.${transfer.download.id.value}.torrent")

    private fun ok(result: StepResult<FinalizeResult>) = result.shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>().value

    private fun done(finished: Boolean = true) = TorrentProgress(400, 400, finished)

    @Test
    fun transferAddsTheTorrentWithItsChoices() =
        runBlocking<Unit> {
            val transfer = prepared()

            protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)

            val request = backend.added.single().request
            request.saveTo shouldBe staging(transfer)
            request.priorities shouldBe listOf(0, 4)
            request.sequential shouldBe true
            request.fileNames shouldBe listOf("Pack/a.bin", "Pack/b.bin")
        }

    @Test
    fun aTorrentIsDownloadedUnderItsOwnNamesAndRenamedAtTheEnd() =
        runBlocking<Unit> {
            // libtorrent finds what it has on disk by the torrent's own names: renamed at the start, a resume would start over
            val transfer = prepared(name = "Other")

            protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)

            backend.added
                .single()
                .request.fileNames shouldBe listOf("Pack/a.bin", "Pack/b.bin")
            backend.added.single().renamed shouldBe null
            val result = ok(protocol.finalize(transfer))
            backend.added.single().renamed shouldBe listOf("Other/a.bin", "Other/b.bin")
            result.outputs shouldBe listOf("Other/b.bin")
            result.consumed shouldBe emptyList()
        }

    @Test
    fun progressIsReported() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false), TorrentProgress(200, 300, false), TorrentProgress(300, 300, true))
            val io = CollectingIo()

            protocol.transfer(prepared(), io) shouldBe StepResult.Ok(Unit)

            io.progress shouldBe listOf(10L, 200L, 300L)
        }

    @Test
    fun aFinishedTorrentEndsTheTransferAndStaysForFinalize() =
        runBlocking<Unit> {
            val transfer = prepared()

            protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)

            backend.added.single().removed shouldBe 0
            ok(protocol.finalize(transfer)).outputs shouldBe listOf("Pack/b.bin")
        }

    @Test
    fun aDiskErrorIsDiskFullAndTheHandleLeaves() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(0, 300, false, TorrentError(TorrentErrorKind.DISK_FULL, "no space")))

            val failed = protocol.transfer(prepared(), CollectingIo()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.DISK_FULL
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun anotherFileErrorIsAPlainFailure() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(0, 300, false, TorrentError(TorrentErrorKind.OTHER, "permission denied")))

            val failed = protocol.transfer(prepared(), CollectingIo()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe null
            failed.message shouldContain "permission denied"
        }

    @Test
    fun aHandleLostFromTheSessionIsTransient() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(0, 300, false, TorrentError(TorrentErrorKind.LOST, "gone")))

            protocol.transfer(prepared(), CollectingIo()).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun anAddFailureIsReturned() =
        runBlocking<Unit> {
            backend.addFailure = StepResult.Failed(null, "torrents aren't supported here: no library")

            protocol.transfer(prepared(), CollectingIo()).shouldBeInstanceOf<StepResult.Failed>().message shouldContain "aren't supported"
        }

    @Test
    fun cancellingRemovesTheHandle() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val running = async(start = CoroutineStart.UNDISPATCHED) { protocol.transfer(transfer, CollectingIo()) }
            while (backend.added.isEmpty()) delay(5)
            delay(30)

            running.cancelAndJoin()

            backend.added.single().removed shouldBe 1
            // nothing is left for a finalize to find
            protocol.finalize(transfer).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun finalizeMovesIntoTheJobFolder() =
        runBlocking<Unit> {
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())

            val result = ok(protocol.finalize(transfer))

            backend.added.single().moved shouldBe (folder to false)
            result.outputs shouldBe listOf("Pack/b.bin")
            result.consumed shouldBe emptyList()
            // libtorrent keeps the pieces of files it was told to skip in a hidden file of its own, and moves it with the rest
            result.cleanup shouldBe listOf(".Pack.${transfer.download.id.value}.torrent", ".${pack.infoHash}.parts")
            result.seeding shouldBe false
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun aClashUnderRenameTakesAFreeName() =
        runBlocking<Unit> {
            fs.put(Path(folder, "Pack", "old.bin"), ByteArray(1))
            fs.put(Path(folder, "Pack (1)", "old.bin"), ByteArray(1))
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())

            val result = ok(protocol.finalize(transfer))

            backend.added.single().renamed shouldBe listOf("Pack (2)/a.bin", "Pack (2)/b.bin")
            backend.added.single().moved shouldBe (folder to false)
            result.outputs shouldBe listOf("Pack (2)/b.bin")
            result.consumed shouldBe listOf("Pack/a.bin", "Pack/b.bin")
        }

    @Test
    fun theRootExistingAsAFileIsAClash() =
        runBlocking<Unit> {
            fs.put(Path(folder, "Pack"), ByteArray(1))
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())

            ok(protocol.finalize(transfer)).outputs shouldBe listOf("Pack (1)/b.bin")
        }

    @Test
    fun aSingleFileRenameKeepsItsExtension() =
        runBlocking<Unit> {
            fs.put(Path(folder, "one.bin"), ByteArray(1))
            val transfer = prepared(one)
            protocol.transfer(transfer, CollectingIo())

            val result = ok(protocol.finalize(transfer))

            result.outputs shouldBe listOf("one (1).bin")
            backend.added.single().renamed shouldBe listOf("one (1).bin")
        }

    @Test
    fun aClashUnderOverwriteReplaces() =
        runBlocking<Unit> {
            clash = ClashPolicy.OVERWRITE
            fs.put(Path(folder, "Pack", "old.bin"), ByteArray(1))
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())

            val result = ok(protocol.finalize(transfer))

            backend.added.single().moved shouldBe (folder to true)
            backend.added.single().renamed shouldBe null
            result.outputs shouldBe listOf("Pack/b.bin")
        }

    @Test
    fun aClashUnderSkipFails() =
        runBlocking<Unit> {
            clash = ClashPolicy.SKIP
            fs.put(Path(folder, "Pack", "old.bin"), ByteArray(1))
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())

            val failed = protocol.finalize(transfer).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "already exists"
            backend.added.single().moved shouldBe null
            backend.added.single().removed shouldBe 1
        }

    @Test
    fun aFailedMoveFailsAndTheHandleLeaves() =
        runBlocking<Unit> {
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())
            backend.added.single().moveFailure = StepResult.Failed(null, "can't move")

            protocol.finalize(transfer).shouldBeInstanceOf<StepResult.Failed>().message shouldContain "can't move"

            backend.added.single().removed shouldBe 1
        }

    @Test
    fun finalizeWithoutAHandleIsTransient() =
        runBlocking<Unit> {
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())
            protocol.finalize(transfer)

            protocol.finalize(transfer).shouldBeInstanceOf<StepResult.Failed>().errorClass shouldBe ErrorClass.TRANSIENT
        }

    private suspend fun waitFor(what: suspend () -> Boolean) = withTimeout(5.seconds) { while (!what()) delay(5) }

    private fun blobOf(info: TorrentInfo) = "resume-of-${info.infoHash}".encodeToByteArray()

    @Test
    fun transferAddsTheStoredResume() =
        runBlocking<Unit> {
            val transfer = prepared()
            store.torrents.setResume(transfer.download.id, "stored".encodeToByteArray())

            protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)

            backend.added
                .single()
                .request.resume!!
                .decodeToString() shouldBe "stored"
        }

    @Test
    fun transferSavesResumeEveryInterval() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val running = async(Dispatchers.Default) { newProtocol(resumeEvery = 20.milliseconds).transfer(transfer, CollectingIo()) }

            waitFor { backend.added.isNotEmpty() && backend.added.single().resumeSaves >= 2 }

            store.torrents
                .get(transfer.download.id)!!
                .resume!!
                .toList() shouldBe blobOf(pack).toList()
            running.cancelAndJoin()
        }

    @Test
    fun pausingSavesResumeBeforeTheTorrentLeaves() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }

            running.cancelAndJoin()

            // the fake refuses to save once the torrent has left the session, as the library does
            backend.added.single().removed shouldBe 1
            store.torrents
                .get(transfer.download.id)!!
                .resume!!
                .toList() shouldBe blobOf(pack).toList()
        }

    @Test
    fun finalizeSavesResumeAtTheFinalPlace() =
        runBlocking<Unit> {
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())
            backend.added.single().resume = "final".encodeToByteArray()

            ok(protocol.finalize(transfer))

            backend.added.single().movedAtLastSave shouldBe true
            store.torrents
                .get(transfer.download.id)!!
                .resume!!
                .decodeToString() shouldBe "final"
        }

    @Test
    fun transferRecordsDownloadDeltasThroughTheMeter() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val io = CollectingIo()
            val running = async(Dispatchers.Default) { protocol.transfer(prepared(), io) }
            waitFor { backend.added.isNotEmpty() }
            val torrent = backend.added.single()

            torrent.stats =
                TorrentStats(payloadDown = 100, payloadUp = 20, allTimeUp = 20, wanted = 300, seedingSeconds = 0, seeding = false)
            waitFor { io.meter.bytes == 100L && io.meter.bytesUp == 20L }
            torrent.stats =
                TorrentStats(payloadDown = 250, payloadUp = 50, allTimeUp = 50, wanted = 300, seedingSeconds = 0, seeding = false)
            waitFor { io.meter.bytes == 250L && io.meter.bytesUp == 50L }

            running.cancelAndJoin()
        }

    @Test
    fun transferSetsTheTorrentsDownloadLimitFromTheShare() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            var share: Long? = 4096
            val running = async(Dispatchers.Default) { protocol.transfer(prepared(), CollectingIo { share }) }
            waitFor { backend.added.isNotEmpty() && backend.added.single().limitedTo == 4096L }

            share = 1000
            waitFor { backend.added.single().limitedTo == 1000L }
            share = null
            waitFor { backend.added.single().limitedTo == null }

            running.cancelAndJoin()
        }

    @Test
    fun setGlobalLimitsGoToTheBackend() {
        protocol.setGlobalLimits(100L, 200L)

        backend.sessionLimits shouldBe (100L to 200L)
    }

    @Test
    fun closeSavesResumeAndClosesTheBackend() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }

            protocol.close()

            backend.closed shouldBe 1
            store.torrents
                .get(transfer.download.id)!!
                .resume!!
                .toList() shouldBe blobOf(pack).toList()
            running.cancelAndJoin()
        }

    private fun runningStats(wanted: Long = 400) =
        TorrentStats(payloadDown = 5, payloadUp = 7, allTimeUp = 7, wanted = wanted, seedingSeconds = 0, seeding = false)

    private fun live(done: List<Long> = listOf(10L, 0L)) = TorrentLiveStats(3, 1, 1.5, 100, 20, done)

    @Test
    fun liveHoldsTheSummaryWhileATorrentRuns() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }
            backend.added.single().stats = runningStats()
            backend.added.single().liveStats = live()

            waitFor { protocol.live()[transfer.download.id]?.summary?.peers == 3 }

            val held = protocol.live().getValue(transfer.download.id)
            held.summary.seeds shouldBe 1
            held.summary.availability shouldBe 1.5
            held.summary.downloadRate shouldBe 100L
            held.summary.uploadRate shouldBe 20L
            held.summary.uploaded shouldBe 7L
            held.summary.ratio shouldBe 7.0 / 400
            held.fileDone shouldBe listOf(10L, 0L)
            running.cancelAndJoin()
        }

    @Test
    fun liveIsGoneOnceTheTransferEnds() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }
            waitFor { protocol.live().containsKey(transfer.download.id) }

            running.cancelAndJoin()

            protocol.live() shouldBe emptyMap()
        }

    @Test
    fun ratioIsNullForZeroWanted() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(0, 0, false))
            val transfer = prepared()
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }
            backend.added.single().stats = runningStats(wanted = 0)
            backend.added.single().liveStats = live()

            waitFor { protocol.live()[transfer.download.id]?.summary?.peers == 3 }

            protocol
                .live()
                .getValue(transfer.download.id)
                .summary.ratio shouldBe null
            running.cancelAndJoin()
        }

    @Test
    fun pausingSavesFileDone() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }
            backend.added.single().liveStats = live(listOf(50L, 0L))

            running.cancelAndJoin()

            store.torrents.get(transfer.download.id)!!.fileDone shouldBe listOf(50L, 0L)
        }

    @Test
    fun aCheckingTorrentKeepsTheStoredFileDone() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(0, 300, false))
            val transfer = prepared()
            store.torrents.setFileDone(transfer.download.id, listOf(50L, 0L))
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }
            // paused during a re-check, which counts up from zero
            backend.added.single().liveStats = TorrentLiveStats(0, 0, 0.0, 0, 0, listOf(0L, 0L), checking = true)

            running.cancelAndJoin()

            store.torrents.get(transfer.download.id)!!.fileDone shouldBe listOf(50L, 0L)
        }

    @Test
    fun aHandleThatLeftTheSessionLeavesNoLive() =
        runBlocking<Unit> {
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())
            protocol.live().containsKey(transfer.download.id) shouldBe true

            protocol.handles.take(transfer.download.id)

            protocol.live() shouldBe emptyMap()
        }

    @Test
    fun finalizeSavesFileDone() =
        runBlocking<Unit> {
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())
            backend.added.single().liveStats = live(listOf(0L, 300L))

            ok(protocol.finalize(transfer))

            store.torrents.get(transfer.download.id)!!.fileDone shouldBe listOf(0L, 300L)
        }

    @Test
    fun aFailingFileDoneSaveDoesNotFailTheTransfer() =
        runBlocking<Unit> {
            backend.script = listOf(TorrentProgress(10, 300, false))
            val transfer = prepared()
            failing.failingFileDone = true
            val running = async(Dispatchers.Default) { protocol.transfer(transfer, CollectingIo()) }
            waitFor { backend.added.isNotEmpty() }
            backend.added.single().liveStats = live()

            running.cancelAndJoin()

            backend.added.single().removed shouldBe 1
            store.torrents.get(transfer.download.id)!!.fileDone shouldBe emptyList()
        }

    @Test
    fun aStaleHandleFromAnEarlierRunIsRemovedFirst() =
        runBlocking<Unit> {
            val transfer = prepared()
            protocol.transfer(transfer, CollectingIo())

            protocol.transfer(transfer, CollectingIo()) shouldBe StepResult.Ok(Unit)

            backend.added.size shouldBe 2
            backend.added[0].removed shouldBe 1
            backend.added[1].removed shouldBe 0
        }
}
