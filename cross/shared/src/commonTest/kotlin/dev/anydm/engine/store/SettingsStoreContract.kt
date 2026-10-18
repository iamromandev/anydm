package dev.anydm.engine.store

import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.virtualClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
                    "http.connections" to "0",
                    "http.connections" to "17",
                    "http.connections" to "x",
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

    @Test
    fun anUnreadableSettingIsLogged() =
        runTest {
            val sink = RecordingLogSink()
            val raw = newDriver()
            val store = Store(raw, StandardTestDispatcher(testScheduler), Log(sink, virtualClock()))
            raw.exec("INSERT INTO setting(name, data) VALUES ('queue.concurrency_limit', 'x')")

            store.settings.load().concurrencyLimit shouldBe 3

            sink.lines.single() shouldContain "WARN  store setting queue.concurrency_limit unreadable, using the default"
            raw.close()
        }

    @Test
    fun debugLoggingRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().debugLogging shouldBe false

            settings.update { it.copy(debugLogging = true) }

            settings.load().debugLogging shouldBe true
            driver.longs("SELECT count(*) FROM setting WHERE name = 'log.debug'") shouldBe listOf(1L)
        }

    @Test
    fun connectionsRoundTrip() =
        runTest {
            val settings = newStore().settings
            settings.load().connectionsPerDownload shouldBe 4
            rows().none { it?.startsWith("http.connections=") == true } shouldBe true

            settings.update { it.copy(connectionsPerDownload = 8) }

            settings.load().connectionsPerDownload shouldBe 8
            rows() shouldContain "http.connections=8"
        }

    @Test
    fun globalSpeedLimitRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().globalSpeedLimit shouldBe null

            settings.update { it.copy(globalSpeedLimit = 512_000) }
            settings.load().globalSpeedLimit shouldBe 512_000L
            rows() shouldContain "net.global_speed_limit=512000"

            settings.update { it.copy(globalSpeedLimit = null) }
            settings.load().globalSpeedLimit shouldBe null
            rows().none { it?.startsWith("net.global_speed_limit=") == true } shouldBe true
            shouldThrow<IllegalArgumentException> { Settings(globalSpeedLimit = 0) }
        }

    @Test
    fun aConnectionCountOutsideTheRangeIsRefused() {
        shouldThrow<IllegalArgumentException> { Settings(connectionsPerDownload = 0) }
        shouldThrow<IllegalArgumentException> { Settings(connectionsPerDownload = 17) }
    }

    @Test
    fun clashPolicyRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().clashPolicy shouldBe ClashPolicy.RENAME
            rows().none { it?.startsWith("files.clash_policy=") == true } shouldBe true

            settings.update { it.copy(clashPolicy = ClashPolicy.SKIP) }

            settings.load().clashPolicy shouldBe ClashPolicy.SKIP
            rows() shouldContain "files.clash_policy=SKIP"
        }
}
