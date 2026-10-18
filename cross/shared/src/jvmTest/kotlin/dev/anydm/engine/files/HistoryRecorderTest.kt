package dev.anydm.engine.files

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class HistoryRecorderTest {
    private fun TestScope.store() = Store(openInMemoryDatabase(), StandardTestDispatcher(testScheduler))

    private suspend fun Store.job(
        url: String,
        name: String = "a.zip",
        size: Long? = 10,
    ) = downloads.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf(url), fileName = name, size = size))

    @Test
    fun aFinishedDownloadIsRecorded() =
        runTest {
            val store = store()
            val clock = virtualClock()
            val job = store.job("https://h/a.zip?utm_source=mail")

            HistoryRecorder(store.history, store.downloads, clock) { 100 }.finished(job, listOf(Path("/dl/a.zip")))

            val entry = store.history.list(10).single()
            entry.downloadId shouldBe job.id
            entry.name shouldBe "a.zip"
            entry.path shouldBe "/dl/a.zip"
            entry.size shouldBe 10L
            entry.sourceUrl shouldBe "https://h/a.zip"
            entry.finishedAt shouldBe clock.now()
        }

    @Test
    fun overTheCapTheOldestGoFirst() =
        runTest {
            val store = store()
            val recorder = HistoryRecorder(store.history, store.downloads, virtualClock()) { 2 }

            for (i in 1..3) recorder.finished(store.job("https://h/$i", name = "f$i.zip"), listOf(Path("/dl/f$i.zip")))

            store.history.list(10).map { it.name } shouldBe listOf("f3.zip", "f2.zip")
        }

    @Test
    fun aDownloadWithoutOutputsIsRecordedAtItsFolder() =
        runTest {
            val store = store()
            val job =
                store.downloads.add(
                    NewDownload(DownloadKind.TORRENT, mirrors = listOf("magnet:?xt=1"), saveFolder = "/dl", fileName = "t"),
                )

            HistoryRecorder(store.history, store.downloads, virtualClock()) { 100 }.finished(job, emptyList())

            store.history
                .list(1)
                .single()
                .path shouldBe "/dl"
        }
}
