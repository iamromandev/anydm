package dev.anydm.engine.store

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test

/** What every [SettingsStore] must do, each test on a new database from [newDriver]. Driver tests extend this. */
abstract class SettingsStoreContract : StoreTestBase() {
    // Absolute on every OS.
    private val folder = Path(SystemTemporaryDirectory, "anydm downloads")

    private fun rows(): List<String?> = driver.texts("SELECT name || '=' || data FROM setting ORDER BY name")

    @Test
    fun defaultsWhenNothingIsStored() =
        runTest {
            val settings = newStore().settings.load()

            settings.concurrencyLimit shouldBe 3
            settings.defaultSaveFolder shouldBe null
            settings.globalPause shouldBe false
        }

    @Test
    fun updatesAreStoredAndReturned() =
        runTest {
            val store = newStore().settings

            val updated = store.update { it.copy(concurrencyLimit = 5, globalPause = true, defaultSaveFolder = folder) }

            updated shouldBe Settings(concurrencyLimit = 5, defaultSaveFolder = folder, globalPause = true)
            store.load() shouldBe updated
        }

    @Test
    fun onlySettingsThatDifferFromTheirDefaultTakeARow() =
        runTest {
            val store = newStore().settings

            store.update { it.copy(concurrencyLimit = 5, globalPause = true, defaultSaveFolder = folder) }
            rows() shouldBe
                listOf(
                    "files.default_save_folder=$folder",
                    "queue.concurrency_limit=5",
                    "queue.global_pause=true",
                )

            store.update { Settings() }
            driver.count("setting") shouldBe 0
        }

    @Test
    fun maxRetriesIsStoredOnlyWhenNotTheDefault() =
        runTest {
            val store = newStore().settings
            store.load().maxRetries shouldBe 5

            store.update { it.copy(maxRetries = 2) }
            store.load().maxRetries shouldBe 2
            rows() shouldBe listOf("retry.max_retries=2")

            store.update { it.copy(maxRetries = 5) }
            driver.count("setting") shouldBe 0
        }

    @Test
    fun unknownSettingsAreKept() =
        runTest {
            val store = newStore().settings
            driver.exec("INSERT INTO setting(name, data) VALUES ('later.story', 'x')")

            store.update { it.copy(globalPause = true) }
            store.update { Settings() }

            rows() shouldBe listOf("later.story=x")
        }

    @Test
    fun unreadableValuesFallBackToTheirDefault() =
        runTest {
            val store = newStore().settings
            val unreadable =
                listOf(
                    "queue.concurrency_limit" to "zero",
                    "queue.concurrency_limit" to "0",
                    "queue.global_pause" to "yes",
                    "files.default_save_folder" to "relative/dir",
                    "retry.max_retries" to "x",
                    "retry.max_retries" to "-1",
                )
            for ((name, data) in unreadable) {
                driver.exec("INSERT OR REPLACE INTO setting(name, data) VALUES ('$name', '$data')")

                store.load() shouldBe Settings()
                driver.exec("DELETE FROM setting WHERE name = '$name'")
            }
        }

    // A caller cancelled while its write waits for the database still gets the write made.
    @Test
    fun aCancelledUpdateStillHappens() =
        runTest {
            val store = newStore().settings

            val call = launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(globalPause = true) } }
            call.cancel()
            testScheduler.advanceUntilIdle()
            call.join()

            store.load().globalPause shouldBe true
        }

    @Test
    fun aFailedUpdateChangesNothing() =
        runTest {
            val store = newStore().settings
            store.update { it.copy(globalPause = true) }

            shouldThrow<IllegalStateException> { store.update { error("boom") } }

            rows() shouldBe listOf("queue.global_pause=true")
        }
}
