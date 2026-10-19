package dev.anydm.engine.files

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeProbe
import dev.anydm.engine.store.DownloadStore
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Instant

class FilesDuplicateCheckTest {
    private val target = Path("/dl/a.zip")

    private class Rig(
        val store: Store,
        val fs: FakeFileSystem,
    ) {
        val check = FilesDuplicateCheck(store.downloads, store.history, fs)
    }

    private fun TestScope.rig() = Rig(Store(openInMemoryDatabase(), StandardTestDispatcher(testScheduler)), FakeFileSystem())

    private fun link(
        vararg urls: String,
        allowDuplicate: Boolean = false,
    ) = NewDownload(DownloadKind.DIRECT, mirrors = urls.toList(), allowDuplicate = allowDuplicate)

    @Test
    fun atAddFindsTheSameUrlOnAnotherDownload() =
        runTest {
            val r = rig()
            val existing = r.store.downloads.add(link("https://h/f.zip?utm_source=x"))

            r.check.atAdd(link("https://h/f.zip")) shouldBe DuplicateFound(existing.id, DuplicateReason.SAME_URL)
        }

    @Test
    fun atAddComparesEveryMirror() =
        runTest {
            val r = rig()
            val existing = r.store.downloads.add(link("https://one/f", "https://two/f"))

            r.check.atAdd(link("https://nope/f", "https://two/f")) shouldBe DuplicateFound(existing.id, DuplicateReason.SAME_URL)
        }

    @Test
    fun atAddIgnoresACancelledDownload() =
        runTest {
            val r = rig()
            val existing = r.store.downloads.add(link("https://h/f.zip"))
            r.store.downloads.update(existing.copy(status = DownloadStatus.CANCELLED))

            r.check.atAdd(link("https://h/f.zip")) shouldBe null
        }

    @Test
    fun atAddIsNullForAnUnrelatedUrlOrNoUrl() =
        runTest {
            val r = rig()
            r.store.downloads.add(link("https://h/f.zip"))

            r.check.atAdd(link("https://h/g.zip")) shouldBe null
            r.check.atAdd(link()) shouldBe null
        }

    @Test
    fun allowDuplicateSkipsAtAdd() =
        runTest {
            val r = rig()
            r.store.downloads.add(link("https://h/f.zip"))

            r.check.atAdd(link("https://h/f.zip", allowDuplicate = true)) shouldBe null
        }

    private suspend fun Rig.other(
        name: String = "a.zip",
        size: Long = 10,
        validator: String? = "etag:\"1\"",
    ): Download {
        val added = store.downloads.add(NewDownload(DownloadKind.DIRECT, fileName = name, size = size))
        return added.copy(sourceValidator = validator).also { store.downloads.update(it) }
    }

    private suspend fun Rig.current() = store.downloads.add(NewDownload(DownloadKind.DIRECT))

    @Test
    fun afterProbeFindsTheSameFileOnAnotherDownload() =
        runTest {
            val r = rig()
            val other = r.other()
            val current = r.current()

            r.check.afterProbe(current, FakeProbe("a.zip", 10, "etag:\"1\""), target) shouldBe
                DuplicateFound(other.id, DuplicateReason.SAME_FILE)
        }

    @Test
    fun afterProbeIgnoresADifferentEtag() =
        runTest {
            val r = rig()
            r.other(validator = "etag:\"1\"")

            r.check.afterProbe(r.current(), FakeProbe("a.zip", 10, "etag:\"2\""), target) shouldBe null
        }

    @Test
    fun afterProbeIgnoresItself() =
        runTest {
            val r = rig()
            val itself = r.other()

            r.check.afterProbe(itself, FakeProbe("a.zip", 10, "etag:\"1\""), target) shouldBe null
        }

    @Test
    fun afterProbeFindsAFileOnDiskOfTheSameSize() =
        runTest {
            val r = rig()
            r.fs.put(target, ByteArray(10))

            r.check.afterProbe(r.current(), FakeProbe("a.zip", 10), target) shouldBe DuplicateFound(null, DuplicateReason.ON_DISK)
        }

    @Test
    fun afterProbeIgnoresAFileOnDiskOfAnotherSize() =
        runTest {
            val r = rig()
            r.fs.put(target, ByteArray(11))

            r.check.afterProbe(r.current(), FakeProbe("a.zip", 10), target) shouldBe null
        }

