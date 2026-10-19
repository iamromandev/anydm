package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.torrent.FakeTorrentService
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Torrents through the engine (TOR-01 to TOR-03), on a new database from [newDriver] for each test. */
abstract class EngineTorrentContract : StoreTestBase() {
    private val magnet = "magnet:?xt=urn:btih:" + "ab".repeat(20)
    private val pack =
        TorrentInfo(
            "Pack",
            "ab".repeat(20),
            listOf(TorrentFile(0, "Pack/a.bin", 100), TorrentFile(1, "Pack/b.bin", 300)),
            "metadata".encodeToByteArray(),
        )
    private val service = FakeTorrentService().also { it.known[magnet] = pack }

    private suspend fun TestScope.started(torrents: FakeTorrentService? = service): EngineHarness =
        EngineHarness(this, newStore(), torrents = torrents).also { it.engine.start() }

    private fun AddTorrentResult.added() = shouldBeInstanceOf<AddTorrentResult.Added>().download

    @Test
    fun inspectReturnsTheFileListAndCreatesNoJob() =
        runTest {
            val h = started()
            try {
                val result = h.engine.inspectTorrent(magnet).shouldBeInstanceOf<InspectResult.Inspected>()

                result.info shouldBe pack
                result.info.files.map { it.path } shouldBe listOf("Pack/a.bin", "Pack/b.bin")
                h.engine.downloads.value shouldBe emptyList()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun inspectWithoutAServiceIsUnavailable() =
        runTest {
            val h = started(torrents = null)
            try {
                h.engine.inspectTorrent(magnet) shouldBe InspectResult.Unavailable
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun inspectReportsWhyATorrentCantBeRead() =
        runTest {
            val h = started()
            try {
                val failed = h.engine.inspectTorrent("magnet:?xt=urn:btih:" + "cd".repeat(20)).shouldBeInstanceOf<InspectResult.Failed>()

                failed.failure.message shouldContain "no torrent"
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun addTorrentCreatesAJobWithTheSourceAndMetadata() =
        runTest {
            val h = started()
            try {
                val download = h.engine.addTorrent(magnet, info = pack).added()

                download.kind shouldBe DownloadKind.TORRENT
                h.store.downloads
                    .mirrors(download.id)
                    .single()
                    .url shouldBe magnet
                val row = h.store.torrents.get(download.id)!!
                row.metadata!!.decodeToString() shouldBe "metadata"
                row.priorities shouldBe listOf(4, 4)
                row.sequential shouldBe false
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun theTemplatesOptionsCarryOver() =
        runTest {
            val h = started()
            try {
                val download =
                    h.engine
                        .addTorrent(
                            magnet,
                            sequential = true,
                            template = NewDownload(DownloadKind.TORRENT, saveFolder = "/media/tv", priority = 3),
                        ).added()

                download.saveFolder shouldBe "/media/tv"
                download.priority shouldBe 3
                h.store.torrents
                    .get(download.id)!!
                    .sequential shouldBe true
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aFileChoiceBecomesPriorities() =
        runTest {
            val h = started()
            try {
                val download = h.engine.addTorrent(magnet, files = setOf("Pack/a.bin"), info = pack).added()

                h.store.torrents
                    .get(download.id)!!
                    .priorities shouldBe listOf(4, 0)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aChoiceNamingNoFileIsEmptySelection() =
        runTest {
            val h = started()
            try {
                h.engine.addTorrent(magnet, files = setOf("Pack/nothing.bin"), info = pack) shouldBe AddTorrentResult.EmptySelection
                h.engine.addTorrent(magnet, files = emptySet(), info = pack) shouldBe AddTorrentResult.EmptySelection

                h.engine.downloads.value shouldBe emptyList()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aMagnetChoiceInspectsFirst() =
        runTest {
            val h = started()
            try {
                val download = h.engine.addTorrent(magnet, files = setOf("Pack/b.bin")).added()

                service.inspected shouldBe listOf(magnet)
                val row = h.store.torrents.get(download.id)!!
                row.priorities shouldBe listOf(0, 4)
                row.metadata!!.decodeToString() shouldBe "metadata"
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aChoiceWithoutAServiceOrInfoIsUnavailable() =
        runTest {
            val h = started(torrents = null)
            try {
                h.engine.addTorrent(magnet, files = setOf("Pack/a.bin")) shouldBe AddTorrentResult.Unavailable
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aChoiceFromATorrentThatCantBeReadFails() =
        runTest {
            val h = started()
            try {
                val other = "magnet:?xt=urn:btih:" + "cd".repeat(20)

                h.engine.addTorrent(other, files = setOf("x")).shouldBeInstanceOf<AddTorrentResult.Failed>()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun withoutAChoiceNothingIsInspected() =
        runTest {
            val h = started()
            try {
                val download = h.engine.addTorrent(magnet).added()

                service.inspected shouldBe emptyList()
                val row = h.store.torrents.get(download.id)!!
                row.metadata shouldBe null
                row.priorities shouldBe emptyList()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun anUnsupportedSourceIsBadSource() =
        runTest {
            val h = started()
            try {
                h.engine.addTorrent("ftp://example.com/a.torrent") shouldBe AddTorrentResult.BadSource
                h.engine.addTorrent("not a link") shouldBe AddTorrentResult.BadSource

                h.engine.downloads.value shouldBe emptyList()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun theSameTorrentTwiceIsRefusedAsADuplicate() =
        runTest {
            val h = started()
            try {
                h.engine.addTorrent(magnet, info = pack).added()

                val second = h.engine.addTorrent(magnet, info = pack).shouldBeInstanceOf<AddTorrentResult.Refused>()

                second.result
                    .shouldBeInstanceOf<CommandResult.Duplicate>()
                    .found.reason shouldBe DuplicateReason.SAME_URL
                h.engine.downloads.value.size shouldBe 1
            } finally {
                h.engine.stop()
            }
        }

    private suspend fun EngineHarness.torrent(): DownloadId = engine.addTorrent(magnet, info = pack).added().id

    @Test
    fun setTorrentFilesStoresAndAppliesLive() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()
                service.running += id

                h.engine.setTorrentFiles(id, listOf(7, 1)).shouldBeInstanceOf<CommandResult.Done>()

                h.store.torrents
                    .get(id)!!
                    .priorities shouldBe listOf(7, 1)
                service.appliedPriorities[id] shouldBe listOf(7, 1)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aChangedSelectionWhileRunningWaitsForTheNextRun() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()
                service.running += id

                h.engine.setTorrentFiles(id, listOf(4, 0)).shouldBeInstanceOf<CommandResult.Done>()

                h.store.torrents
                    .get(id)!!
                    .priorities shouldBe listOf(4, 0)
                service.appliedPriorities[id] shouldBe null
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aJobNotRunningJustStoresTheChoice() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()

                h.engine.setTorrentFiles(id, listOf(0, 4)).shouldBeInstanceOf<CommandResult.Done>()

                h.store.torrents
                    .get(id)!!
                    .priorities shouldBe listOf(0, 4)
                service.appliedPriorities shouldBe emptyMap()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aWrongPriorityListIsInvalid() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()

                h.engine.setTorrentFiles(id, listOf(4)).shouldBeInstanceOf<CommandResult.Invalid>()
                h.engine.setTorrentFiles(id, listOf(4, 8)).shouldBeInstanceOf<CommandResult.Invalid>()
                h.engine.setTorrentFiles(id, listOf(4, -1)).shouldBeInstanceOf<CommandResult.Invalid>()
                h.engine.setTorrentFiles(id, listOf(0, 0)).shouldBeInstanceOf<CommandResult.Invalid>()

                h.store.torrents
                    .get(id)!!
                    .priorities shouldBe listOf(4, 4)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun setSequentialStoresAndApplies() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()
                service.running += id

                h.engine.setSequential(id, true).shouldBeInstanceOf<CommandResult.Done>()

                h.store.torrents
                    .get(id)!!
                    .sequential shouldBe true
                service.appliedSequential[id] shouldBe true
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun settersOnAJobWithNoTorrentRowAreNotFound() =
        runTest {
            val h = started()
            try {
                val direct = h.addJob()

                h.engine.setTorrentFiles(direct, listOf(4)) shouldBe CommandResult.NotFound
                h.engine.setSequential(direct, true) shouldBe CommandResult.NotFound
                h.engine.setTorrentFiles(DownloadId(9999), listOf(4)) shouldBe CommandResult.NotFound
                h.engine.setSequential(DownloadId(9999), true) shouldBe CommandResult.NotFound
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aStoppedEngineTakesNoTorrentCommand() =
        runTest {
            val h = started()
            val id = h.torrent()
            h.engine.stop()

            val failure = runCatching { h.engine.setSequential(id, true) }.exceptionOrNull()

            failure.shouldBeInstanceOf<IllegalStateException>()
        }
}
