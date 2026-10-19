package dev.anydm.engine.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.QualityPreset
import dev.anydm.engine.core.Segment
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.exists
import dev.anydm.engine.platform.isFolder
import dev.anydm.engine.platform.namesIn
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.toNioPath
import dev.anydm.engine.platform.writeText
import dev.anydm.engine.store.db.AnydmDb
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant

class DatabaseFileTest {
    private val root = newTempFolder("anydm-store-test-")
    private val file = Path(root, DATABASE_FILE)

    @AfterTest
    fun deleteRoot() = deleteRecursively(root)

    private fun TestScope.open(): Store = Store(openDatabaseFile(file), StandardTestDispatcher(testScheduler))

    // Everything the store keeps, read back through the repositories.
    private data class Snapshot(
        val downloads: List<Download>,
        val mirrors: List<Mirror>,
        val files: List<File>,
        val segments: List<Segment>,
        val attempts: List<Attempt>,
        val settings: Settings,
    )

    // The committed snapshot of version 1, as the build verifies it, with what a version 1 install holds.
    private fun createVersion1File() {
        val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
        Files.copy(Path(snapshots, "1.db").toNioPath(), file.toNioPath())
        rawDriver(file).use { raw -> V1_ROWS.forEach(raw::exec) }
    }

    @Test
    fun versionTwoUpgradesWithNoSourceValidator() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "2.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 2")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'PAUSED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, 'USER', 1700000000000)",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1)) shouldBe
                Download(
                    id = DownloadId(1),
                    kind = DownloadKind.DIRECT,
                    status = DownloadStatus.PAUSED,
                    saveFolder = "/media",
                    fileName = "a.mp4",
                    size = 1_000,
                    pauseReasons = setOf(PauseReason.USER),
                    queuedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                    sourceValidator = null,
                )
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe AnydmDb.Schema.version }
        }

    @Test
    fun versionThreeUpgradesWithNoContext() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "3.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 3")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"')",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.sourceValidator shouldBe "etag:\"v1\""
            store.downloads.context(DownloadId(1)) shouldBe null
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionFourUpgradesWithNoChecksum() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "4.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 4")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"')",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.checksum shouldBe null
            store.downloads.get(DownloadId(1))!!.sourceValidator shouldBe "etag:\"v1\""
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionFiveUpgradesWithEmptyTraffic() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "5.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 5")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"', NULL)",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.fileName shouldBe "a.mp4"
            store.traffic.trafficByDay("0000-01-01", "9999-12-31") shouldBe emptyList()
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionSixUpgradesWithNoCategories() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "6.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 6")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, 5, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"', NULL)",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.categoryId shouldBe CategoryId(5)
            store.categories.list() shouldBe emptyList()
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionSevenUpgradesWithDuplicatesNotAllowed() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "7.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 7")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"', NULL)",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.allowDuplicate shouldBe false
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionEightUpgradesWithAnEmptyHistory() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "8.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 8")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"', NULL, 0)",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.fileName shouldBe "a.mp4"
            store.history.list(10) shouldBe emptyList()
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionNineUpgradesWithNothingPlacedYet() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "9.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 9")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"', NULL, 0)",
                )
                raw.exec("INSERT INTO file VALUES (1, 1, 0, 'a.mp4', 1000, 1)")
            }

            val store = open()
            store.downloads
                .files(DownloadId(1))
                .single()
                .placedPath shouldBe null
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionTenUpgradesWithNoTools() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "10.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 10")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"', NULL, 0)",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.fileName shouldBe "a.mp4"
            store.tools.all() shouldBe emptyList()
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionElevenUpgradesWithNoFormatsOrExtractions() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "11.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 11")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'DIRECT', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, 'etag:\"v1\"', NULL, 0)",
                )
            }

            val store = open()
            store.downloads.get(DownloadId(1))!!.fileName shouldBe "a.mp4"
            store.downloads.format(DownloadId(1)) shouldBe null
            store.extractions.get(DownloadId(1)) shouldBe null
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionTwelveUpgradesWithFormatsKept() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "12.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 12")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'SITE', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, NULL, NULL, 0)",
                )
                raw.exec("INSERT INTO format(download_id, preset, height) VALUES (1, 'SMALL', 480)")
            }

            val store = open()
            store.downloads.format(DownloadId(1)) shouldBe Format(QualityPreset.SMALL, height = 480)
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    @Test
    fun versionThirteenUpgradesWithCollectionsKept() =
        runTest {
            val snapshots = requireNotNull(System.getProperty("anydm.schemaDir")) { "anydm.schemaDir is not set" }
            Files.copy(Path(snapshots, "13.db").toNioPath(), file.toNioPath())
            rawDriver(file).use { raw ->
                raw.exec("PRAGMA user_version = 13")
                raw.exec(
                    "INSERT INTO download VALUES (1, 'SITE', 'QUEUED', NULL, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 0, NULL, 0, " +
                        "NULL, '', 1700000000000, NULL, NULL, 0)",
                )
            }

            val store = open()
            val kept = store.downloads.get(DownloadId(1))!!
            kept.playlistIndex shouldBe null
            kept.sourceUrl shouldBe null
            kept.selection shouldBe null
            store.close()
            rawDriver(file).use { it.userVersion() shouldBe 14L }
        }

    // A made-up version 2 whose migration is [change], run on a version 1 file; create is the real schema.
    private class Version2(
        private val change: (SqlDriver) -> Unit,
    ) : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 2L

        override fun create(driver: SqlDriver) = AnydmDb.Schema.create(driver)

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> {
            check(oldVersion == 1L && newVersion == 2L) { "unexpected migration $oldVersion -> $newVersion" }
            change(driver)
            return QueryResult.Unit
        }
    }

    private suspend fun Store.snapshot(): Snapshot {
        val all = downloads.list()
        val files = all.flatMap { downloads.files(it.id) }
        return Snapshot(
            downloads = all,
            mirrors = all.flatMap { downloads.mirrors(it.id) },
            files = files,
            segments = files.flatMap { downloads.segments(it.id) },
            attempts = all.flatMap { downloads.attempts(it.id) },
            settings = settings.load(),
        )
    }

    @Test
    fun aNewFileStartsAtTheCurrentVersion() {
        // Bump this with each .sqm, and add its upgrade case to theFirstSchemaVersionUpgradesCleanly.
        AnydmDb.Schema.version shouldBe 14L

        openDatabaseFile(file).use { it.userVersion() shouldBe 14L }
    }

    @Test
    fun everythingSurvivesReopening() =
        runTest {
            val first = open()
            val parent = first.downloads.add(NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST))
            val child =
                first.downloads.add(
                    NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/a.mp4"), parentId = parent.id, size = 1_000),
                )
            val storedFile = first.downloads.replaceFiles(child.id, listOf(NewFile(0, "a.mp4", size = 1_000))).single()
            val segment = first.downloads.replaceSegments(storedFile.id, listOf(NewSegment(0, 1_000))).single()
            first.downloads.saveWatermarks(mapOf(segment.id to 400L))
            val attempt = first.downloads.addAttempt(NewAttempt(child.id, Instant.fromEpochMilliseconds(1_700_000_000_000)))
            first.downloads.updateAttempt(
                attempt.copy(
                    endedAt = Instant.fromEpochMilliseconds(1_700_000_000_123),
                    errorClass = ErrorClass.TRANSIENT,
                    message = "reset",
                ),
            )
            first.downloads.update(child.copy(status = DownloadStatus.PAUSED, attemptCount = 1))
            // The global pause survives a restart (FND-05).
            first.settings.update { it.copy(globalPause = true, concurrencyLimit = 5) }
            val before = first.snapshot()
            first.close()

            val second = open()
            val after = second.snapshot()
            second.close()

            after shouldBe before
            after.settings shouldBe Settings(concurrencyLimit = 5, globalPause = true)
            after.segments.single().watermark shouldBe 400L
            after.downloads.map { it.status } shouldBe listOf(DownloadStatus.PENDING, DownloadStatus.PAUSED)
        }

    @Test
    fun aDatabaseFromANewerAnydmIsRefused() {
        val newer = AnydmDb.Schema.version + 1
        rawDriver(file).use { it.exec("PRAGMA user_version = $newer") }

        shouldThrow<IncompatibleStoreException> { openDatabaseFile(file) }

        rawDriver(file).use { it.userVersion() shouldBe newer }
    }

    @Test
    fun theFirstSchemaVersionUpgradesCleanly() =
        runTest {
            createVersion1File()

            val store = open()
            val downloads = store.downloads
            downloads.list() shouldBe
                listOf(
                    Download(
                        id = DownloadId(1),
                        kind = DownloadKind.SITE,
                        status = DownloadStatus.PENDING,
                        collection = CollectionKind.PLAYLIST,
                        categoryId = CategoryId(4),
                        profileId = ProfileId(6),
                        saveFolder = "/media",
                        fileName = "list",
                        priority = 1,
                    ),
                    Download(
                        id = DownloadId(2),
                        kind = DownloadKind.DIRECT,
                        status = DownloadStatus.FAILED,
                        parentId = DownloadId(1),
                        saveFolder = "/media",
                        fileName = "a.mp4",
                        size = 1_000,
                        priority = 3,
                        speedLimit = 4_096,
                        attemptCount = 2,
                        nextAttemptAt = Instant.fromEpochMilliseconds(1_700_000_000_123),
                    ),
                )
            val mirror = downloads.mirrors(DownloadId(2)).single()
            mirror shouldBe Mirror(mirror.id, DownloadId(2), "https://example.com/a.mp4", 0)
            val storedFile = downloads.files(DownloadId(2)).single()
            storedFile shouldBe File(storedFile.id, DownloadId(2), 0, "a.mp4", 1_000, true)
            val segment = downloads.segments(storedFile.id).single()
            segment shouldBe Segment(segment.id, storedFile.id, 0, 1_000, 250)
            val attempt = downloads.attempts(DownloadId(2)).single()
            attempt shouldBe
                Attempt(
                    id = attempt.id,
                    downloadId = DownloadId(2),
                    startedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                    mirrorId = mirror.id,
                    endedAt = Instant.fromEpochMilliseconds(1_700_000_000_123),
                    errorClass = ErrorClass.TRANSIENT,
                    message = "reset",
                )
            store.settings.load() shouldBe Settings(globalPause = true)
            // The next id continues from the old file's counter.
            downloads.add(NewDownload(DownloadKind.DIRECT)).id shouldBe DownloadId(3)
            store.close()

            rawDriver(file).use { it.userVersion() shouldBe AnydmDb.Schema.version }
        }

    // The classic way to change a table SQLite can't alter: build the new one, copy, drop the old, rename. Dropping a
    // table with foreign keys on deletes its rows first, and with them everything that cascades from them. So a
    // migration must run with foreign keys off.
    @Test
    fun aTableRebuildInAMigrationKeepsEveryChildRow() {
        createVersion1File()
        val rebuildDownload =
            Version2 { driver ->
                driver.exec(
                    """CREATE TABLE download_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind TEXT NOT NULL,
                        status TEXT NOT NULL,
                        parent_id INTEGER REFERENCES download(id) ON DELETE CASCADE,
                        collection TEXT,
                        category_id INTEGER,
                        profile_id INTEGER,
                        save_folder TEXT,
                        file_name TEXT,
                        size INTEGER CHECK (size >= 0),
                        priority INTEGER NOT NULL,
                        speed_limit INTEGER CHECK (speed_limit > 0),
                        attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
                        next_attempt_at INTEGER,
                        CHECK (parent_id <> id)
                    )""",
                )
                driver.exec("INSERT INTO download_new SELECT * FROM download")
                driver.exec("DROP TABLE download")
                driver.exec("ALTER TABLE download_new RENAME TO download")
                driver.exec("CREATE INDEX download_status ON download(status, priority DESC, id)")
                driver.exec("CREATE INDEX download_parent ON download(parent_id)")
            }

        openDatabaseFile(file, rebuildDownload).use { driver ->
            driver.count("download") shouldBe 2
            driver.longs("SELECT parent_id FROM download WHERE id = 2") shouldBe listOf(1L)
            driver.count("mirror") shouldBe 1
            driver.count("file") shouldBe 1
            driver.count("segment") shouldBe 1
            driver.count("attempt") shouldBe 1
            driver.longs("SELECT mirror_id FROM attempt") shouldBe listOf(1L)
            driver.userVersion() shouldBe 2L
            // The store itself still has foreign keys on.
            driver.longs("PRAGMA foreign_keys") shouldBe listOf(1L)
        }
    }

    @Test
    fun aMigrationThatLeavesAnOrphanIsRolledBack() {
        createVersion1File()
        // With foreign keys off nothing cascades, so this leaves the child rows of download 2 without their parent.
        val orphaning = Version2 { driver -> driver.exec("DELETE FROM download WHERE id = 2") }

        shouldThrow<IncompatibleStoreException> { openDatabaseFile(file, orphaning) }

        rawDriver(file).use { raw ->
            raw.userVersion() shouldBe 1L
            raw.count("download") shouldBe 2
            raw.count("mirror") shouldBe 1
            raw.count("file") shouldBe 1
            raw.count("segment") shouldBe 1
            raw.count("attempt") shouldBe 1
        }
    }

    @Test
    fun aFileWithTablesOfItsOwnIsRefusedAndLeftAlone() {
        rawDriver(file).use { it.exec("CREATE TABLE notes (x)") }

        shouldThrow<IncompatibleStoreException> { openDatabaseFile(file) }

        rawDriver(file).use { raw ->
            raw.texts("SELECT name FROM sqlite_master") shouldBe listOf("notes")
            raw.userVersion() shouldBe 0L
        }
    }

    @Test
    fun aPathThatWouldChangeTheJdbcUrlIsRefused() {
        shouldThrow<IllegalArgumentException> { openDatabaseFile(Path(root, "a?mode=memory.db")) }
        shouldThrow<IllegalArgumentException> { openDatabaseFile(Path(root, "x.db?cache=shared")) }
        shouldThrow<IllegalArgumentException> { openDatabaseFile(Path(root, "mode=memory.db")) }
        namesIn(root) shouldBe emptySet()
    }

    @Test
    fun noJournalFilesAreLeftBehind() =
        runTest {
            val store = open()
            store.downloads.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/a")))
            store.settings.update { it.copy(globalPause = true) }
            store.close()

            // Fails on purpose if the journal mode is changed to WAL without deciding what to do about -wal and -shm.
            namesIn(root) shouldBe setOf(DATABASE_FILE)
        }

    @Test
    fun openStoreCreatesTheDataFolderAndTheDatabase() =
        runTest {
            // The space is what macOS has.
            val dirs =
                AppDirs(
                    data = Path(root, "Application Support", "anydm"),
                    logs = Path(root, "logs"),
                    cache = Path(root, "cache"),
                )

            val opened = openStore(dirs, DesktopFileSystem()).shouldBeInstanceOf<FileResult.Ok<Store>>()
            opened.value.use { store ->
                val added = store.downloads.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/a")))
                store.downloads.get(added.id) shouldBe added
            }

            namesIn(dirs.data) shouldBe setOf(DATABASE_FILE)
            exists(dirs.logs) shouldBe false
            exists(dirs.cache) shouldBe false
        }

    @Test
    fun openStoreReportsADataFolderItCantUse() =
        runTest {
            val data = Path(root, "data").also { writeText(it, "not a folder") }
            val dirs = AppDirs(data = data, logs = Path(root, "logs"), cache = Path(root, "cache"))

            val result = openStore(dirs, DesktopFileSystem()).shouldBeInstanceOf<FileResult.AlreadyExists>()

            result.path shouldBe data
            isFolder(data) shouldBe false
            namesIn(root) shouldBe setOf("data")
        }

    private companion object {
        val V1_ROWS =
            listOf(
                // A version 1 install has user_version 1; SQLDelight's snapshot is left at 0.
                "PRAGMA user_version = 1",
                "INSERT INTO download VALUES (1, 'SITE', 'PENDING', NULL, 'PLAYLIST', 4, 6, '/media', 'list', NULL, 1, NULL, 0, NULL)",
                "INSERT INTO download VALUES (2, 'DIRECT', 'FAILED', 1, NULL, NULL, NULL, '/media', 'a.mp4', 1000, 3, 4096, 2, 1700000000123)",
                "INSERT INTO mirror VALUES (1, 2, 'https://example.com/a.mp4', 0)",
                "INSERT INTO file VALUES (1, 2, 0, 'a.mp4', 1000, 1)",
                "INSERT INTO segment VALUES (1, 1, 0, 1000, 250)",
                "INSERT INTO attempt VALUES (1, 2, 1, 1700000000000, 1700000000123, 'TRANSIENT', 'reset')",
                "INSERT INTO setting VALUES ('queue.global_pause', 'true')",
            )
    }
}
