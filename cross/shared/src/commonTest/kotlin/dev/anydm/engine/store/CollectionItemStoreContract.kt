package dev.anydm.engine.store

import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The items of a collection (MED-16, MED-17), on a new database from [newDriver] for each test. */
abstract class CollectionItemStoreContract : StoreTestBase() {
    private fun child(n: Int) = NewDownload(DownloadKind.SITE, mirrors = listOf("https://www.example.com/watch?v=v$n"), playlistIndex = n)

    private fun item(
        n: Int,
        title: String? = "Video $n",
    ) = NewItem("youtube", "v$n", n, title, 60 + n)

    private val parent = NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST)

    @Test
    fun addCollectionStoresItemsWithTheirChildren() =
        runTest {
            val store = newStore()

            val (collection, children) = store.downloads.addCollection(parent, listOf(child(1), child(2)), listOf(item(1), item(2)))

            val items = store.collectionItems.items(collection.id)
            items.map { it.childId } shouldBe children.map { it.id }
            items.map { it.videoId } shouldBe listOf("v1", "v2")
            items[0] shouldBe CollectionItem(collection.id, "youtube", "v1", children[0].id, 1, "Video 1", 61, done = false)
            children.map { it.parentId } shouldBe listOf(collection.id, collection.id)
        }

    @Test
    fun itemsAreOrderedByPlaylistIndex() =
        runTest {
            val store = newStore()

            val (collection, _) =
                store.downloads.addCollection(
                    parent,
                    listOf(child(3), child(1), child(2)),
                    listOf(item(3), item(1), item(2)),
                )

            store.collectionItems.items(collection.id).map { it.playlistIndex } shouldBe listOf(1, 2, 3)
        }

    @Test
    fun addChildrenAddsMore() =
        runTest {
            val store = newStore()
            val (collection, _) = store.downloads.addCollection(parent, listOf(child(1)), listOf(item(1)))

            val more = store.downloads.addChildren(collection.id, listOf(child(2)), listOf(item(2)))

            more.single().parentId shouldBe collection.id
            store.collectionItems.items(collection.id).size shouldBe 2
        }

    @Test
    fun addChildrenToAGoneCollectionAddsNothing() =
        runTest {
            val store = newStore()
            val (collection, _) = store.downloads.addCollection(parent, emptyList(), emptyList())
            store.downloads.remove(collection.id)

            store.downloads.addChildren(collection.id, listOf(child(1)), listOf(item(1))) shouldBe emptyList()
            store.downloads.list() shouldBe emptyList()
        }

    @Test
    fun childrenAndItemsMustMatch() =
        runTest {
            val store = newStore()

            shouldThrow<IllegalArgumentException> { store.downloads.addCollection(parent, listOf(child(1)), emptyList()) }
        }

    @Test
    fun markDoneSetsDoneOnce() =
        runTest {
            val store = newStore()
            val (collection, children) = store.downloads.addCollection(parent, listOf(child(1), child(2)), listOf(item(1), item(2)))

            val done = store.collectionItems.markDone(children[0].id)

            done!!.done shouldBe true
            store.collectionItems.markDone(children[0].id)!!.done shouldBe true
            store.collectionItems.items(collection.id).map { it.done } shouldBe listOf(true, false)
            store.collectionItems.markDone(collection.id) shouldBe null
        }

    @Test
    fun byChildFindsTheItem() =
        runTest {
            val store = newStore()
            val (_, children) = store.downloads.addCollection(parent, listOf(child(1)), listOf(item(1)))

            store.collectionItems.byChild(children[0].id)!!.videoId shouldBe "v1"
            store.collectionItems.byChild(
                children[0].id.let {
                    dev.anydm.engine.core
                        .DownloadId(it.value + 99)
                },
            ) shouldBe null
        }

    @Test
    fun forgetResetsTheItemSoItCanBeFetchedAgain() =
        runTest {
            val store = newStore()
            val (collection, children) = store.downloads.addCollection(parent, listOf(child(1)), listOf(item(1)))
            store.collectionItems.markDone(children[0].id)

            store.collectionItems.forget(collection.id, "youtube", "v1") shouldBe true

            val reset = store.collectionItems.items(collection.id).single()
            reset.done shouldBe false
            reset.childId shouldBe null
            reset.title shouldBe "Video 1"
            store.collectionItems.forget(collection.id, "youtube", "nope") shouldBe false
        }

    @Test
    fun relinkPointsAtANewChild() =
        runTest {
            val store = newStore()
            val (collection, children) = store.downloads.addCollection(parent, listOf(child(1)), listOf(item(1)))
            store.downloads.remove(children[0].id)
            val again = store.downloads.addChildren(collection.id, listOf(child(2)), listOf(item(2))).single()

            store.collectionItems.relink(collection.id, "youtube", "v1", again.id) shouldBe true

            store.collectionItems
                .items(collection.id)
                .first()
                .childId shouldBe again.id
            store.collectionItems.relink(collection.id, "youtube", "nope", again.id) shouldBe false
        }

    @Test
    fun anItemAddedAgainReplacesTheOldRow() =
        runTest {
            val store = newStore()
            val (collection, children) = store.downloads.addCollection(parent, listOf(child(1)), listOf(item(1)))
            store.downloads.remove(children[0].id)

            val again = store.downloads.addChildren(collection.id, listOf(child(1)), listOf(item(1))).single()

            store.collectionItems
                .items(collection.id)
                .single()
                .childId shouldBe again.id
        }

    @Test
    fun removingTheChildClearsChildIdAndKeepsTheRow() =
        runTest {
            val store = newStore()
            val (collection, children) = store.downloads.addCollection(parent, listOf(child(1), child(2)), listOf(item(1), item(2)))
            store.collectionItems.markDone(children[0].id)

            store.downloads.remove(children[0].id)
            store.downloads.remove(children[1].id)

            val items = store.collectionItems.items(collection.id)
            items.map { it.childId } shouldBe listOf(null, null)
            items.map { it.done } shouldBe listOf(true, false)
        }

    @Test
    fun removingTheCollectionRemovesItsItems() =
        runTest {
            val store = newStore()
            val (collection, _) = store.downloads.addCollection(parent, listOf(child(1)), listOf(item(1)))

            store.downloads.remove(collection.id)

            store.collectionItems.items(collection.id) shouldBe emptyList()
        }
}
