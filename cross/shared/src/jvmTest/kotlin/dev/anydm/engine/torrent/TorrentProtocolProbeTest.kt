package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewTorrent
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class TorrentProtocolProbeTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val backend = FakeTorrentBackend()
    private val tmp = Files.createTempDirectory("anydm-torrent-probe-").toFile()
    private val hex = "0123456789abcdef0123456789abcdef01234567"

    private val protocol =
        TorrentProtocol(
            http = HttpProtocol(OkHttpTransport()),
            backend = backend,
            torrents = store.torrents,
            cleaner = NameCleaner(OsProfile.LINUX),
            fileSystem = DesktopFileSystem(),
            magnetTimeout = 50.milliseconds,
            torrentLimit = 1_000,
        )

    private val pack = backend.torrent("pack", "pack/a.bin" to 100L, "pack/b.bin" to 300L)

    @AfterTest
    fun close() {
        server.close()
        store.close()
        tmp.deleteRecursively()
    }

    private suspend fun request(
        source: String,
        torrent: NewTorrent? = NewTorrent(),
    ): ProbeRequest {
        val download = store.downloads.add(NewDownload(DownloadKind.TORRENT, mirrors = listOf(source), torrent = torrent))
        return ProbeRequest(download, store.downloads.mirrors(download.id).single())
    }

    private fun probed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Ok<*>>().value as TorrentProbe

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    @Test
    fun aTorrentUrlIsFetchedAndItsMetadataStored() =
        runBlocking<Unit> {
            server.serve("/x.torrent", pack.metadata)
            val request = request(server.url("/x.torrent"))

            val probe = probed(protocol.probe(request))

            probe.info shouldBe pack
            store.torrents
                .get(request.download.id)!!
                .metadata!!
                .toList() shouldBe pack.metadata.toList()
        }

    @Test
    fun aLocalFileIsRead() =
        runBlocking<Unit> {
            val file = java.io.File(tmp, "a b.torrent").also { it.writeBytes(pack.metadata) }

            probed(protocol.probe(request("file://" + file.toURI().path))).info shouldBe pack
        }

    @Test
    fun aMagnetWaitsForMetadataAndStoresIt() =
        runBlocking<Unit> {
            val magnet = "magnet:?xt=urn:btih:$hex"
            backend.magnets[magnet] = pack
            val request = request(magnet)

            probed(protocol.probe(request)).info shouldBe pack

            backend.calls shouldBe listOf("magnet $magnet")
            store.torrents
                .get(request.download.id)!!
                .metadata!!
                .toList() shouldBe pack.metadata.toList()
        }

    @Test
    fun aMagnetThatNeverAnswersFailsTransient() =
        runBlocking<Unit> {
            backend.magnetNever = true

            failed(protocol.probe(request("magnet:?xt=urn:btih:$hex"))).errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun aBodyOverTheCapIsRefused() =
        runBlocking<Unit> {
            server.serve("/big.torrent", ByteArray(2_000))

            failed(protocol.probe(request(server.url("/big.torrent")))).message shouldContain "bigger than 1000 bytes"
        }

    @Test
    fun aCorruptTorrentIsAFailureWithItsReason() =
        runBlocking<Unit> {
            server.serve("/bad.torrent", byteArrayOf(1, 2, 3))

            val result = failed(protocol.probe(request(server.url("/bad.torrent"))))

            result.errorClass shouldBe null
            result.message shouldContain "not a valid .torrent"
        }

    @Test
    fun aHostileTorrentIsRefused() =
        runBlocking<Unit> {
            val hostile =
                listOf(
                    listOf("../x"),
                    listOf("/abs"),
                    listOf("pack/./x"),
                    listOf("pack/a.bin.1.anydm"),
                    listOf("pack/A", "pack/a"),
                    listOf("pack", "pack/x"),
                )
            for ((i, paths) in hostile.withIndex()) {
                val bad = backend.torrent("bad$i", *paths.map { it to 1L }.toTypedArray(), hash = "c".repeat(39) + i)
                server.serve("/bad$i.torrent", bad.metadata)

                val result = failed(protocol.probe(request(server.url("/bad$i.torrent"))))

                result.errorClass shouldBe null
                result.message shouldContain "can't be placed"
            }
        }

    @Test
    fun storedMetadataIsNotFetchedAgain() =
        runBlocking<Unit> {
            val request = request("magnet:?xt=urn:btih:$hex", NewTorrent(metadata = pack.metadata))

            probed(protocol.probe(request)).info shouldBe pack

            backend.calls shouldBe listOf("bytes")
        }

    @Test
    fun theProbeReportsSelectedSizeAndTheInfohash() =
        runBlocking<Unit> {
            val request = request("magnet:?xt=urn:btih:$hex", NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4)))

            val probe = probed(protocol.probe(request))

            probe.size shouldBe 300L
            probe.validator shouldBe pack.infoHash
            probe.name shouldBe "pack"
        }

    @Test
    fun planHasOneFilePerTorrentFileWithSelected() =
        runBlocking<Unit> {
            val request = request("magnet:?xt=urn:btih:$hex", NewTorrent(metadata = pack.metadata, priorities = listOf(0, 4)))

            val plan = protocol.plan(probed(protocol.probe(request)), PlanContext(request.download))

            (plan as StepResult.Ok<TransferPlan>).value.files shouldBe
                listOf(
                    PlannedFile(0, "pack/a.bin", 100L, selected = false),
                    PlannedFile(1, "pack/b.bin", 300L, selected = true),
                )
        }

    @Test
    fun planPathsUseThePlacedName() =
        runBlocking<Unit> {
            val request = request("magnet:?xt=urn:btih:$hex", NewTorrent(metadata = pack.metadata))
            val placed = request.download.copy(fileName = "Renamed")

            val plan = protocol.plan(probed(protocol.probe(request)), PlanContext(placed))

            (plan as StepResult.Ok<TransferPlan>).value.files.map { it.path } shouldBe listOf("Renamed/a.bin", "Renamed/b.bin")
        }

    @Test
    fun aSavedChoiceThatDoesNotFitFails() =
        runBlocking<Unit> {
            val request = request("magnet:?xt=urn:btih:$hex", NewTorrent(metadata = pack.metadata, priorities = listOf(4, 4, 4)))

            failed(protocol.probe(request)).message shouldContain "doesn't fit"
        }

    @Test
    fun everyFileSkippedFails() =
        runBlocking<Unit> {
            val request = request("magnet:?xt=urn:btih:$hex", NewTorrent(metadata = pack.metadata, priorities = listOf(0, 0)))

            failed(protocol.probe(request)).message shouldContain "no file is selected"
        }

    @Test
    fun anUnsupportedSourceFails() =
        runBlocking<Unit> {
            failed(protocol.probe(request("ftp://example.com/x.torrent"))).message shouldContain "magnet"
        }

    @Test
    fun aJobWithNoTorrentRowStillProbes() =
        runBlocking<Unit> {
            server.serve("/x.torrent", pack.metadata)
            val request = request(server.url("/x.torrent"), torrent = null)

            probed(protocol.probe(request)).info shouldBe pack
            store.torrents.get(request.download.id) shouldBe null
        }

    @Test
    fun workFoldersNameTheStagingFolder() =
        runBlocking<Unit> {
            val request = request("magnet:?xt=urn:btih:$hex")

            protocol.workFolders(request.download) shouldBe emptyList()
            protocol.workFolders(request.download.copy(fileName = "Pack")) shouldBe listOf(".Pack.${request.download.id.value}.torrent")
        }

    @Test
    fun closeClosesTheBackend() =
        runBlocking<Unit> {
            protocol.close()

            backend.closed shouldBe 1
        }
}
