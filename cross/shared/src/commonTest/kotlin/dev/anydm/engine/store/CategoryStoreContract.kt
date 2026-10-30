package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.NewCategory
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Instant

/** What every [CategoryStore] must do (FIL-01), each test on a new database from [newDriver]. */
abstract class CategoryStoreContract : StoreTestBase() {
    private fun category(
        name: String,
        priority: Int = 0,
        folder: String = "/dl/$name",
        vararg rules: CategoryRule,
    ) = NewCategory(name, folder, null, rules.toList(), priority)

    @Test
    fun addThenListSortsByPriority() =
        runTest {
            val categories = newStore().categories
            val late = categories.add(category("late", priority = 5))
            val early = categories.add(category("early", priority = 1))

            categories.list() shouldBe listOf(early, late)
            early.name shouldBe "early"
        }

    @Test
    fun rulesAndTemplateRoundTrip() =
        runTest {
            val categories = newStore().categories
            val rules =
                listOf(
                    CategoryRule.Extension(setOf("mkv", "mp4")),
                    CategoryRule.Mime(setOf("video/*")),
                    CategoryRule.Domain(setOf("example.com")),
                    CategoryRule.Kind(setOf(DownloadKind.TORRENT)),
                )
            val added = categories.add(NewCategory("media", "/dl/media", "{title}", rules, 0))

            categories.list().single() shouldBe added
            added.rules shouldBe rules
            added.template shouldBe "{title}"
        }

    @Test
    fun updateChangesTheFolder() =
        runTest {
            val categories = newStore().categories
            val added = categories.add(category("docs"))
            categories.update(added.copy(folder = "/elsewhere"))

            categories.list().single().folder shouldBe "/elsewhere"
        }

    @Test
    fun removeNullsTheCategoryOfItsDownloads() =
        runTest {
            val store = newStore()
            val added = store.categories.add(category("docs"))
            val kept = store.categories.add(category("other"))
            val inDocs =
                store.downloads.add(
                    NewDownload(DownloadKind.DIRECT, categoryId = added.id),
                    queuedAt = Instant.fromEpochMilliseconds(0),
                )
            val inOther =
                store.downloads.add(
                    NewDownload(DownloadKind.DIRECT, categoryId = kept.id),
                    queuedAt = Instant.fromEpochMilliseconds(0),
                )

            store.categories.remove(added.id)

            store.categories.list() shouldBe listOf(kept)
            store.downloads.get(inDocs.id)?.categoryId shouldBe null
            store.downloads.get(inOther.id)?.categoryId shouldBe kept.id
        }
}
