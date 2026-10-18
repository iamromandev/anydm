package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/** What every [ExtractionStore] must do (MED-01), each test on a new database from [newDriver]. */
abstract class ExtractionStoreContract : StoreTestBase() {
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_123)

    private fun extraction(
        id: DownloadId,
        json: String = "{\"id\":\"a\"}",
    ) = Extraction(id, "https://example.com/watch", json, at)

    @Test
    fun anExtractionRoundTrips() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.SITE)).id

            store.extractions.put(extraction(id)) shouldBe true

            store.extractions.get(id) shouldBe extraction(id)
        }

    @Test
    fun putReplacesTheEarlierOne() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.SITE)).id
            store.extractions.put(extraction(id, "{\"v\":1}"))

            store.extractions.put(extraction(id, "{\"v\":2}"))

            store.extractions.get(id)!!.json shouldBe "{\"v\":2}"
            driver.count("extraction") shouldBe 1L
        }

    @Test
    fun putForAMissingDownloadIsRefused() =
        runTest {
            val store = newStore()

            store.extractions.put(extraction(DownloadId(999))) shouldBe false
            store.extractions.get(DownloadId(999)) shouldBe null
        }

    @Test
    fun deleteRemovesIt() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.SITE)).id
            store.extractions.put(extraction(id))

            store.extractions.delete(id)

            store.extractions.get(id) shouldBe null
        }

    @Test
    fun removingTheDownloadDropsIt() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.SITE)).id
            store.extractions.put(extraction(id))

            store.downloads.remove(id)

            store.extractions.get(id) shouldBe null
            driver.count("extraction") shouldBe 0L
        }
}
