package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewTorrent
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.torrent.FakeTorrentService
import dev.anydm.engine.torrent.SeedEnd
import dev.anydm.engine.torrent.SeedEndReason
import dev.anydm.engine.torrent.SeedRule
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

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
    fun aChoiceOnAJobAddedWithoutOneIsAChangeOfSelection() =
        runTest {
            val h = started()
            try {
                // no choice stored means every file is selected
                val id =
                    h.engine
                        .addTorrent(magnet)
                        .added()
                        .id
                service.running += id

                h.engine.setTorrentFiles(id, listOf(7, 7)).shouldBeInstanceOf<CommandResult.Done>()
                service.appliedPriorities[id] shouldBe listOf(7, 7)

                // skipping a file the running torrent was started with waits for the next run
                h.engine.setTorrentFiles(id, listOf(4, 0)).shouldBeInstanceOf<CommandResult.Done>()
                service.appliedPriorities[id] shouldBe listOf(7, 7)
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

    // Seeding (TOR-05): a torrent whose protocol says "seeding" at finalize; FakeProtocol serves it in 4 s.

    private suspend fun TestScope.seeding(h: EngineHarness): DownloadId {
        h.protocol.seeding = true
        val id = h.torrent()
        advanceTimeBy(6.seconds)
        runCurrent()
        h.status(id) shouldBe DownloadStatus.SEEDING
        service.seeding += id
        return id
    }

    private fun TestScope.eventsOf(h: EngineHarness): List<EngineEvent> =
        mutableListOf<EngineEvent>().also { events -> backgroundScope.launch { h.engine.events.collect { events += it } } }

    @Test
    fun aSeedEndedByRuleBecomesCompletedAndEmitsCompleted() =
        runTest {
            val h = started()
            try {
                val events = eventsOf(h)
                runCurrent()
                val id = seeding(h)

                service.seedEnds.emit(SeedEnd.Ended(id, SeedEndReason.RULE))
                runCurrent()

                h.status(id) shouldBe DownloadStatus.COMPLETED
                events.filterIsInstance<EngineEvent.Completed>().map { it.id } shouldBe listOf(id)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aFailedSeedBecomesFailedWithItsMessage() =
        runTest {
            val h = started()
            try {
                val events = eventsOf(h)
                runCurrent()
                val id = seeding(h)

                service.seedEnds.emit(SeedEnd.Failed(id, "the files are gone"))
                runCurrent()

                h.status(id) shouldBe DownloadStatus.FAILED
                h.store.downloads
                    .attempts(id)
                    .last()
                    .message shouldBe "the files are gone"
                events.filterIsInstance<EngineEvent.Failed>().single().message shouldBe "the files are gone"
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun stopSeedingCompletesTheJob() =
        runTest {
            val h = started()
            try {
                val id = seeding(h)

                h.engine.stopSeeding(id).shouldBeInstanceOf<CommandResult.Done>()
                runCurrent()

                service.stopped shouldBe listOf(id)
                h.status(id) shouldBe DownloadStatus.COMPLETED
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun stopSeedingOnADownloadingJobIsRejected() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()
                advanceTimeBy(1.seconds)
                runCurrent()
                h.status(id) shouldBe DownloadStatus.DOWNLOADING

                h.engine.stopSeeding(id).shouldBeInstanceOf<CommandResult.Rejected>()
                h.engine.stopSeeding(DownloadId(9999)) shouldBe CommandResult.NotFound

                service.stopped shouldBe emptyList()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun setSeedRulesStoresAndValidates() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()

                h.engine.setSeedRules(id, SeedRule.At(2.0), SeedRule.Off).shouldBeInstanceOf<CommandResult.Done>()

                h.store.torrents
                    .get(id)!!
                    .seedRatio shouldBe SeedRule.At(2.0)
                h.store.torrents
                    .get(id)!!
                    .seedMinutes shouldBe SeedRule.Off
                h.engine.setSeedRules(id, SeedRule.At(0.0), SeedRule.Inherit).shouldBeInstanceOf<CommandResult.Invalid>()
                h.engine.setSeedRules(id, SeedRule.Inherit, SeedRule.At(-1)).shouldBeInstanceOf<CommandResult.Invalid>()
                h.engine.setSeedRules(DownloadId(9999), SeedRule.Inherit, SeedRule.Inherit) shouldBe CommandResult.NotFound
                h.engine.setSeedRules(h.addJob(), SeedRule.Inherit, SeedRule.Inherit) shouldBe CommandResult.NotFound
                h.store.torrents
                    .get(id)!!
                    .seedRatio shouldBe SeedRule.At(2.0)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun addTorrentCarriesSeedRules() =
        runTest {
            val h = started()
            try {
                val id =
                    h.engine
                        .addTorrent(magnet, info = pack, seedRatio = SeedRule.At(1.5), seedMinutes = SeedRule.Off)
                        .added()
                        .id

                h.store.torrents
                    .get(id)!!
                    .seedRatio shouldBe SeedRule.At(1.5)
                h.store.torrents
                    .get(id)!!
                    .seedMinutes shouldBe SeedRule.Off
            } finally {
                h.engine.stop()
            }
        }

    /** A torrent job the last run left SEEDING, in a store no engine has started on yet. */
    private suspend fun seededBeforeRestart(store: Store): DownloadId {
        val job =
            store.downloads.add(
                NewDownload(
                    DownloadKind.TORRENT,
                    mirrors = listOf(magnet),
                    saveFolder = stepRoot.toString(),
                    fileName = "Pack",
                    torrent = NewTorrent(pack.metadata),
                ),
            )
        store.downloads.update(job.copy(status = DownloadStatus.SEEDING))
        return job.id
    }

    @Test
    fun aSeedingJobIsResumedAtStart() =
        runTest {
            val store = newStore()
            val h = EngineHarness(this, store, torrents = service)
            val id = seededBeforeRestart(store)

            h.engine.start()
            runCurrent()

            h.protocol.resumeSeedingCalls shouldBe listOf(id)
            h.status(id) shouldBe DownloadStatus.SEEDING
            h.engine.stop()
        }

    @Test
    fun aSeedWhoseResumeFailsBecomesFailedAtStart() =
        runTest {
            val store = newStore()
            val h = EngineHarness(this, store, torrents = service)
            val id = seededBeforeRestart(store)
            h.protocol.resumeSeedingResult = StepResult.Failed(null, "the seeded file is gone")

            h.engine.start()
            runCurrent()

            h.status(id) shouldBe DownloadStatus.FAILED
            h.store.downloads
                .attempts(id)
                .last()
                .message shouldBe "the seeded file is gone"
            h.engine.stop()
        }

    @Test
    fun aSeedThatCantBeTakenUpYetStaysSeeding() =
        runTest {
            val store = newStore()
            val h = EngineHarness(this, store, torrents = service)
            val id = seededBeforeRestart(store)
            h.protocol.resumeSeedingResult = StepResult.Failed(ErrorClass.TRANSIENT, "torrents aren't supported here")

            h.engine.start()
            runCurrent()

            h.status(id) shouldBe DownloadStatus.SEEDING
            h.engine.stop()
        }

    @Test
    fun seedingJobsHoldNoSlot() =
        runTest {
            val h = started()
            try {
                h.store.settings.update { it.copy(concurrencyLimit = 1) }
                val seed = seeding(h)
                h.protocol.seeding = false
                val other = h.addJob()
                advanceTimeBy(1.seconds)
                runCurrent()

                h.status(seed) shouldBe DownloadStatus.SEEDING
                h.status(other) shouldBe DownloadStatus.DOWNLOADING
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun pauseAndCancelOfASeedStayRejected() =
        runTest {
            val h = started()
            try {
                val id = seeding(h)

                h.engine.pause(id).shouldBeInstanceOf<CommandResult.Rejected>()
                h.engine.cancel(id).shouldBeInstanceOf<CommandResult.Rejected>()

                h.status(id) shouldBe DownloadStatus.SEEDING
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun removingASeedEndsItsSeedWithoutCompletingTheJob() =
        runTest {
            val h = started()
            try {
                val events = eventsOf(h)
                runCurrent()
                val id = seeding(h)

                h.engine.remove(id, RemoveFiles.KEEP).shouldBeInstanceOf<CommandResult.Done>()
                runCurrent()

                service.stopped shouldBe listOf(id)
                h.snapshot(id) shouldBe null
                events.filterIsInstance<EngineEvent.Completed>() shouldBe emptyList()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aChangedSelectionClearsTheStoredResume() =
        runTest {
            val h = started()
            try {
                val id = h.torrent()
                h.store.torrents.setResume(id, "blob".encodeToByteArray())

                // the same files at other priorities keep it: the pieces still match
                h.engine.setTorrentFiles(id, listOf(7, 1)).shouldBeInstanceOf<CommandResult.Done>()
                h.store.torrents
                    .get(id)!!
                    .resume!!
                    .decodeToString() shouldBe "blob"

                h.engine.setTorrentFiles(id, listOf(0, 4)).shouldBeInstanceOf<CommandResult.Done>()
                h.store.torrents
                    .get(id)!!
                    .resume shouldBe null
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun setGlobalUploadLimitReachesTheEngine() =
        runTest {
            val h = started()
            try {
                h.engine.setGlobalUploadLimit(50_000).shouldBeInstanceOf<CommandResult.Done>()

                h.store.settings
                    .load()
                    .globalUploadLimit shouldBe 50_000L
                h.protocol.globalLimits.last() shouldBe (null to 50_000L)
                h.engine.setGlobalUploadLimit(0).shouldBeInstanceOf<CommandResult.Invalid>()
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
