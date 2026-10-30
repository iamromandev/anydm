package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.files.CategoryResult
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.NewCategory
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.BackupFile
import dev.anydm.engine.store.CategoryEntry
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.ImportResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.ProfileEntry
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.store.encode
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The transfer and file settings (#608), changed through the engine and in effect at once. */
abstract class EngineSettingsContract : StoreTestBase() {
    private fun TestScope.advance(seconds: Int) {
        advanceTimeBy(seconds.seconds)
        runCurrent()
    }

    private suspend fun TestScope.withEngine(block: suspend (EngineHarness) -> Unit) {
        val h = EngineHarness(this, newStore()).also { it.engine.start() }
        try {
            block(h)
        } finally {
            h.engine.stop()
        }
    }

    @Test
    fun appliedSettingsAreStoredAndReadBack() =
        runTest {
            withEngine { h ->
                h.store.settings.update { it.copy(proxy = ProxyChoice.Direct, clipboardWatch = true) }
                val next =
                    TransferSettings(
                        defaultSaveFolder = "/dl",
                        clashPolicy = ClashPolicy.SKIP,
                        historyLimit = 50,
                        connectionsPerDownload = 8,
                        globalSpeedLimit = 1_000_000,
                        slowMode = true,
                        seedRatio = 2.0,
                        keepAwake = false,
                    )

                h.engine.applyTransferSettings(next).shouldBeInstanceOf<CommandResult.Done>()

                h.engine.transferSettings() shouldBe next
                val stored = h.store.settings.load()
                stored.defaultSaveFolder shouldBe Path("/dl")
                // What this story doesn't own is left as it was.
                stored.proxy shouldBe ProxyChoice.Direct
                stored.clipboardWatch shouldBe true
            }
        }

    @Test
    fun aValueTheEngineRefusesChangesNothing() =
        runTest {
            withEngine { h ->
                h.engine
                    .applyTransferSettings(TransferSettings(clashPolicy = ClashPolicy.SKIP, connectionsPerDownload = 99))
                    .shouldBeInstanceOf<CommandResult.Invalid>()

                h.engine.transferSettings() shouldBe TransferSettings()
            }
        }

    @Test
    fun aLowerLimitHoldsBackARunningJobAtOnce() =
        runTest {
            withEngine { h ->
                val first = h.addJob()
                val second = h.addJob()
                advance(1)
                h.status(first) shouldBe DownloadStatus.DOWNLOADING
                h.status(second) shouldBe DownloadStatus.DOWNLOADING

                h.engine.applyTransferSettings(TransferSettings(concurrencyLimit = 1))
                runCurrent()

                listOf(h.status(first), h.status(second)).count { it == DownloadStatus.DOWNLOADING } shouldBe 1
            }
        }

    @Test
    fun noRetriesFailsAJobAtItsFirstError() =
        runTest {
            withEngine { h ->
                h.engine.applyTransferSettings(TransferSettings(maxRetries = 0))
                h.protocol.failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.TRANSIENT, "reset"))

                val id = h.addJob()
                advance(1)

                h.status(id) shouldBe DownloadStatus.FAILED
            }
        }

    @Test
    fun globalPauseOnAndOffGoesThroughTheQueue() =
        runTest {
            withEngine { h ->
                val id = h.addJob()
                advance(1)

                h.engine.applyTransferSettings(TransferSettings(globalPause = true))
                advance(1)
                h.status(id) shouldBe DownloadStatus.PAUSED

                h.engine.applyTransferSettings(TransferSettings(globalPause = false))
                advance(1)
                h.status(id) shouldBe DownloadStatus.DOWNLOADING
            }
        }

    @Test
    fun resetKeepsPauseAndTheAfterQueueAction() =
        runTest {
            withEngine { h ->
                h.engine.applyTransferSettings(
                    TransferSettings(
                        clashPolicy = ClashPolicy.OVERWRITE,
                        concurrencyLimit = 1,
                        globalPause = true,
                        afterQueue = AfterQueueAction.SLEEP,
                    ),
                )

                h.engine.resetTransferSettings().shouldBeInstanceOf<CommandResult.Done>()

                h.engine.transferSettings() shouldBe
                    TransferSettings(globalPause = true, afterQueue = AfterQueueAction.SLEEP)
            }
        }

    private suspend fun EngineHarness.addWithoutFolder(name: String) =
        engine
            .add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/$name"), fileName = name))
            .shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id

    @Test
    fun aDownloadWithNoFolderGoesToTheSavedDefaultFolder() =
        runTest {
            withEngine { h ->
                h.fs.prepareFolder(Path("/dl"))
                h.engine.applyTransferSettings(TransferSettings(defaultSaveFolder = "/dl"))

                val id = h.addWithoutFolder("a.zip")
                advance(10)

                h.status(id) shouldBe DownloadStatus.COMPLETED
                h.fs.exists(Path("/dl/a.zip")).shouldBeTrue()
            }
        }

    @Test
    fun aSavedDefaultFolderThatIsGoneFallsBackToTheDownloadsFolder() =
        runTest {
            withEngine { h ->
                h.engine.applyTransferSettings(TransferSettings(defaultSaveFolder = "/unplugged/dl"))

                val id = h.addWithoutFolder("b.zip")
                advance(10)

                h.status(id) shouldBe DownloadStatus.COMPLETED
                h.fs.exists(Path(stepRoot, "b.zip")).shouldBeTrue()
            }
        }

    @Test
    fun categoriesAreAddedEditedAndDeletedWithUniqueNames() =
        runTest {
            withEngine { h ->
                val videos =
                    h.engine
                        .addCategory(NewCategory("Videos", "/v", "{title}", listOf(CategoryRule.Extension(setOf("mkv"))), 0))
                        .shouldBeInstanceOf<CategoryResult.Saved>()
                        .category
                h.engine.addCategory(NewCategory("videos", "/v2", null, emptyList(), 1)) shouldBe CategoryResult.NameTaken

                val music =
                    h.engine
                        .addCategory(NewCategory("Music", "/m", null, emptyList(), 1))
                        .shouldBeInstanceOf<CategoryResult.Saved>()
                        .category
                h.engine.updateCategory(music.copy(name = "VIDEOS")) shouldBe CategoryResult.NameTaken
                h.engine.updateCategory(music.copy(folder = "/music")).shouldBeInstanceOf<CategoryResult.Saved>()
                h.engine.categories().map { it.folder } shouldBe listOf("/v", "/music")

                h.engine.deleteCategory(videos.id)
                h.engine.categories().map { it.name } shouldBe listOf("Music")
                h.engine.updateCategory(videos) shouldBe CategoryResult.NotFound
            }
        }

    @Test
    fun historyIsListedAndCleared() =
        runTest {
            withEngine { h ->
                h.store.history.add(HistoryEntry(DownloadId(1), "a.zip", "/dl/a.zip", 10, Instant.fromEpochMilliseconds(1), "https://h/a"))
                h.store.history.add(HistoryEntry(DownloadId(2), "b.zip", "/dl/b.zip", 10, Instant.fromEpochMilliseconds(2), "https://h/b"))

                h.engine.history(10).map { it.name } shouldBe listOf("b.zip", "a.zip")
                h.engine.clearHistory()
                h.engine.history(10) shouldBe emptyList()
            }
        }

    @Test
    fun anExportImportedAfterAResetBringsEverythingBack() =
        runTest {
            withEngine { h ->
                val videos =
                    h.engine
                        .addCategory(NewCategory("Videos", "/v", "{title}", emptyList(), 0))
                        .shouldBeInstanceOf<CategoryResult.Saved>()
                        .category
                val profile =
                    h.engine
                        .addProfile(NewProfile("Clips", categoryId = videos.id, speedLimit = 5000, proxy = ProxyChoice.Direct))
                        .shouldBeInstanceOf<ProfileResult.Saved>()
                        .profile
                h.engine.setDefaultProfile(profile.id)
                h.engine.applyTransferSettings(TransferSettings(clashPolicy = ClashPolicy.SKIP, historyLimit = 20))
                val text = h.engine.exportSettings()

                h.engine.resetTransferSettings()
                h.engine.updateCategory(videos.copy(folder = "/elsewhere"))
                h.engine.setDefaultProfile(null)
                h.engine.importSettings(text) shouldBe ImportResult.Applied

                h.engine.transferSettings() shouldBe TransferSettings(clashPolicy = ClashPolicy.SKIP, historyLimit = 20)
                h.engine
                    .categories()
                    .single()
                    .folder shouldBe "/v"
                val back = h.engine.profiles().single()
                back.categoryId shouldBe videos.id
                // The proxy is not in the file, so the profile keeps its own.
                back.proxy shouldBe ProxyChoice.Direct
                h.store.settings
                    .load()
                    .defaultProfileId shouldBe profile.id
            }
        }

    @Test
    fun aRefusedImportChangesNothing() =
        runTest {
            withEngine { h ->
                val text = h.engine.exportSettings().replace("\"historyLimit\": 1000", "\"historyLimit\": 0")
                h.engine.importSettings(text).shouldBeInstanceOf<ImportResult.Refused>()
                h.engine.transferSettings() shouldBe TransferSettings()
            }
        }

    @Test
    fun anImportLeavesPauseTheAfterQueueActionAndTheDefaultProfileAsTheyAre() =
        runTest {
            withEngine { h ->
                val text = h.engine.exportSettings()
                val clips =
                    h.engine
                        .addProfile(NewProfile("Clips"))
                        .shouldBeInstanceOf<ProfileResult.Saved>()
                        .profile
                h.engine.setDefaultProfile(clips.id)
                h.engine.applyTransferSettings(TransferSettings(globalPause = true, afterQueue = AfterQueueAction.SHUT_DOWN))

                h.engine.importSettings(text) shouldBe ImportResult.Applied

                val stored = h.store.settings.load()
                stored.globalPause shouldBe true
                stored.afterQueue shouldBe AfterQueueAction.SHUT_DOWN
                stored.defaultProfileId shouldBe clips.id
            }
        }

    @Test
    fun categoryNamesAreStoredTrimmed() =
        runTest {
            withEngine { h ->
                h.engine
                    .addCategory(NewCategory("Video ", "/v", null, emptyList(), 0))
                    .shouldBeInstanceOf<CategoryResult.Saved>()
                    .category.name shouldBe "Video"
                h.engine
                    .categories()
                    .single()
                    .name shouldBe "Video"
            }
        }

    @Test
    fun importedCategoryNamesAreTrimmedAndMatchedAgain() =
        runTest {
            withEngine { h ->
                val text =
                    BackupFile(
                        categories = listOf(CategoryEntry(" Music ", "/m")),
                        profiles = listOf(ProfileEntry("Songs", category = " Music ")),
                    ).encode()

                h.engine.importSettings(text) shouldBe ImportResult.Applied
                h.engine.importSettings(text) shouldBe ImportResult.Applied

                val music = h.engine.categories().single()
                music.name shouldBe "Music"
                h.engine
                    .profiles()
                    .single()
                    .categoryId shouldBe music.id
            }
        }
}
