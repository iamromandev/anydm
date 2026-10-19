package dev.anydm.engine.core

import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.fakeContent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

/** A protocol that keeps its own files (a torrent's): the runner makes rows and nothing else (#541). */
class StepRunnerOwnedFilesTest {
    private val names = listOf("Album/1.flac", "Album/2.flac", "Album/cover.jpg")
    private val lengths = listOf(3_000L, 5_000L, 700L)
    private val id = DownloadId(1)

    private fun StepHarness.owned(
        unselected: Set<Int> = emptySet(),
        skip: Set<Int> = emptySet(),
    ) {
        protocol.files = names.zip(lengths) { name, length -> FakeFile(name, length) }
        protocol.name = "Album"
        protocol.ownsFiles = true
        protocol.unselected = unselected
        protocol.onOwnedFile = { part ->
            if (part.file.index !in skip) fs.put(part.target, fakeContent(part.file.index, 0, lengths[part.file.index].toInt()))
        }
    }

    private fun StepHarness.at(name: String) = Path(stepRoot, *name.split('/').toTypedArray())

    @Test
    fun noPartialIsMadeForAnOwnedFile() =
        runTest {
            val h = StepHarness()
            h.owned()

            h.runAll(aPendingDownload(kind = DownloadKind.TORRENT)).failure shouldBe null

            h.fs.paths().none { it.name.endsWith(".anydm") } shouldBe true
            h.fs.openWriters shouldBe 0
            h.journal.none { it.startsWith("preallocate") || it.startsWith("atomicMove") } shouldBe true
            names.forEachIndexed { i, name -> h.fs.read(h.at(name)) shouldBe fakeContent(i, 0, lengths[i].toInt()) }
        }

    @Test
    fun unselectedFilesKeepARowWithSelectedFalse() =
        runTest {
            val h = StepHarness()
            h.owned(unselected = setOf(1))

            h.runAll(aPendingDownload(kind = DownloadKind.TORRENT)).failure shouldBe null

            h.store.files(id).map { it.selected } shouldBe listOf(true, false, true)
            h.fs.exists(h.at(names[1])) shouldBe false
        }

    @Test
    fun theTotalCountsSelectedFilesOnly() =
        runTest {
            val h = StepHarness()
            h.owned(unselected = setOf(1))
            val ready = h.prepare(aPendingDownload(kind = DownloadKind.TORRENT)).shouldBeInstanceOf<PrepareOutcome.Ready>()

            ready.download.size shouldBe 3_700L
            h.runner.run(ready, h.listener).shouldBeInstanceOf<RunOutcome.Finished>()

            h.listener.reported.last() shouldBe (3_700L to 3_700L)
        }

    @Test
    fun outputsTheProtocolPlacedAreRecordedAsPlaced() =
        runTest {
            val h = StepHarness()
            h.owned(unselected = setOf(1))

            val done = h.runAll(aPendingDownload(kind = DownloadKind.TORRENT))

            done.failure shouldBe null
            done.download.status shouldBe DownloadStatus.COMPLETED
            h.store.files(id).map { it.placedPath } shouldBe listOf(names[0], null, names[2])
        }

    @Test
    fun aMissingOutputFailsPostProcess() =
        runTest {
            val h = StepHarness()
            h.owned(skip = setOf(0))

            val failure = h.runAll(aPendingDownload(kind = DownloadKind.TORRENT)).failure

            failure!!.step shouldBe Step.POST_PROCESS
        }

    @Test
    fun aChangedSelectionStartsTheRowsAgain() =
        runTest {
            val h = StepHarness()
            h.owned(unselected = setOf(1))
            h.protocol.failure = FakeFailure.AtTransfer(0, StepResult.Failed(null, "stopped"))
            h.runAll(aPendingDownload(kind = DownloadKind.TORRENT)).failure!!.step shouldBe Step.TRANSFER
            h.store.files(id).map { it.selected } shouldBe listOf(true, false, true)
            h.protocol.failure = null
            h.protocol.unselected = emptySet()

            h.runAll(aPendingDownload(kind = DownloadKind.TORRENT)).failure shouldBe null

            h.store.files(id).map { it.selected } shouldBe listOf(true, true, true)
        }

    private fun sha256(bytes: ByteArray) =
        Checksum(
            ChecksumAlgorithm.SHA256,
            hasherFor(ChecksumAlgorithm.SHA256)
                .also {
                    it.update(bytes)
                }.hex(),
        )

    @Test
    fun aSingleOutputChecksumHashesTheTarget() =
        runTest {
            for ((checksum, expected) in listOf(
                sha256(fakeContent(0, 0, 2_000)) to null,
                Checksum(ChecksumAlgorithm.SHA256, "0".repeat(64)) to ErrorClass.INTEGRITY,
            )) {
                val h = StepHarness()
                h.protocol.files = listOf(FakeFile("one.bin", 2_000))
                h.protocol.ownsFiles = true
                h.protocol.checksum = checksum
                h.protocol.onOwnedFile = { part -> h.fs.put(part.target, fakeContent(0, 0, 2_000)) }

                h.runAll(aPendingDownload(kind = DownloadKind.TORRENT)).failure?.errorClass shouldBe expected
            }
        }

    @Test
    fun aValidatorChangeLeavesTheOwnedFilesAlone() =
        runTest {
            val h = StepHarness()
            h.owned()
            val done = h.runAll(aPendingDownload(kind = DownloadKind.TORRENT)).download

            h.runner.startOver(done).shouldBeInstanceOf<StepResult.Ok<Unit>>()

            names.forEach { h.fs.exists(h.at(it)) shouldBe true }
            h.store.files(id) shouldBe emptyList()
        }
}
