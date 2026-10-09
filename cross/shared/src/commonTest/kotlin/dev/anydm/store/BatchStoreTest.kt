package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.api.BatchApi
import dev.anydm.model.BatchItemDto
import dev.anydm.model.BatchKind
import dev.anydm.model.BatchOutcome
import dev.anydm.model.BatchPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted batch API: what a preview of each text answers, and what an add answers. */
private class FakeBatchApi : BatchApi {
    val previews = mutableListOf<Pair<BatchKind, String>>()
    val adds = mutableListOf<Triple<BatchKind, String, String>>()
    val addedCategories = mutableListOf<String?>()
    var gate: CompletableDeferred<Unit>? = null
    var previewFails: ApiException? = null
    var addFails: ApiException? = null
    var items =
        listOf(
            BatchItemDto("a", "added", "d1"),
            BatchItemDto("b", "duplicate", "d0", "Already in your list: b (completed)"),
            BatchItemDto("c", "error", null, "Video unavailable"),
        )

    override suspend fun previewBatch(
        kind: BatchKind,
        text: String,
    ): BatchPreview {
        previews += kind to text
        gate?.await()
        previewFails?.let { throw it }
        val urls = text.lines().filter { it.isNotBlank() }
        return BatchPreview(urls.size, urls)
    }

    override suspend fun addBatch(
        kind: BatchKind,
        text: String,
        preset: String,
        categoryId: String?,
    ): List<BatchItemDto> {
        adds += Triple(kind, text, preset)
        addedCategories += categoryId
        addFails?.let { throw it }
        return items
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BatchStoreTest {
    private val api = FakeBatchApi()
    private var added = 0

    private fun TestScope.store(initial: String = ""): BatchStore = BatchStore(api, backgroundScope, initial, onAdded = { added += 1 })

    @Test
    fun `the preview waits for typing to pause, then asks once for the latest text`() =
        runTest {
            val store = store()
            store.setText("a")
            advanceTimeBy(200)
            store.setText("a\nb")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS - 1)
            runCurrent()
            assertTrue(api.previews.isEmpty())
            assertTrue(store.state.value.checking)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(BatchKind.LIST to "a\nb"), api.previews)
            assertEquals(
                2,
                store.state.value.preview
                    ?.count,
            )
            assertFalse(store.state.value.checking)
            assertTrue(store.state.value.canAdd)
        }

    @Test
    fun `lines pasted into the add box are previewed straight away`() =
        runTest {
            val store = store("a\nb\nc")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()
            assertEquals(
                3,
                store.state.value.preview
                    ?.count,
            )
        }

    @Test
    fun `an answer for text that has since changed is dropped`() =
        runTest {
            val store = store()
            api.gate = CompletableDeferred()
            store.setText("old")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()
            // The request for "old" is out; the text moves on before it answers.
            api.gate = null
            store.setText("new\nnewer")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()
            assertEquals(
                2,
                store.state.value.preview
                    ?.count,
            )
        }

    @Test
    fun `a refused preview shows the API's reason and blocks adding`() =
        runTest {
            val store = store()
            store.setKind(BatchKind.PATTERN)
            api.previewFails = ApiException("The pattern names 1,200 links; the most is 1,000", 400, null)
            store.setText("f[1-1200]")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()
            assertEquals(BatchKind.PATTERN to "f[1-1200]", api.previews.single())
            assertEquals("The pattern names 1,200 links; the most is 1,000", store.state.value.previewError)
            assertNull(store.state.value.preview)
            assertFalse(store.state.value.canAdd)
        }

    @Test
    fun `clearing the text clears the preview without asking`() =
        runTest {
            val store = store("a\nb")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()
            store.setText("  ")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()
            assertEquals(1, api.previews.size)
            assertNull(store.state.value.preview)
            assertFalse(store.state.value.checking)
        }

    @Test
    fun `each tab keeps its own text, and switching previews the other`() =
        runTest {
            val store = store("a\nb")
            store.setKind(BatchKind.PATTERN)
            assertEquals("", store.state.value.text)
            store.setText("f[1-2]")
            store.setKind(BatchKind.LIST)
            assertEquals("a\nb", store.state.value.text)
            assertEquals("f[1-2]", store.state.value.pattern)
        }

    @Test
    fun `adding answers for every link and asks for the counts again`() =
        runTest {
            val store = store("a\nb\nc")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()

            assertTrue(store.add("720"))

            assertEquals(listOf(Triple(BatchKind.LIST, "a\nb\nc", "720")), api.adds)
            val results = store.state.value.results!!
            assertEquals(listOf(BatchOutcome.ADDED, BatchOutcome.DUPLICATE, BatchOutcome.ERROR), results.map { it.outcome })
            assertEquals("d0", results[1].downloadId)
            assertEquals("Video unavailable", results[2].message)
            assertEquals(1, added)

            store.startOver()
            assertNull(store.state.value.results)
            assertEquals("a\nb\nc", store.state.value.text)
        }

    @Test
    fun `nothing is added before a good preview`() =
        runTest {
            val store = store()
            store.setText("a")
            assertFalse(store.add("best"))
            assertTrue(api.adds.isEmpty())
        }

    @Test
    fun `a failed add keeps the form and says why`() =
        runTest {
            val store = store("a\nb")
            advanceTimeBy(BATCH_PREVIEW_DELAY_MS + 1)
            runCurrent()
            api.addFails = ApiException("Could not reach the API", null, null)

            assertFalse(store.add("best"))

            assertEquals("Could not reach the API", store.state.value.addError)
            assertNull(store.state.value.results)
            assertFalse(store.state.value.adding)
            assertEquals(0, added)
        }
}
