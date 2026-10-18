package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.files.FilesDuplicateCheck
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.store.NewDownload
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What the queue does with a duplicate at add time (FIL-05, FIL-06), on a new database from [newDriver]. */
abstract class QueueDuplicateContract : QueueTestBase() {
    private suspend fun TestScope.queue(): QueueHarness =
        started {
            val store = newStore()
            val fs = FakeFileSystem()
            QueueHarness(it, store, fs = fs, duplicates = FilesDuplicateCheck(store.downloads, store.history, fs))
        }

    private fun link(
        url: String = "https://h/f.zip",
        allowDuplicate: Boolean = false,
        parent: dev.anydm.engine.core.DownloadId? = null,
    ) = NewDownload(DownloadKind.DIRECT, mirrors = listOf(url), allowDuplicate = allowDuplicate, parentId = parent)

    @Test
    fun addReturnsDuplicateAndAddsNothing() =
        runTest {
            val h = queue()
            val first =
                h.queue
                    .add(link())
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!

            val second = h.queue.add(link("https://h/f.zip?utm_source=mail"))

            second shouldBe CommandResult.Duplicate(DuplicateFound(first.id, DuplicateReason.SAME_URL))
            h.store.downloads
                .list()
                .map { it.id } shouldBe listOf(first.id)
        }

    @Test
    fun addAnywayAddsIt() =
        runTest {
            val h = queue()
            h.queue.add(link())

            val again =
                h.queue
                    .add(link(allowDuplicate = true))
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!

            again.allowDuplicate shouldBe true
            h.store.downloads
                .list()
                .size shouldBe 2
        }

    @Test
    fun aCancelledDownloadDoesNotBlockAddingTheLinkAgain() =
        runTest {
            val h = queue()
            val first =
                h.queue
                    .add(link())
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            h.store.downloads.update(first.copy(status = DownloadStatus.CANCELLED))

            h.queue.add(link()).shouldBeInstanceOf<CommandResult.Done>()
        }

    @Test
    fun playlistItemsAreCheckedOneByOne() =
        runTest {
            val h = queue()
            val parent =
                h.queue
                    .add(NewDownload(DownloadKind.SITE))
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
                    .id
            h.queue.add(link("https://h/a"))

            val results = listOf("https://h/a", "https://h/b", "https://h/c").map { h.queue.add(link(it, parent = parent)) }

            results.map { it::class } shouldBe
                listOf(CommandResult.Duplicate::class, CommandResult.Done::class, CommandResult.Done::class)
        }
}
