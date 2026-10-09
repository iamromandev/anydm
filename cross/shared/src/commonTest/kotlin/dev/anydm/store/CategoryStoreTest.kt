package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.model.DOWNLOADS_CATEGORY_ID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CategoryStoreTest {
    private val api = FakeCategoryApi()

    private fun store() = CategoryStore(api)

    @Test
    fun `load lists the categories`() =
        runTest {
            val store = store()
            store.load()
            assertEquals(
                listOf("Downloads", "Music"),
                store.state.value.categories
                    .map { it.name },
            )
            assertTrue(store.state.value.loaded)
        }

    @Test
    fun `create, update and delete reload the list`() =
        runTest {
            val store = store()
            store.load()
            assertTrue(store.create("Lectures", "edu"))
            assertEquals(
                "Lectures",
                store.state.value.categories
                    .last()
                    .name,
            )
            assertTrue(store.update("c1", "Songs", null))
            assertEquals(
                "Songs",
                store.state.value.categories[1]
                    .name,
            )
            assertTrue(store.delete("c1"))
            assertFalse(
                store.state.value.categories
                    .any { it.id == "c1" },
            )
        }

    @Test
    fun `move swaps neighbours and refuses past either end`() =
        runTest {
            val store = store()
            store.load()
            assertTrue(store.move(1, -1))
            assertEquals(listOf("c1", DOWNLOADS_CATEGORY_ID), api.ordered.last())
            assertFalse(store.move(0, -1))
        }

    @Test
    fun `a refusal keeps the server's words in state`() =
        runTest {
            val store = store()
            store.load()
            api.failWith = ApiException("Music still holds 1 download; move them first", 409, null)
            assertFalse(store.delete("c1"))
            assertEquals("Music still holds 1 download; move them first", store.state.value.error)
            store.clearError()
            assertEquals(null, store.state.value.error)
        }
}
