package dev.anydm.engine.core

import dev.anydm.engine.files.FilesPlacement
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.InMemoryPlanStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class StepRunnerPlacementTest {
    private suspend fun prepared(
        download: Download,
        probeName: String,
        placement: Placement,
    ): Download {
        val store = InMemoryPlanStore().also { it.register(download.id) }
        val protocol = FakeProtocol(files = listOf(FakeFile(probeName, 64L * 1024)), name = probeName)
        val runner =
            StepRunner(
                FakeFileSystem(),
                store,
                mapOf(DownloadKind.DIRECT to protocol),
                stepRoot,
                freeSpaceReserve = 0,
                placement = placement,
            )
        return runner
            .prepare(download, aMirror(download.id))
            .shouldBeInstanceOf<PrepareOutcome.Ready>()
            .download
    }

    @Test
    fun prepareCleansTheProbeName() =
        runTest {
            val placement = FilesPlacement(NameCleaner(OsProfile.WINDOWS))
            prepared(aPendingDownload(), "a:b?.txt", placement).fileName shouldBe "a_b_.txt"
        }

    @Test
    fun theUsersNameIsCleanedToo() =
        runTest {
            val placement = FilesPlacement(NameCleaner(OsProfile.WINDOWS))
            prepared(aPendingDownload(fileName = "CON"), "file.zip", placement).fileName shouldBe "_CON"
        }

    @Test
    fun noPlacementKeepsTheProbeNameAsIs() =
        runTest {
            prepared(aPendingDownload(), "a:b?.txt", Placement.None).fileName shouldBe "a:b?.txt"
        }
}
