package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.torrent.SeedRule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** A torrent job's metadata and options (TOR-02, TOR-03), on a new database from [newDriver] for each test. */
abstract class TorrentStoreContract : StoreTestBase() {
    private val bytes = ByteArray(300) { it.toByte() }

    private fun torrentJob(torrent: NewTorrent? = NewTorrent(bytes, listOf(0, 4, 7), sequential = true)) =
        NewDownload(DownloadKind.TORRENT, mirrors = listOf("magnet:?xt=urn:btih:" + "a".repeat(40)), torrent = torrent)

    @Test
    fun aTorrentRowRoundTripsWithItsMetadata() =
        runTest {
            val store = newStore()
            val job = store.downloads.add(torrentJob())

            val row = store.torrents.get(job.id)!!

            row.downloadId shouldBe job.id
            row.metadata!!.toList() shouldBe bytes.toList()
            row.priorities shouldBe listOf(0, 4, 7)
            row.sequential shouldBe true
        }

    @Test
    fun addWritesTheRowWithTheJob() =
        runTest {
            val store = newStore()

            val job = store.downloads.add(torrentJob(NewTorrent()))

            val row = store.torrents.get(job.id)!!
            row.metadata shouldBe null
            row.priorities shouldBe emptyList()
            row.sequential shouldBe false
        }

    @Test
    fun noTorrentMeansNoRow() =
        runTest {
            val store = newStore()

            store.torrents.get(store.downloads.add(torrentJob(null)).id) shouldBe null
        }

    @Test
    fun settersChangeTheRow() =
        runTest {
            val store = newStore()
            val job = store.downloads.add(torrentJob(NewTorrent()))

            store.torrents.setMetadata(job.id, bytes) shouldBe true
            store.torrents.setPriorities(job.id, listOf(4, 4)) shouldBe true
            store.torrents.setSequential(job.id, true) shouldBe true

            val row = store.torrents.get(job.id)!!
            row.metadata!!.toList() shouldBe bytes.toList()
            row.priorities shouldBe listOf(4, 4)
            row.sequential shouldBe true
        }

    @Test
    fun settersReturnFalseForAJobWithNoRow() =
        runTest {
            val store = newStore()

            store.torrents.setMetadata(DownloadId(99), bytes) shouldBe false
            store.torrents.setPriorities(DownloadId(99), listOf(4)) shouldBe false
            store.torrents.setSequential(DownloadId(99), true) shouldBe false
        }

    @Test
    fun removingTheJobRemovesTheRow() =
        runTest {
            val store = newStore()
            val job = store.downloads.add(torrentJob())

            store.downloads.remove(job.id)

            store.torrents.get(job.id) shouldBe null
        }

    @Test
    fun resumeRoundTripsAndClears() =
        runTest {
            val store = newStore()
            val job = store.downloads.add(torrentJob())
            store.torrents.get(job.id)!!.resume shouldBe null

            store.torrents.setResume(job.id, bytes) shouldBe true
            store.torrents
                .get(job.id)!!
                .resume!!
                .toList() shouldBe bytes.toList()

            store.torrents.setResume(job.id, null) shouldBe true
            store.torrents.get(job.id)!!.resume shouldBe null
        }

    @Test
    fun seedRulesRoundTrip() =
        runTest {
            val store = newStore()
            val job = store.downloads.add(torrentJob())
            store.torrents.get(job.id)!!.let {
                it.seedRatio shouldBe SeedRule.Inherit
                it.seedMinutes shouldBe SeedRule.Inherit
            }

            store.torrents.setSeedRules(job.id, SeedRule.At(1.5), SeedRule.At(90)) shouldBe true
            store.torrents.get(job.id)!!.let {
                it.seedRatio shouldBe SeedRule.At(1.5)
                it.seedMinutes shouldBe SeedRule.At(90)
            }

            store.torrents.setSeedRules(job.id, SeedRule.Off, SeedRule.Off) shouldBe true
            store.torrents.get(job.id)!!.let {
                it.seedRatio shouldBe SeedRule.Off
                it.seedMinutes shouldBe SeedRule.Off
            }

            store.torrents.setSeedRules(job.id, SeedRule.Inherit, SeedRule.Inherit) shouldBe true
            store.torrents.get(job.id)!!.seedRatio shouldBe SeedRule.Inherit
        }

    @Test
    fun anAtOfZeroOrLessIsRefused() =
        runTest {
            val store = newStore()
            val job = store.downloads.add(torrentJob())

            shouldThrow<IllegalArgumentException> { store.torrents.setSeedRules(job.id, SeedRule.At(0.0), SeedRule.Inherit) }
            shouldThrow<IllegalArgumentException> { store.torrents.setSeedRules(job.id, SeedRule.Inherit, SeedRule.At(-1)) }
            shouldThrow<IllegalArgumentException> { NewTorrent(seedRatio = SeedRule.At(-0.5)) }
            shouldThrow<IllegalArgumentException> { NewTorrent(seedMinutes = SeedRule.At(0)) }
        }

    @Test
    fun settersOnAJobWithNoTorrentRowReturnFalse() =
        runTest {
            val store = newStore()

            store.torrents.setResume(DownloadId(99), bytes) shouldBe false
            store.torrents.setSeedRules(DownloadId(99), SeedRule.Off, SeedRule.Off) shouldBe false
        }

    @Test
    fun anAddedTorrentCarriesItsSeedRules() =
        runTest {
            val store = newStore()

            val job = store.downloads.add(torrentJob(NewTorrent(seedRatio = SeedRule.At(2.0), seedMinutes = SeedRule.Off)))

            store.torrents.get(job.id)!!.let {
                it.seedRatio shouldBe SeedRule.At(2.0)
                it.seedMinutes shouldBe SeedRule.Off
                it.resume shouldBe null
            }
        }

    @Test
    fun aPriorityOutOfRangeIsRefused() =
        runTest {
            for (bad in listOf(8, -1)) shouldThrow<IllegalArgumentException> { NewTorrent(priorities = listOf(4, bad)) }
            val store = newStore()
            val job = store.downloads.add(torrentJob())

            shouldThrow<IllegalArgumentException> { store.torrents.setPriorities(job.id, listOf(9)) }
        }
}
