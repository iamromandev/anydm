package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.namesIn
import dev.anydm.engine.platform.newTempFolder
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

// The real thing: a file opened through openStore, with its own dispatcher, hit from many threads at once.
class ConcurrentStoreTest {
    private val root = newTempFolder("anydm-store-test-")

    @AfterTest
    fun deleteRoot() = deleteRecursively(root)

    @Test
    fun manyCallersAtOnceNeitherCollideNorLoseWrites() =
        runTest(timeout = 60.seconds) {
            val dirs = AppDirs(data = Path(root, "data"), logs = Path(root, "logs"), cache = Path(root, "cache"))
            val opened = openStore(dirs, DesktopFileSystem()).shouldBeInstanceOf<FileResult.Ok<Store>>()
            opened.value.use { store ->
                val downloads = store.downloads
                val base = downloads.add(NewDownload(DownloadKind.DIRECT))
                val file = downloads.replaceFiles(base.id, listOf(NewFile(0, "a.mp4", size = 2_000))).single()
                val segments = downloads.replaceSegments(file.id, List(20) { NewSegment(it * 100L, it * 100L + 100) })

                fun urls(i: Int) = List(i % 3 + 1) { "https://example.com/$i/$it" }

                val (added, limits) =
                    withContext(Dispatchers.Default) {
                        val adds = List(60) { i -> async { downloads.add(NewDownload(DownloadKind.DIRECT, urls(i), priority = i)) } }
                        val watermarks =
                            segments.mapIndexed { i, s -> async { downloads.saveWatermarks(mapOf(s.id to i * 100L + 50)) } }
                        val settings = List(20) { n -> async { store.settings.update { it.copy(concurrencyLimit = n + 1) } } }
                        val reads = List(20) { async { downloads.list().size } }
                        watermarks.awaitAll()
                        reads.awaitAll()
                        adds.awaitAll() to settings.awaitAll().map(Settings::concurrencyLimit)
                    }

                // One writer at a time: ids come out unique and without gaps, every download has its own mirrors.
                (listOf(base) + added).map { it.id.value }.sorted() shouldBe (1L..61L).toList()
                added.forEachIndexed { i, download ->
                    downloads.mirrors(download.id).map { it.url } shouldBe urls(i)
                    download.priority shouldBe i
                }
                downloads.segments(file.id).map { it.watermark } shouldBe List(20) { it * 100L + 50 }
                downloads.list().size shouldBe 61
                // Each update returns what it stored; the last one to run is what is there now.
                limits.sorted() shouldBe (1..20).toList()
                limits shouldContain store.settings.load().concurrencyLimit
            }
            namesIn(Path(root, "data")) shouldBe setOf(DATABASE_FILE)
        }
}
