package dev.anydm.engine.store

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.PacingValues
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.tools.YtDlpChannel
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
    fun playlistFileRoundTrips() =
        runTest {
            val settings = newStore().settings

            settings.load().playlistFile shouldBe true
            settings.update { it.copy(playlistFile = false) }.playlistFile shouldBe false
            settings.load().playlistFile shouldBe false
            settings.update { it.copy(playlistFile = true) }
            settings.load() shouldBe Settings()
            rows() shouldBe emptyList()
        }

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
    fun seedRatioRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().seedRatio shouldBe null

            settings.update { it.copy(seedRatio = 1.5) }
            settings.load().seedRatio shouldBe 1.5
            rows() shouldContain "torrent.seed_ratio=1.5"

            settings.update { it.copy(seedRatio = null) }
            settings.load().seedRatio shouldBe null
            rows().none { it?.startsWith("torrent.seed_ratio=") == true } shouldBe true
            shouldThrow<IllegalArgumentException> { Settings(seedRatio = 0.0) }
            shouldThrow<IllegalArgumentException> { Settings(seedRatio = -1.0) }
        }

    @Test
    fun seedMinutesRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().seedMinutes shouldBe null

            settings.update { it.copy(seedMinutes = 120) }
            settings.load().seedMinutes shouldBe 120
            rows() shouldContain "torrent.seed_minutes=120"

            settings.update { it.copy(seedMinutes = null) }
            settings.load().seedMinutes shouldBe null
            rows().none { it?.startsWith("torrent.seed_minutes=") == true } shouldBe true
            shouldThrow<IllegalArgumentException> { Settings(seedMinutes = 0) }
        }

    @Test
    fun slowModeRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().slowMode shouldBe false

            settings.update { it.copy(slowMode = true) }
            settings.load().slowMode shouldBe true
            rows() shouldContain "net.slow_mode=true"

            settings.update { it.copy(slowMode = false) }
            settings.load().slowMode shouldBe false
            rows().none { it?.startsWith("net.slow_mode=") == true } shouldBe true
        }

    @Test
    fun slowModeLimitRoundTripsAndDefaultsTo1MiB() =
        runTest {
            val settings = newStore().settings
            settings.load().slowModeLimit shouldBe 1_048_576L

            settings.update { it.copy(slowModeLimit = 2048) }
            settings.load().slowModeLimit shouldBe 2048L
            rows() shouldContain "net.slow_mode_limit=2048"

            settings.update { it.copy(slowModeLimit = 1_048_576) }
            settings.load().slowModeLimit shouldBe 1_048_576L
            rows().none { it?.startsWith("net.slow_mode_limit=") == true } shouldBe true
        }

    @Test
    fun anUnreadableSlowModeLimitFallsBack() =
        runTest {
            val settings = newStore().settings
            for (bad in listOf("0", "x", "-5")) {
                driver.exec("INSERT OR REPLACE INTO setting(name, data) VALUES ('net.slow_mode_limit', '$bad')")
                settings.load().slowModeLimit shouldBe 1_048_576L
            }
        }

    @Test
    fun aSlowModeLimitBelowOneIsRefused() {
        shouldThrow<IllegalArgumentException> { Settings(slowModeLimit = 0) }
        shouldThrow<IllegalArgumentException> { Settings(slowModeLimit = -1) }
    }

    @Test
    fun globalUploadLimitRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().globalUploadLimit shouldBe null

            settings.update { it.copy(globalUploadLimit = 256_000) }
            settings.load().globalUploadLimit shouldBe 256_000L
            rows() shouldContain "net.global_upload_limit=256000"

            settings.update { it.copy(globalUploadLimit = null) }
            settings.load().globalUploadLimit shouldBe null
            rows().none { it?.startsWith("net.global_upload_limit=") == true } shouldBe true
            shouldThrow<IllegalArgumentException> { Settings(globalUploadLimit = 0) }
        }

    @Test
    fun anUnreadableSeedSettingFallsBack() =
        runTest {
            val store = newStore().settings
            for ((name, data) in listOf(
                "torrent.seed_ratio" to "abc",
                "torrent.seed_ratio" to "0",
                "torrent.seed_ratio" to "-2",
                "torrent.seed_minutes" to "0",
                "torrent.seed_minutes" to "1.5",
                "net.global_upload_limit" to "-5",
                "net.global_upload_limit" to "x",
            )) {
                driver.exec("INSERT OR REPLACE INTO setting(name, data) VALUES ('$name', '$data')")

                store.load() shouldBe Settings()
                driver.exec("DELETE FROM setting WHERE name = '$name'")
            }
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

    @Test
    fun freeSpaceReserveRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().freeSpaceReserve shouldBe 64L * 1024 * 1024
            rows().none { it?.startsWith("files.free_space_reserve=") == true } shouldBe true

            settings.update { it.copy(freeSpaceReserve = 1_000) }

            settings.load().freeSpaceReserve shouldBe 1_000L
            rows() shouldContain "files.free_space_reserve=1000"
            shouldThrow<IllegalArgumentException> { Settings(freeSpaceReserve = -1) }
        }

    @Test
    fun historyLimitRoundTrips() =
        runTest {
            val settings = newStore().settings
            settings.load().historyLimit shouldBe 1000
            rows().none { it?.startsWith("files.history_limit=") == true } shouldBe true

            settings.update { it.copy(historyLimit = 50) }

            settings.load().historyLimit shouldBe 50
            rows() shouldContain "files.history_limit=50"
            shouldThrow<IllegalArgumentException> { Settings(historyLimit = 0) }
        }

    @Test
    fun toolSettingsRoundTrip() =
        runTest {
            val settings = newStore().settings
            settings.load().ytDlpChannel shouldBe YtDlpChannel.STABLE
            settings.load().systemTools shouldBe true
            rows().none { it?.startsWith("tools.") == true } shouldBe true

            settings.update { it.copy(ytDlpChannel = YtDlpChannel.NIGHTLY, systemTools = false) }

            settings.load().ytDlpChannel shouldBe YtDlpChannel.NIGHTLY
            settings.load().systemTools shouldBe false
            rows() shouldContain "tools.yt_dlp_channel=NIGHTLY"
            rows() shouldContain "tools.system_tools=false"
        }

    @Test
    fun mediaSettingsRoundTrip() =
        runTest {
            val settings = newStore().settings
            settings.load().cookieSource shouldBe CookieSource.None
            settings.load().pacing shouldBe Pacing()
            rows().none { it?.startsWith("media.") == true } shouldBe true

            settings.update {
                it.copy(
                    cookieSource = CookieSource.Browser("firefox", "default"),
                    pacing = Pacing(PacingPreset.CUSTOM, PacingValues(2.0, 4.0, 6.0)),
                )
            }

            settings.load().cookieSource shouldBe CookieSource.Browser("firefox", "default")
            settings.load().pacing shouldBe Pacing(PacingPreset.CUSTOM, PacingValues(2.0, 4.0, 6.0))
            rows() shouldContain "media.cookie_source=browser:firefox:default"
            rows() shouldContain "media.pacing=CUSTOM"
            rows() shouldContain "media.pacing_request_sleep=2.0"
            rows() shouldContain "media.pacing_interval_min=4.0"
            rows() shouldContain "media.pacing_interval_max=6.0"

            settings.update { it.copy(pacing = Pacing(PacingPreset.BALANCED)) }
            rows() shouldContain "media.pacing=BALANCED"
            rows().none { it?.startsWith("media.pacing_") == true } shouldBe true

            settings.update { it.copy(cookieSource = CookieSource.None, pacing = Pacing()) }
            rows().none { it?.startsWith("media.") == true } shouldBe true
        }

    @Test
    fun anUnreadableMediaSettingFallsBack() =
        runTest {
            val settings = newStore().settings
            driver.exec("INSERT INTO setting(name, data) VALUES ('media.cookie_source', 'browser:netscape')")
            driver.exec("INSERT INTO setting(name, data) VALUES ('media.pacing', 'CUSTOM')")
            driver.exec("INSERT INTO setting(name, data) VALUES ('media.pacing_request_sleep', 'abc')")

            settings.load().cookieSource shouldBe CookieSource.None
            settings.load().pacing shouldBe Pacing()
        }

    @Test
    fun torrentPortRoundTrips() =
        runTest {
            val settings = newStore().settings

            settings.load().torrentPort shouldBe 6881
            settings.update { it.copy(torrentPort = 51413) }.torrentPort shouldBe 51413
            settings.load().torrentPort shouldBe 51413
            settings.update { it.copy(torrentPort = 0) }
            settings.load().torrentPort shouldBe 0
            settings.update { it.copy(torrentPort = 6881) }
            rows() shouldBe emptyList()
        }

    @Test
    fun aTorrentPortOutOfRangeIsRefused() {
        shouldThrow<IllegalArgumentException> { Settings(torrentPort = 70000) }
        shouldThrow<IllegalArgumentException> { Settings(torrentPort = -1) }
    }

    @Test
    fun anUnreadableTorrentPortFallsBack() =
        runTest {
            val store = newStore()
            driver.exec("INSERT INTO setting(name, data) VALUES ('torrent.port', 'abc')")

            store.settings.load().torrentPort shouldBe 6881
        }
}
