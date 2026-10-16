package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.SegmentId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The part of DownloadStoreContract the step runner depends on, checked against the fake so it can't drift from the SQL store. */
class InMemoryPlanStoreTest {
    private val id = DownloadId(1)

    private fun newStore() = InMemoryPlanStore().also { it.register(id) }

    @Test
    fun filesAndSegmentsComeBackInOrderAndIdsAreNeverReused() =
        runTest {
            val store = newStore()
            val first = store.replaceFiles(id, listOf(NewFile(1, "b.mp4"), NewFile(0, "a.mp4", size = 1_000)))
            first.map { it.index } shouldBe listOf(0, 1)
            val segments = store.replaceSegments(first[0].id, listOf(NewSegment(500, 1_000), NewSegment(0, 500)))
            segments.map { it.start } shouldBe listOf(0L, 500L)
            store.segments(first[0].id) shouldBe segments

            val second = store.replaceFiles(id, listOf(NewFile(0, "c.mp4")))
            store.segments(first[0].id) shouldBe emptyList()
            (second.map { it.id } + first.map { it.id }).toSet().size shouldBe 3
            shouldThrow<IllegalArgumentException> { store.replaceFiles(id, listOf(NewFile(0, "x"), NewFile(0, "y"))) }
            store.files(id) shouldBe second
        }

    @Test
    fun aParentThatDoesntExistIsABug() =
        runTest {
            val store = newStore()
            shouldThrow<IllegalStateException> { store.replaceFiles(DownloadId(999), emptyList()) }.message shouldContain
                "download 999 does not exist"
            shouldThrow<IllegalStateException> { store.replaceSegments(FileId(999), emptyList()) }.message shouldContain
                "file 999 does not exist"
        }

    @Test
    fun watermarksAreSavedAllOrNothing() =
        runTest {
            val store = newStore()
            val file = store.replaceFiles(id, listOf(NewFile(0, "a.mp4", size = 1_000))).single()
            val (s0, s1) = store.replaceSegments(file.id, listOf(NewSegment(0, 500), NewSegment(500, 1_000)))

            // A segment that has since been removed is skipped.
            store.saveWatermarks(mapOf(s0.id to 250L, s1.id to 1_000L, SegmentId(999) to 5L))
            store.segments(file.id).map { it.watermark } shouldBe listOf(250L, 1_000L)

            shouldThrow<IllegalArgumentException> { store.saveWatermarks(mapOf(s0.id to 300L, s1.id to 1_001L)) }
            store.segments(file.id).map { it.watermark } shouldBe listOf(250L, 1_000L)
        }
}
