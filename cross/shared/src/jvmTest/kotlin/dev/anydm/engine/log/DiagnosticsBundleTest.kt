package dev.anydm.engine.log

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.toNioPath
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.exec
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

class DiagnosticsBundleTest {
    private val root = newTempFolder("anydm-diag-test-")
    private val logs = Path(root, "logs").also { Files.createDirectories(it.toNioPath()) }
    private val target = Path(root, "diag.zip")
    private val app = AppInfo("anydm", "1.2.3", "macOS 15", "JVM 21")
    private val clock =
        object : Clock {
            override fun now() = Instant.parse("2026-10-17T12:34:56.789Z")
        }
    private val stores = mutableListOf<Store>()

    @AfterTest
    fun cleanUp() {
        stores.forEach { it.close() }
        deleteRecursively(root)
    }

    private fun writeLog(
        name: String,
        text: String,
    ) {
        Files.writeString(Path(logs, name).toNioPath(), text)
    }

    private fun TestScope.bundle(prepare: (app.cash.sqldelight.db.SqlDriver) -> Unit = {}): DiagnosticsBundle {
        val driver = openInMemoryDatabase()
        val store = Store(driver, StandardTestDispatcher(testScheduler)).also { stores += it }
        prepare(driver)
        return DiagnosticsBundle(store.settings, JvmDiagnosticsPlatform(logs), clock)
    }

    private fun entries(): Map<String, String> =
        ZipFile(target.toNioPath().toFile()).use { zip ->
            zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes().decodeToString() }
        }

    @Test
    fun theBundleHasLogsVersionsAndSettings() =
        runTest {
            writeLog("anydm.log", "newest\n")
            writeLog("anydm.1.log", "older\n")

            bundle().export(target, app) shouldBe FileResult.Ok(Unit)

            val zip = entries()
            zip.keys shouldBe setOf("logs/anydm.log", "logs/anydm.1.log", "versions.txt", "settings.txt")
            zip.getValue("logs/anydm.log") shouldBe "newest\n"
            val versions = zip.getValue("versions.txt")
            versions shouldContain "anydm 1.2.3"
            versions shouldContain "macOS 15"
            versions shouldContain "JVM 21"
            versions shouldContain "made 2026-10-17T12:34:56.789Z"
            versions shouldContain "no external tools yet"
            zip.getValue("settings.txt").lines().filter { it.isNotEmpty() } shouldBe
                listOf(
                    "concurrencyLimit = 3",
                    "defaultSaveFolder =",
                    "globalPause = false",
                    "maxRetries = 5",
                    "debugLogging = false",
                )
        }

    @Test
    fun toolsAreListed() =
        runTest {
            bundle().export(target, app, listOf(ToolVersion("ffmpeg", null, null), ToolVersion("yt-dlp", "2026.10.01", "/usr/bin/yt-dlp")))

            val lines = entries().getValue("versions.txt").lines()
            lines shouldContain "tool ffmpeg not found"
            lines shouldContain "tool yt-dlp 2026.10.01 /usr/bin/yt-dlp"
        }

    @Test
    fun aSecretInALogFileIsMaskedInTheBundle() =
        runTest {
            writeLog("anydm.log", "Cookie: a=1\n")

            bundle().export(target, app)

            entries().getValue("logs/anydm.log") shouldBe "Cookie: ***\n"
        }

    @Test
    fun anUnknownSettingIsLeftOut() =
        runTest {
            bundle { it.exec("INSERT INTO setting(name, data) VALUES ('proxy.password', 'hunter2')") }.export(target, app)

            entries().values.forEach { it shouldNotContain "hunter2" }
            entries().getValue("settings.txt") shouldNotContain "proxy"
        }

    @Test
    fun aFailedExportLeavesNothing() =
        runTest {
            val locked = Path(root, "locked").also { Files.createDirectories(it.toNioPath()) }
            locked.toNioPath().toFile().setWritable(false)
            try {
                val result = bundle().export(Path(locked, "d.zip"), app)

                result.shouldBeInstanceOf<FileResult.Failed>()
                Files.list(locked.toNioPath()).use { it.count() } shouldBe 0L
            } finally {
                locked.toNioPath().toFile().setWritable(true)
            }
        }

    @Test
    fun exportToAMissingFolderFails() =
        runTest {
            val missing = Path(root, "nowhere", "d.zip")

            bundle().export(missing, app).shouldBeInstanceOf<FileResult.Failed>()

            Files.exists(Path(root, "nowhere").toNioPath()) shouldBe false
        }
}
