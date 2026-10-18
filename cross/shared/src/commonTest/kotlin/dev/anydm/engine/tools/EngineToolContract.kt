package dev.anydm.engine.tools

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineHarness
import dev.anydm.engine.EngineState
import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.hasherFor
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.fakeContent
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** The engine, the queue and the ToolManager together: a job waits for a missing tool and comes back (BIN-01). */
abstract class EngineToolContract : StoreTestBase() {
    private val linux = Platform(OsFamily.LINUX, Arch.X64)
    private val size = 4096L
    private val folder = Path("/app/cache/tools/ffmpeg/8.1.3")
    private val sum = hasherFor(ChecksumAlgorithm.SHA256).also { it.update(fakeContent(0, 0, size.toInt())) }.hex()

    private class Rig(
        val h: EngineHarness,
        val site: FakeProtocol,
        val toolProtocol: FakeProtocol,
    ) {
        fun toolJobs() =
            h.engine.downloads.value
                .filter { it.download.kind == DownloadKind.TOOL }
    }

    /** The tool "unpacks" ffmpeg and ffprobe when its job finalizes, and the SITE jobs stop failing at the same moment. */
    private suspend fun TestScope.rig(
        withManager: Boolean = true,
        preInstalled: Boolean = false,
        withEntry: Boolean = true,
        failOnce: Boolean = false,
        releases: ReleaseSource = ReleaseSource { _, _ -> null },
    ): Rig {
        val store = newStore()
        val fs = FakeFileSystem()
        val siteInner =
            FakeProtocol(failure = if (preInstalled) null else FakeFailure.AtProbe(StepResult.Failed(ErrorClass.TOOL_MISSING, "ffmpeg")))
        // With failOnce the job's first probe finds the tool missing even though it is installed, as in a race.
        var failures = if (failOnce) 1 else 0
        val site: FakeProtocol = siteInner
        val siteProtocol =
            object : Protocol by siteInner {
                override suspend fun probe(
                    request: dev.anydm.engine.protocol.ProbeRequest,
                ): StepResult<dev.anydm.engine.protocol.ProbeResult> {
                    if (failures > 0) {
                        failures--
                        return StepResult.Failed(ErrorClass.TOOL_MISSING, "ffmpeg")
                    }
                    return siteInner.probe(request)
                }
            }
        val toolInner = FakeProtocol(files = listOf(FakeFile("ffmpeg-8.1.3.tar.xz", size)))
        val toolProtocol =
            object : Protocol by toolInner {
                override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
                    fs.put(Path(folder, "bin", "ffmpeg"), byteArrayOf(1))
                    fs.put(Path(folder, "bin", "ffprobe"), byteArrayOf(1))
                    site.failure = null
                    return toolInner.finalize(transfer)
                }
            }
        val clock = virtualClock()
        val catalog =
            ToolCatalog(
                if (withEntry) {
                    mapOf(
                        (Tool.FFMPEG to linux) to
                            CatalogEntry("8.1.3", listOf(Artifact("https://example.com/ffmpeg-8.1.3.tar.xz", sum, ArchiveKind.TAR_XZ))),
                    )
                } else {
                    emptyMap()
                },
                mapOf(Tool.FFMPEG to ToolVersion.parse("6.0")!!),
            )
        val manager =
            ToolManager(
                catalog,
                store.tools,
                FakeProcessRunner(emptyList()),
                fs,
                finder = { null },
                releases = releases,
                dirs = AppDirs(Path("/app/data"), Path("/app/logs"), Path("/app/cache")),
                platform = linux,
                settings = { store.settings.load() },
                clock = clock,
                toolJobs = { store.downloads.list().filter { it.kind == DownloadKind.TOOL } },
            )
        val h =
            EngineHarness(
                this,
                store,
                protocols =
                    mapOf(
                        DownloadKind.SITE to siteProtocol,
                        DownloadKind.TOOL to toolProtocol,
                        DownloadKind.DIRECT to siteProtocol,
                    ),
                fs = fs,
                clock = clock,
                tools = if (withManager) manager else null,
            )
        if (preInstalled) {
            fs.put(Path(folder, "bin", "ffmpeg"), byteArrayOf(1))
            fs.put(Path(folder, "bin", "ffprobe"), byteArrayOf(1))
            store.tools.put(ToolRecord(Tool.FFMPEG, "8.1.3", folder.toString(), null))
        }
        return Rig(h, site, toolInner)
    }

    private suspend fun Rig.addSite(name: String): DownloadId =
        (
            h.engine.add(
                NewDownload(DownloadKind.SITE, mirrors = listOf("https://example.com/$name"), saveFolder = "/dl", fileName = "$name.mp4"),
            )
                as dev.anydm.engine.queue.CommandResult.Done
        ).download!!.id

    private fun TestScope.settleEngine() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    @Test
    fun aJobPausedForAMissingToolIsReleasedWhenTheToolJobCompletes() =
        runTest {
            val r = rig()
            r.h.engine.start()
            try {
                val job = r.addSite("a")
                settleEngine()

                r.toolJobs().map { it.download.status } shouldBe listOf(COMPLETED)
                r.h.store.tools
                    .get(Tool.FFMPEG)!!
                    .version shouldBe "8.1.3"
                r.h.status(job) shouldBe COMPLETED
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun oneToolJobServesEveryWaitingJob() =
        runTest {
            val r = rig()
            r.h.engine.start()
            try {
                val a = r.addSite("a")
                val b = r.addSite("b")
                settleEngine()

                r.toolJobs().size shouldBe 1
                r.h.status(a) shouldBe COMPLETED
                r.h.status(b) shouldBe COMPLETED
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun aFailedToolJobLeavesTheJobsPausedAndTheNextOneTriesAgain() =
        runTest {
            val r = rig()
            r.toolProtocol.failure = FakeFailure.AtProbe(StepResult.Failed(null, "no route"))
            r.h.engine.start()
            try {
                val a = r.addSite("a")
                settleEngine()
                r.toolJobs().map { it.download.status } shouldBe listOf(FAILED)
                r.h.status(a) shouldBe PAUSED
                r.h
                    .snapshot(a)!!
                    .download.pauseReasons shouldBe setOf(PauseReason.TOOLS)

                r.addSite("b")
                settleEngine()

                r.toolJobs().size shouldBe 2
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun withoutAToolManagerTheJobStaysPaused() =
        runTest {
            val r = rig(withManager = false)
            r.h.engine.start()
            try {
                val a = r.addSite("a")
                settleEngine()

                r.toolJobs() shouldBe emptyList()
                r.h.status(a) shouldBe PAUSED
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun aJobThatPausesJustAfterTheToolWasInstalledIsStillReleased() =
        runTest {
            val r = rig(preInstalled = true, failOnce = true)
            r.h.engine.start()
            try {
                val a = r.addSite("a")
                settleEngine()

                r.toolJobs() shouldBe emptyList()
                r.h.status(a) shouldBe COMPLETED
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun aJobPausedForToolsBeforeARestartIsReleasedAtStart() =
        runTest {
            val r = rig(preInstalled = true)
            val stored =
                r.h.store.downloads.add(
                    NewDownload(DownloadKind.SITE, mirrors = listOf("https://example.com/a"), saveFolder = "/dl", fileName = "a.mp4"),
                )
            r.h.store.downloads
                .update(stored.copy(status = PAUSED, pauseReasons = setOf(PauseReason.TOOLS)))
            r.h.engine.start()
            try {
                settleEngine()

                r.h.status(stored.id) shouldBe COMPLETED
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun aToolJobCompletedBeforeARestartIsRecordedAtStart() =
        runTest {
            val r = rig(preInstalled = false)
            r.h.fs.put(Path(folder, "bin", "ffmpeg"), byteArrayOf(1))
            r.h.fs.put(Path(folder, "bin", "ffprobe"), byteArrayOf(1))
            val stored =
                r.h.store.downloads.add(
                    NewDownload(
                        DownloadKind.TOOL,
                        mirrors = listOf("https://example.com/x"),
                        saveFolder = folder.toString(),
                        fileName = "x.tar.xz",
                    ),
                )
            r.h.store.downloads
                .update(stored.copy(status = COMPLETED))
            r.h.engine.start()
            try {
                settleEngine()

                r.h.store.tools
                    .get(Tool.FFMPEG)!!
                    .version shouldBe "8.1.3"
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun aToolThatCannotBeFetchedIsReportedAndTheJobStaysPaused() =
        runTest {
            val r = rig(withEntry = false)
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch {
                r.h.engine.events
                    .collect { events += it }
            }
            runCurrent() // the collector is subscribed before anything is emitted
            r.h.engine.start()
            try {
                val a = r.addSite("a")
                settleEngine()

                events.filterIsInstance<EngineEvent.ToolUnavailable>() shouldBe listOf(EngineEvent.ToolUnavailable(a, "ffmpeg"))
                r.h.status(a) shouldBe PAUSED
                r.toolJobs() shouldBe emptyList()
            } finally {
                r.h.engine.stop()
            }
        }

    @Test
    fun aFailureInTheToolManagerDoesNotFailTheEngine() =
        runTest {
            val r = rig(releases = ReleaseSource { _, _ -> error("boom") })
            r.site.failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.TOOL_MISSING, "yt-dlp"))
            r.h.engine.start()
            try {
                r.addSite("a")
                settleEngine()

                r.h.engine.state.value shouldBe EngineState.Running
            } finally {
                r.h.engine.stop()
            }
        }
}
