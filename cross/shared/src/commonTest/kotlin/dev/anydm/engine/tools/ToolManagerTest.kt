package dev.anydm.engine.tools

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.platform.FakeProcessRunner.Scripted
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.InMemoryToolStore
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Settings
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class ToolManagerTest {
    private val linux = Platform(OsFamily.LINUX, Arch.X64)
    private val hash = "a".repeat(64)
    private val ffmpegUrl = "https://example.com/ffmpeg-8.1.3.tar.xz"
    private val toolsRoot = Path("/app/cache/tools")

    private val catalog =
        ToolCatalog(
            entries =
                mapOf(
                    (Tool.FFMPEG to linux) to CatalogEntry("8.1.3", listOf(Artifact(ffmpegUrl, hash, ArchiveKind.TAR_XZ))),
                    (Tool.DENO to linux) to CatalogEntry("2.9.7", listOf(Artifact("https://example.com/deno.zip", hash, ArchiveKind.ZIP))),
                ),
            minimums =
                mapOf(
                    Tool.YT_DLP to ToolVersion.parse("2025.01.01")!!,
                    Tool.FFMPEG to ToolVersion.parse("6.0")!!,
                    Tool.DENO to ToolVersion.parse("2.0.0")!!,
                ),
        )

    private class Rig(
        scope: TestScope,
        catalog: ToolCatalog,
        platform: Platform,
        script: List<Scripted>,
        val installed: Map<String, String>,
    ) {
        val fs = FakeFileSystem()
        val store = InMemoryToolStore()
        val runner = FakeProcessRunner(script)
        var settings = Settings()
        var onPath = installed
        val latest = mutableListOf<Release?>()
        var release: Release? = null
        val jobs = mutableListOf<Download>()
        val added = mutableListOf<NewDownload>()
        var failAdd = false
        private var nextId = 0L
        val clock = scope.virtualClock()

        val manager =
            ToolManager(
                catalog,
                store,
                runner,
                fs,
                finder = { name -> onPath[name]?.let(::Path) },
                releases = { _, _ -> release },
                dirs = AppDirs(Path("/app/data"), Path("/app/logs"), Path("/app/cache")),
                platform = platform,
                settings = { settings },
                clock = clock,
                toolJobs = { jobs.toList() },
            )

        fun setStatus(
            id: DownloadId,
            status: DownloadStatus,
        ) {
            jobs.replaceAll { if (it.id == id) it.copy(status = status) else it }
        }

        val add: suspend (NewDownload) -> CommandResult = { new ->
            delay(1.milliseconds)
            if (failAdd) {
                CommandResult.Invalid("no")
            } else {
                added += new
                val job = Download(DownloadId(++nextId), DownloadKind.TOOL, DownloadStatus.DOWNLOADING, saveFolder = new.saveFolder)
                jobs += job
                CommandResult.Done(job)
            }
        }
    }

    private fun TestScope.rig(
        script: List<Scripted> = emptyList(),
        installed: Map<String, String> = emptyMap(),
    ) = Rig(this, catalog, linux, script, installed)

    private fun version(
        exe: String,
        text: String,
        flag: String = "--version",
    ) = Scripted(listOf(exe, flag), stdout = listOf(text))

    private fun Rig.install(
        tool: Tool,
        version: String,
        vararg files: String,
    ): Path {
        val folder = Path(toolsRoot, tool.id, version)
        files.forEach { fs.put(Path(folder, it), byteArrayOf(1)) }
        return folder
    }

    private suspend fun Rig.record(
        tool: Tool,
        version: String,
        checkedAt: kotlin.time.Instant? = null,
    ) = store.put(ToolRecord(tool, version, Path(toolsRoot, tool.id, version).toString(), checkedAt))

    // --- resolving

    @Test
    fun aSystemCopyThatMeetsTheMinimumIsUsed() =
        runTest {
            val r = rig(listOf(version("/usr/bin/deno", "deno 2.1.4 (stable)")), installed = mapOf("deno" to "/usr/bin/deno"))

            val resolved = r.manager.resolve(Tool.DENO)!!

            resolved.managed shouldBe false
            resolved.version shouldBe ToolVersion.parse("2.1.4")
            resolved.executable("deno") shouldBe Path("/usr/bin/deno")
        }

    @Test
    fun aSystemCopyBelowTheMinimumIsSkipped() =
        runTest {
            val r = rig(listOf(version("/usr/bin/deno", "deno 1.46.0")), installed = mapOf("deno" to "/usr/bin/deno"))

            r.manager.resolve(Tool.DENO) shouldBe null
        }

    @Test
    fun aSystemToolMustHaveAllItsExecutables() =
        runTest {
            val r =
                rig(
                    listOf(version("/usr/bin/ffmpeg", "ffmpeg version 7.0.2", "-version")),
                    installed =
                        mapOf("ffmpeg" to "/usr/bin/ffmpeg"),
                )

            r.manager.resolve(Tool.FFMPEG) shouldBe null
        }

    @Test
    fun aSystemCopyThatDoesNotAnswerIsSkipped() =
        runTest {
            val r = rig(emptyList(), installed = mapOf("deno" to "/usr/bin/deno"))

            r.manager.resolve(Tool.DENO) shouldBe null
        }

    @Test
    fun systemCopiesCanBeSwitchedOff() =
        runTest {
            val r = rig(listOf(version("/usr/bin/deno", "deno 2.1.4")), installed = mapOf("deno" to "/usr/bin/deno"))
            r.settings = Settings(systemTools = false)

            r.manager.resolve(Tool.DENO) shouldBe null
        }

    @Test
    fun theManagedCopyIsUsedWhenThereIsNoSystemOne() =
        runTest {
            val r = rig()
            r.install(Tool.FFMPEG, "8.1.3", "pkg/bin/ffmpeg", "pkg/bin/ffprobe")
            r.record(Tool.FFMPEG, "8.1.3")

            val resolved = r.manager.resolve(Tool.FFMPEG)!!

            resolved.managed shouldBe true
            resolved.executable("ffprobe") shouldBe Path(toolsRoot, "ffmpeg", "8.1.3", "pkg", "bin", "ffprobe")
        }

    @Test
    fun aRecordWhoseFilesAreGoneIsNotResolved() =
        runTest {
            val r = rig()
            r.record(Tool.DENO, "2.9.7")

            r.manager.resolve(Tool.DENO) shouldBe null
        }

    @Test
    fun nothingInstalledResolvesNull() =
        runTest {
            rig().manager.resolve(Tool.YT_DLP) shouldBe null
        }

    // --- installing

    @Test
    fun ensureAddsOneToolJobWithThePinnedChecksum() =
        runTest {
            val r = rig()

            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true

            r.added shouldBe
                listOf(
                    NewDownload(
                        DownloadKind.TOOL,
                        mirrors = listOf(ffmpegUrl),
                        saveFolder = "/app/cache/tools/ffmpeg/8.1.3",
                        fileName = "ffmpeg-8.1.3.tar.xz",
                        checksum = Checksum(ChecksumAlgorithm.SHA256, hash),
                        allowDuplicate = true,
                    ),
                )
        }

    @Test
    fun twoConcurrentEnsuresAddOneJob() =
        runTest {
            val r = rig()

            val both = listOf(async { r.manager.ensure(Tool.FFMPEG, r.add) }, async { r.manager.ensure(Tool.FFMPEG, r.add) }).awaitAll()

            both shouldBe listOf(true, true)
            r.added.size shouldBe 1
        }

    @Test
    fun ensureWhileTheJobIsStillRunningAddsNothing() =
        runTest {
            val r = rig()
            r.manager.ensure(Tool.FFMPEG, r.add)

            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true

            r.added.size shouldBe 1
        }

    @Test
    fun ensureAfterAFailedJobAddsANewOne() =
        runTest {
            val r = rig()
            r.manager.ensure(Tool.FFMPEG, r.add)
            r.setStatus(DownloadId(1), DownloadStatus.FAILED)

            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true

            r.added.size shouldBe 2
        }

    @Test
    fun ensureAfterACancelledJobAddsANewOne() =
        runTest {
            val r = rig()
            r.manager.ensure(Tool.FFMPEG, r.add)
            r.setStatus(DownloadId(1), DownloadStatus.CANCELLED)

            r.manager.ensure(Tool.FFMPEG, r.add)

            r.added.size shouldBe 2
        }

    @Test
    fun aFailedAddIsReportedAndLeavesNothingInFlight() =
        runTest {
            val r = rig()
            r.failAdd = true
            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe false

            r.failAdd = false
            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true
            r.added.size shouldBe 1
        }

    @Test
    fun ensureForAnUnsupportedPlatformIsFalse() =
        runTest {
            val r = Rig(this, catalog, Platform(OsFamily.MACOS, Arch.ARM64), emptyList(), emptyMap())

            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe false
            r.added shouldBe emptyList()
        }

    @Test
    fun ytDlpComesFromTheReleaseSourceAsABareBinary() =
        runTest {
            val r = rig()
            r.release = Release("2026.10.01", "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.01/yt-dlp_linux", "b".repeat(64))

            r.manager.ensure(Tool.YT_DLP, r.add) shouldBe true

            r.added.single().apply {
                mirrors shouldBe listOf("https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.01/yt-dlp_linux")
                saveFolder shouldBe "/app/cache/tools/yt-dlp/2026.10.01"
                fileName shouldBe "yt-dlp"
                checksum shouldBe Checksum(ChecksumAlgorithm.SHA256, "b".repeat(64))
            }
        }

    @Test
    fun ytDlpWithNoReleaseToReadIsFalse() =
        runTest {
            rig().manager.ensure(Tool.YT_DLP, rig().add) shouldBe false
        }

    // --- completion

    private fun toolJob(
        tool: Tool,
        version: String,
    ) = Download(DownloadId(1), DownloadKind.TOOL, saveFolder = "/app/cache/tools/${tool.id}/$version")

    @Test
    fun installedRecordsTheToolItsVersionAndTheCheckTime() =
        runTest {
            val r = rig()
            r.install(Tool.DENO, "2.9.7", "deno")

            r.manager.installed(toolJob(Tool.DENO, "2.9.7"), emptyList()) shouldBe true

            r.store.get(Tool.DENO) shouldBe ToolRecord(Tool.DENO, "2.9.7", "/app/cache/tools/deno/2.9.7", r.clock.now())
        }

    @Test
    fun installedWaitsForTheRestOfTheExecutables() =
        runTest {
            val r = rig()
            r.install(Tool.FFMPEG, "8.1.3", "ffmpeg") // ffprobe's own download hasn't finished

            r.manager.installed(toolJob(Tool.FFMPEG, "8.1.3"), emptyList()) shouldBe false

            r.store.get(Tool.FFMPEG) shouldBe null
        }

    @Test
    fun installedForgetsTheJobsInFlight() =
        runTest {
            val r = rig()
            r.manager.ensure(Tool.DENO, r.add)
            r.setStatus(DownloadId(1), DownloadStatus.COMPLETED)
            r.install(Tool.DENO, "2.9.7", "deno")
            r.manager.installed(toolJob(Tool.DENO, "2.9.7"), emptyList())
            r.store.put(ToolRecord(Tool.DENO, "2.9.7", "x", null))
            r.fs.deleteTree(Path(toolsRoot, "deno", "2.9.7"))

            r.manager.ensure(Tool.DENO, r.add) shouldBe true

            r.added.size shouldBe 2
        }

    @Test
    fun oldVersionFoldersAreRemovedKeepingTheCurrentAndThePreviousOne() =
        runTest {
            val r = rig()
            for (v in listOf("1.0.0", "2.0.0", "3.0.0")) {
                r.install(Tool.DENO, v, "deno")
                r.manager.installed(toolJob(Tool.DENO, v), emptyList())
            }

            r.fs.exists(Path(toolsRoot, "deno", "1.0.0", "deno")) shouldBe false
            r.fs.exists(Path(toolsRoot, "deno", "2.0.0", "deno")) shouldBe true
            r.fs.exists(Path(toolsRoot, "deno", "3.0.0", "deno")) shouldBe true
        }

    @Test
    fun aFolderOutsideTheToolsFolderIsNeverTouched() =
        runTest {
            val r = rig()
            r.fs.put(Path("/elsewhere/deno/2.9.7/deno"), byteArrayOf(1))

            r.manager.installed(Download(DownloadId(1), DownloadKind.TOOL, saveFolder = "/elsewhere/deno/2.9.7"), emptyList()) shouldBe
                false

            r.fs.exists(Path("/elsewhere/deno/2.9.7/deno")) shouldBe true
            r.store.get(Tool.DENO) shouldBe null
        }

    @Test
    fun aFailedInstallLeavesThePreviousVersionCurrent() =
        runTest {
            val r = rig()
            r.install(Tool.DENO, "2.0.0", "deno")
            r.record(Tool.DENO, "2.0.0")
            // 2.9.7's job failed its self-test: installed() is never called for it.

            r.manager.resolve(Tool.DENO)!!.version shouldBe ToolVersion.parse("2.0.0")
        }

    // --- updates

    private suspend fun Rig.ytDlp(
        version: String,
        checkedAt: kotlin.time.Instant?,
    ) {
        install(Tool.YT_DLP, version, "yt-dlp")
        record(Tool.YT_DLP, version, checkedAt)
    }

    private val newer = Release("2026.11.01", "https://example.com/yt-dlp", "c".repeat(64))

    @Test
    fun refreshDoesNothingWithinADay() =
        runTest {
            val r = rig()
            r.ytDlp("2026.10.01", r.clock.now() - 23.hours)
            r.release = newer

            r.manager.refreshIfStale(Tool.YT_DLP, r.add)

            r.added shouldBe emptyList()
        }

    @Test
    fun refreshInstallsANewerVersionAndRecordsTheCheck() =
        runTest {
            val r = rig()
            val now = r.clock.now()
            r.ytDlp("2026.10.01", now - 25.hours)
            r.release = newer

            r.manager.refreshIfStale(Tool.YT_DLP, r.add)

            r.added.single().saveFolder shouldBe "/app/cache/tools/yt-dlp/2026.11.01"
            r.store.get(Tool.YT_DLP)!!.checkedAt shouldBe now
            r.store.get(Tool.YT_DLP)!!.version shouldBe "2026.10.01" // the pointer moves only when the new one is installed
        }

    @Test
    fun refreshIgnoresAnOlderOrEqualVersion() =
        runTest {
            val r = rig()
            r.ytDlp("2026.10.01", null)
            r.release = Release("2026.10.01", "https://example.com/y", "c".repeat(64))

            r.manager.refreshIfStale(Tool.YT_DLP, r.add)

            r.added shouldBe emptyList()
            r.store.get(Tool.YT_DLP)!!.checkedAt shouldBe r.clock.now()
        }

    @Test
    fun refreshSkipsWhenNoReleaseCouldBeRead() =
        runTest {
            val r = rig()
            r.ytDlp("2026.10.01", null)
            r.release = null

            r.manager.refreshIfStale(Tool.YT_DLP, r.add)

            r.added shouldBe emptyList()
            r.store.get(Tool.YT_DLP)!!.checkedAt shouldBe null
        }

    @Test
    fun refreshOnlyAppliesToYtDlp() =
        runTest {
            val r = rig()
            r.install(Tool.DENO, "2.0.0", "deno")
            r.record(Tool.DENO, "2.0.0")
            r.release = newer

            r.manager.refreshIfStale(Tool.DENO, r.add)

            r.added shouldBe emptyList()
        }

    @Test
    fun refreshWithNothingInstalledLeavesItToEnsure() =
        runTest {
            val r = rig()
            r.release = newer

            r.manager.refreshIfStale(Tool.YT_DLP, r.add)

            r.added shouldBe emptyList()
        }

    @Test
    fun refreshLeavesASystemCopyAlone() =
        runTest {
            val r = rig(listOf(version("/usr/bin/yt-dlp", "2026.10.01")), installed = mapOf("yt-dlp" to "/usr/bin/yt-dlp"))
            r.ytDlp("2026.09.01", null)
            r.release = newer

            r.manager.refreshIfStale(Tool.YT_DLP, r.add)

            r.added shouldBe emptyList()
        }

    @Test
    fun aSystemFfmpegIsTestedWithItsOwnFlagAndReadWithTheNPrefix() =
        runTest {
            val r =
                rig(
                    listOf(
                        version("/usr/bin/ffmpeg", "ffmpeg version n7.1 Copyright (c) 2000-2024", "-version"),
                        version("/usr/bin/ffprobe", "ffprobe version n7.1 Copyright (c) 2007-2024", "-version"),
                    ),
                    installed = mapOf("ffmpeg" to "/usr/bin/ffmpeg", "ffprobe" to "/usr/bin/ffprobe"),
                )

            r.manager.resolve(Tool.FFMPEG)!!.version shouldBe ToolVersion.parse("7.1")
        }

    private fun foreign(
        tool: Tool,
        status: DownloadStatus,
        id: Long = 9,
    ) = Download(DownloadId(id), DownloadKind.TOOL, status, saveFolder = "/app/cache/tools/${tool.id}/8.1.3")

    @Test
    fun aToolJobFromBeforeARestartIsNotDuplicated() =
        runTest {
            val r = rig()
            r.jobs += foreign(Tool.FFMPEG, DownloadStatus.DOWNLOADING)

            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true

            r.added shouldBe emptyList()
        }

    @Test
    fun aCompletedJobNotYetInstalledStillCounts() =
        runTest {
            val r = rig()
            r.jobs += foreign(Tool.FFMPEG, DownloadStatus.COMPLETED)

            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true

            r.added shouldBe emptyList()
        }

    @Test
    fun aCompletedJobThatInstalledNothingIsFetchedAgain() =
        runTest {
            val r = rig()
            val job = foreign(Tool.FFMPEG, DownloadStatus.COMPLETED)
            r.jobs += job
            r.install(Tool.FFMPEG, "8.1.3", "bin/ffmpeg") // ffprobe never came

            r.manager.installed(job, emptyList()) shouldBe false
            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true

            r.added.size shouldBe 1
        }

    @Test
    fun aJobOfAnotherToolDoesNotCount() =
        runTest {
            val r = rig()
            r.jobs += foreign(Tool.DENO, DownloadStatus.DOWNLOADING)

            r.manager.ensure(Tool.FFMPEG, r.add) shouldBe true

            r.added.size shouldBe 1
        }

    @Test
    fun reinstallingTheCurrentVersionKeepsThePreviousOne() =
        runTest {
            val r = rig()
            for (v in listOf("1.0.0", "2.0.0", "2.0.0")) {
                r.install(Tool.DENO, v, "deno")
                r.manager.installed(toolJob(Tool.DENO, v), emptyList())
            }

            r.fs.exists(Path(toolsRoot, "deno", "1.0.0", "deno")) shouldBe true
        }
}
