package dev.anydm.engine.core

import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.namesIn
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.readBytes
import dev.anydm.engine.platform.toNioPath
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.fakeContent
import dev.anydm.engine.store.InMemoryPlanStore
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test

/** The runner on the real file system and the real SQL store, which the fakes of StepRunnerTest stand in for. */
class StepRunnerDesktopTest {
    private val root = newTempFolder("anydm-steps-")

    @AfterTest
    fun deleteRoot() = deleteRecursively(root)

    @Test
    fun preflightFailsOnAReadOnlyFolder() {
        val locked = Path(root, "locked").also { SystemFileSystem.createDirectories(it) }
        val nio = locked.toNioPath()
        assumeTrue("needs POSIX permissions", Files.getFileStore(nio).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeFalse("permissions don't bind this user", Files.isWritable(nio))
            runTest {
                val protocol = FakeProtocol()
                val runner = StepRunner(DesktopFileSystem(), InMemoryPlanStore(), mapOf(DownloadKind.DIRECT to protocol), root)
                val failed =
                    runner
                        .prepare(aPendingDownload(saveFolder = locked.toString()), aMirror())
                        .shouldBeInstanceOf<PrepareOutcome.Failed>()
                failed.failure.step shouldBe Step.PREFLIGHT
                failed.failure.errorClass shouldBe null
                failed.failure.message shouldContain locked.toString()
                failed.download.status shouldBe DownloadStatus.PENDING
                protocol.calls.shouldBeEmpty()
            }
        } finally {
            Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun aDownloadLandsOnDiskThroughTheRealStoreAndFileSystem() =
        runTest {
            Store(openInMemoryDatabase(), StandardTestDispatcher(testScheduler)).use { store ->
                val downloads = store.downloads
                val added =
                    downloads.add(
                        NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/file.zip"), saveFolder = root.toString()),
                    )
                val mirror = downloads.mirrors(added.id).single()
                val listener =
                    object : RunListener {
                        // As the queue (#552) will: merge only what the runner owns onto its own row, never the snapshot.
                        override suspend fun moved(download: Download) {
                            val row = downloads.get(download.id).shouldNotBeNull()
                            downloads.update(
                                row.copy(
                                    status = download.status,
                                    saveFolder = download.saveFolder,
                                    fileName = download.fileName,
                                    size = download.size,
                                ),
                            ) shouldBe true
                        }
                    }
                val runner =
                    StepRunner(DesktopFileSystem(), downloads, mapOf(DownloadKind.DIRECT to FakeProtocol()), root, freeSpaceReserve = 0)

                val ready = runner.prepare(added, mirror, listener).shouldBeInstanceOf<PrepareOutcome.Ready>()
                runner.run(ready, listener).shouldBeInstanceOf<RunOutcome.Finished>()

                downloads.get(added.id)?.status shouldBe DownloadStatus.COMPLETED
                readBytes(Path(root, "file.zip")) shouldBe fakeContent(0, 0, 65536)
                namesIn(root) shouldBe setOf("file.zip") // no partial left
                downloads.get(added.id)?.fileName shouldBe "file.zip"
                downloads.get(added.id)?.size shouldBe 65536L
                val segments = downloads.files(added.id).flatMap { downloads.segments(it.id) }
                (segments.isNotEmpty() && segments.all { it.isComplete }) shouldBe true
            }
        }
}
