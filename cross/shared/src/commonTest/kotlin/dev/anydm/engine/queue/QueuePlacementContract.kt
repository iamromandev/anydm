package dev.anydm.engine.queue

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.FilesPlacement
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.platform.FakeFileSystem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** What the queue keeps of the placement decisions (FIL-01). */
abstract class QueuePlacementContract : QueueTestBase() {
    @Test
    fun aJobKeepsTheCategoryItWasPlacedIn() =
        runTest {
            val fs = FakeFileSystem()
            val zips = Category(CategoryId(7), "zips", "/dl/zips", null, listOf(CategoryRule.Extension(setOf("zip"))), 0)
            val placement = FilesPlacement(NameCleaner(OsProfile.LINUX), { listOf(zips) }, { ClashPolicy.RENAME }, fs)
            val h = started { QueueHarness(it, newStore(), fs = fs, placement = placement) }
            val id = h.addJob()
            settle()

            h.status(id) shouldBe COMPLETED
            h.store.downloads
                .get(id)!!
                .categoryId shouldBe CategoryId(7)
        }
}
