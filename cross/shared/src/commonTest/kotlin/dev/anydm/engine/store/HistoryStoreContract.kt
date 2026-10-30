package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/** What every [HistoryStore] must do (FIL-10), each test on a new database from [newDriver]. */
abstract class HistoryStoreContract : StoreTestBase() {
    private fun entry(
        id: Long,
        name: String = "f$id.zip",
        size: Long? = 10,
        at: Long = id,
        url: String = "https://h/f$id.zip",
    ) = HistoryEntry(DownloadId(id), name, "/dl/$name", size, Instant.fromEpochMilliseconds(at), url)

    @Test
    fun listIsNewestFirstAndLimited() =
        runTest {
            val history = newStore().history
            for (i in 1L..5L) history.add(entry(i))

            history.list(3).map { it.downloadId.value } shouldBe listOf(5L, 4L, 3L)
            history.list(10).size shouldBe 5
        }

    @Test
    fun clearEmptiesIt() =
        runTest {
            val history = newStore().history
            for (i in 1L..3L) history.add(entry(i))

            history.clear()

            history.list(10) shouldBe emptyList()
        }

    @Test
    fun theSameMomentIsBrokenByInsertionOrder() =
        runTest {
            val history = newStore().history
            history.add(entry(1, at = 100))
            history.add(entry(2, at = 100))

            history.list(10).map { it.downloadId.value } shouldBe listOf(2L, 1L)
        }

    @Test
    fun pruneKeepsTheNewest() =
        runTest {
            val history = newStore().history
            for (i in 1L..5L) history.add(entry(i))

            history.prune(keep = 3)

            history.list(10).map { it.downloadId.value } shouldBe listOf(5L, 4L, 3L)
            history.prune(keep = 10)
            history.list(10).size shouldBe 3
        }

    @Test
    fun entriesRoundTrip() =
        runTest {
            val history = newStore().history
            val e = entry(7, name = "é 🎬.mp4", size = null, at = 1_700_000_000_123)
            history.add(e)

            history.list(1).single() shouldBe e
        }

    @Test
    fun findByUrl() =
        runTest {
            val history = newStore().history
            history.add(entry(1, url = "https://h/a"))
            history.add(entry(2, url = "https://h/b"))

            history.findByUrl("https://h/b")?.downloadId shouldBe DownloadId(2)
            history.findByUrl("https://h/none") shouldBe null
        }

    @Test
    fun findByFileMatchesNameIgnoringCaseAndSize() =
        runTest {
            val history = newStore().history
            history.add(entry(1, name = "A.zip", size = 10))

            history.findByFile("a.ZIP", 10)?.downloadId shouldBe DownloadId(1)
            history.findByFile("a.zip", 11) shouldBe null
            history.findByFile("b.zip", 10) shouldBe null
        }

    @Test
    fun historySurvivesTheRemovalOfItsDownload() =
        runTest {
            val store = newStore()
            val added = store.downloads.add(NewDownload(dev.anydm.engine.core.DownloadKind.DIRECT))
            store.history.add(entry(added.id.value))

            store.downloads.remove(added.id)

            store.history.list(10).size shouldBe 1
        }
}