    @Test
    fun afterProbeIgnoresAFileOnDiskWhenTheJobWasPreparedBefore() =
        runTest {
            val r = rig()
            r.fs.put(target, ByteArray(10))
            val prepared = r.current().let { it.copy(size = 10).also { d -> r.store.downloads.update(d) } }
            r.store.downloads.replaceFiles(
                prepared.id,
                listOf(
                    dev.anydm.engine.store
                        .NewFile(0, "a.zip", 10),
                ),
            )

            r.check.afterProbe(prepared, FakeProbe("a.zip", 10), target) shouldBe null
        }

    @Test
    fun allowDuplicateSkipsAfterProbe() =
        runTest {
            val r = rig()
            r.other()
            r.fs.put(target, ByteArray(10))
            val anyway = r.store.downloads.add(NewDownload(DownloadKind.DIRECT, allowDuplicate = true))

            r.check.afterProbe(anyway, FakeProbe("a.zip", 10, "etag:\"1\""), target) shouldBe null
        }

    private fun entry(
        name: String = "a.zip",
        size: Long? = 10,
        url: String = "https://h/f.zip",
    ) = HistoryEntry(DownloadId(99), name, "/dl/$name", size, Instant.fromEpochMilliseconds(1), url)

    @Test
    fun atAddFindsTheUrlInTheHistory() =
        runTest {
            val r = rig()
            r.store.history.add(entry(url = "https://h/f.zip"))

            r.check.atAdd(link("https://h/f.zip?utm_source=x")) shouldBe DuplicateFound(null, DuplicateReason.IN_HISTORY)
        }

    @Test
    fun afterProbeFindsNameAndSizeInTheHistory() =
        runTest {
            val r = rig()
            r.store.history.add(entry(name = "a.zip", size = 10))

            r.check.afterProbe(r.current(), FakeProbe("a.zip", 10), target) shouldBe DuplicateFound(null, DuplicateReason.IN_HISTORY)
            r.check.afterProbe(r.current(), FakeProbe("a.zip", 11), target) shouldBe null
        }

    @Test
    fun allowDuplicateSkipsTheHistoryToo() =
        runTest {
            val r = rig()
            r.store.history.add(entry(url = "https://h/f.zip"))

            r.check.atAdd(link("https://h/f.zip", allowDuplicate = true)) shouldBe null
        }

    @Test
    fun aJobThatWasPreparedBeforeIsNotCheckedAgain() =
        runTest {
            val r = rig()
            r.other()
            val current = r.current()
            r.store.downloads.replaceFiles(
                current.id,
                listOf(
                    dev.anydm.engine.store
                        .NewFile(0, "a.zip", 10),
                ),
            )

            r.check.afterProbe(current, FakeProbe("a.zip", 10, "etag:\"1\""), target) shouldBe null
        }

    @Test
    fun aDownloadAddedWithASizeStillGetsTheOnDiskCheck() =
        runTest {
            val r = rig()
            r.fs.put(target, ByteArray(10))
            val sized = r.store.downloads.add(NewDownload(DownloadKind.DIRECT, size = 10))

            r.check.afterProbe(sized, FakeProbe("a.zip", 10), target) shouldBe DuplicateFound(null, DuplicateReason.ON_DISK)
        }

    private class CountingDownloads(
        private val inner: DownloadStore,
    ) : DownloadStore by inner {
        var lists = 0

        override suspend fun list(): List<Download> {
            lists++
            return inner.list()
        }
    }

    @Test
    fun atAddAllAnswersLikeAskingOneByOne() =
        runTest {
            val r = rig()
            val existing = r.store.downloads.add(link("https://h/a.zip"))
            val asked = listOf(link("https://h/a.zip"), link("https://h/b.zip"), link("https://h/c.zip", allowDuplicate = true), link())

            r.check.atAddAll(asked) shouldBe asked.map { r.check.atAdd(it) }
            r.check.atAddAll(asked) shouldBe listOf(DuplicateFound(existing.id, DuplicateReason.SAME_URL), null, null, null)
        }

    @Test
    fun atAddAllReadsTheJobsOnceForAnyNumberOfLinks() =
        runTest {
            val r = rig()
            r.store.downloads.add(link("https://h/a.zip"))
            val counting = CountingDownloads(r.store.downloads)

            FilesDuplicateCheck(counting, r.store.history, r.fs).atAddAll((1..50).map { link("https://h/new$it.zip") })

            counting.lists shouldBe 1
        }
}
