package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.QualityPreset
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.DesktopProcessRunner
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test

/**
 * Opt-in, kept out of CI: downloads a real page with the yt-dlp and ffmpeg on PATH. Run with
 * `-Danydm.live.url=<page>` passed to the test JVM.
 */
class LiveMediaSmokeTest {
    @Test
    fun aRealPageDownloadsAtTheSmallPreset() {
        val url = System.getProperty("anydm.live.url")
        assumeTrue("set anydm.live.url to run this test", url != null)
        val processes = DesktopProcessRunner()
        val root = Files.createTempDirectory("anydm-live-").toRealPath().toFile()
        val store = Store(openInMemoryDatabase(), Dispatchers.IO)
        try {
            runBlocking<Unit> {
                suspend fun onPath(tool: Tool): ResolvedTool? {
                    val found = processes.run(ProcessSpec(listOf("which", tool.id))) as? ProcessOutcome.Ok ?: return null
                    val path =
                        found.value.stdout
                            .trim()
                            .takeIf { found.value.exitCode == 0 && it.isNotEmpty() } ?: return null
                    return ResolvedTool(tool, ToolVersion.parse("1")!!, mapOf(tool.id to Path(path)), managed = false)
                }
                val ytDlp = onPath(Tool.YT_DLP)
                val ffmpeg = onPath(Tool.FFMPEG)
                assumeTrue("yt-dlp and ffmpeg must be on PATH", ytDlp != null && ffmpeg != null)
                val site =
                    SiteProtocol(
                        HttpProtocol(OkHttpTransport()),
                        processes,
                        {
                            if (it == Tool.YT_DLP) {
                                ytDlp
                            } else if (it == Tool.FFMPEG) {
                                ffmpeg
                            } else {
                                onPath(it)
                            }
                        },
                        store.extractions,
                        DesktopFileSystem(),
                    )
                val runner =
                    StepRunner(
                        DesktopFileSystem(),
                        store.downloads,
                        mapOf(DownloadKind.SITE to site),
                        Path(root.toString()),
                        freeSpaceReserve = 0,
                    )
                val download =
                    store.downloads.add(
                        NewDownload(
                            DownloadKind.SITE,
                            mirrors = listOf(url),
                            saveFolder = root.toString(),
                            format = Format(QualityPreset.SMALL),
                        ),
                    )
                val ready =
                    runner
                        .prepare(download, store.downloads.mirrors(download.id).single(), RunListener.None)
                        .shouldBeInstanceOf<PrepareOutcome.Ready>()

                val finished = runner.run(ready).shouldBeInstanceOf<RunOutcome.Finished>()

                finished.outputs.isNotEmpty() shouldBe true
                finished.outputs.all { java.io.File(it.toString()).length() > 0 } shouldBe true
            }
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }
}
