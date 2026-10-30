package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.SpeedDelta
import dev.anydm.engine.net.TrafficBatch
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What every [TrafficStore] must do (NET-04), each test on a new database from [newDriver]. Driver tests extend this. */
abstract class TrafficStoreContract : StoreTestBase() {
    private val day = "2026-10-18"

    @Test
    fun networkBytesSumsDownAndUpOfOneNetworkInRange() =
        runTest {
            val traffic = newStore().traffic
            traffic.add(
                TrafficBatch(
                    days =
                        mapOf(
                            Triple("2026-10-20", "wifi", DownloadKind.DIRECT) to 100L,
                            Triple("2026-10-21", "wifi", DownloadKind.DIRECT) to 200L,
                            Triple("2026-10-21", "eth", DownloadKind.DIRECT) to 1000L,
                            Triple("2026-10-22", "wifi", DownloadKind.DIRECT) to 7L,
                        ),
                    daysUp =
                        mapOf(
                            Triple("2026-10-20", "wifi", DownloadKind.DIRECT) to 10L,
                            Triple("2026-10-21", "wifi", DownloadKind.TORRENT) to 50L,
                        ),
                ),
                pruneBefore = 0,
            )

            traffic.networkBytes("wifi", "2026-10-20", "2026-10-21") shouldBe 360L
            traffic.networkBytes("eth", "2026-10-20", "2026-10-21") shouldBe 1000L
            traffic.networkBytes("none", "2026-10-20", "2026-10-21") shouldBe 0L
        }

    @Test
    fun addAccumulates() =
        runTest {
            val traffic = newStore().traffic
            traffic.add(
                TrafficBatch(
                    days = mapOf(Triple(day, "en0", DownloadKind.DIRECT) to 100L),
                    speedDays = mapOf(day to SpeedDelta(100, 40, 3)),
                ),
                pruneBefore = 0,
            )
            traffic.add(
                TrafficBatch(
                    days = mapOf(Triple(day, "en0", DownloadKind.DIRECT) to 50L),
                    speedDays = mapOf(day to SpeedDelta(10, 70, 1)),
                ),
                pruneBefore = 0,
            )

            traffic.trafficByDay(day, day) shouldBe listOf(DayTraffic(day, "en0", DownloadKind.DIRECT, 150))
            traffic.speedDays(day, day) shouldBe listOf(SpeedDay(day, 110, 70, 4))
        }

    @Test
    fun readsAreByRange() =
        runTest {
            val traffic = newStore().traffic
            val days = listOf("2026-10-17", "2026-10-18", "2026-10-19")
            traffic.add(
                TrafficBatch(
                    days = days.associate { Triple(it, "en0", DownloadKind.SITE) to 1L },
                    speedDays = days.associateWith { SpeedDelta(1, 1, 1) },
                ),
                pruneBefore = 0,
            )

            traffic.trafficByDay("2026-10-18", "2026-10-19").map { it.day } shouldBe listOf("2026-10-18", "2026-10-19")
            traffic.speedDays("2026-10-18", "2026-10-19").map { it.day } shouldBe listOf("2026-10-18", "2026-10-19")
        }

    @Test
    fun oldMinutesArePruned() =
        runTest {
            val traffic = newStore().traffic
            traffic.add(TrafficBatch(speedMinutes = mapOf(100L to SpeedDelta(5, 5), 200L to SpeedDelta(7, 7))), pruneBefore = 0)
            traffic.add(TrafficBatch(speedMinutes = mapOf(200L to SpeedDelta(1, 9))), pruneBefore = 150)

            traffic.speedMinutes(0) shouldBe listOf(SpeedMinute(200, 8, 9))
        }

    @Test
    fun downloadTrafficGoesWithItsDownload() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.DIRECT)).id
            store.traffic.add(TrafficBatch(downloads = mapOf(id to 500L)), pruneBefore = 0)
            store.traffic.downloadTraffic(id) shouldBe 500L

            store.downloads.remove(id)

            store.traffic.downloadTraffic(id) shouldBe 0L
        }

    @Test
    fun uploadBytesAddToDaysAndDownloads() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.TORRENT)).id
            val key = Triple(day, "en0", DownloadKind.TORRENT)
            store.traffic.add(
                TrafficBatch(days = mapOf(key to 100L), daysUp = mapOf(key to 40L), downloadsUp = mapOf(id to 40L)),
                pruneBefore = 0,
            )
            store.traffic.add(TrafficBatch(daysUp = mapOf(key to 10L), downloadsUp = mapOf(id to 10L)), pruneBefore = 0)

            store.traffic.trafficByDay(day, day) shouldBe listOf(DayTraffic(day, "en0", DownloadKind.TORRENT, 100, bytesUp = 50))
            store.traffic.downloadUpload(id) shouldBe 50L
        }

    @Test
    fun downloadBytesAreUnchangedByUpload() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.TORRENT)).id
            store.traffic.add(TrafficBatch(downloads = mapOf(id to 500L), downloadsUp = mapOf(id to 70L)), pruneBefore = 0)

            store.traffic.downloadTraffic(id) shouldBe 500L
            store.traffic.downloadUpload(id) shouldBe 70L
        }

    @Test
    fun aRemovedDownloadsUploadIsDropped() =
        runTest {
            val store = newStore()
            val id = store.downloads.add(NewDownload(DownloadKind.TORRENT)).id
            store.traffic.add(TrafficBatch(downloadsUp = mapOf(id to 9L, DownloadId(999) to 9L)), pruneBefore = 0)
            store.traffic.downloadUpload(id) shouldBe 9L
            store.traffic.downloadUpload(DownloadId(999)) shouldBe 0L

            store.downloads.remove(id)

            store.traffic.downloadUpload(id) shouldBe 0L
        }

    @Test
    fun trafficOfARemovedDownloadIsDropped() =
        runTest {
            val store = newStore()
            val gone = DownloadId(999)
            store.traffic.add(
                TrafficBatch(days = mapOf(Triple(day, "en0", DownloadKind.DIRECT) to 9L), downloads = mapOf(gone to 9L)),
                pruneBefore = 0,
            )

            store.traffic
                .trafficByDay(day, day)
                .single()
                .bytes shouldBe 9L
            store.traffic.downloadTraffic(gone) shouldBe 0L
        }
}
