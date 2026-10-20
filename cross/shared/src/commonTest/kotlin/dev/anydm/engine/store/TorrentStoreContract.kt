package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
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
    fun aPriorityOutOfRangeIsRefused() =
        runTest {
            for (bad in listOf(8, -1)) shouldThrow<IllegalArgumentException> { NewTorrent(priorities = listOf(4, bad)) }
            val store = newStore()
            val job = store.downloads.add(torrentJob())

            shouldThrow<IllegalArgumentException> { store.torrents.setPriorities(job.id, listOf(9)) }
        }
}
